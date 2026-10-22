/*
 * Copyright (c) 2015-2026 by Jikoo.
 *
 * Regionerator is licensed under a Creative Commons
 * Attribution-ShareAlike 4.0 International License.
 *
 * You should have received a copy of the license along with this
 * work. If not, see <http://creativecommons.org/licenses/by-sa/4.0/>.
 */

package com.github.jikoo.regionerator.world.impl.anvil;

import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Region file access coordinated with Paper's chunk system (Moonrise), used by Paper and its forks.
 *
 * <p>Paper only opens, evicts and closes region files while holding the lock of the
 * {@code RegionFileStorage} that caches them, and only reads or writes an open file while holding that
 * file's own lock. While Regionerator holds a storage's lock and the region's file is absent from that
 * storage's cache, the server has no usable handle to the file and cannot open one. Files are edited
 * only in that state, with the locks of all three storages (block, entity and POI data) held so a
 * chunk load cannot observe some data types edited and others not.
 *
 * <p>Paper queues a chunk's saves before it drops the chunk from memory, and keeps a queued save
 * registered until it has been written. A chunk with no chunk holder and no queued I/O therefore has
 * nothing pending that could be written back over an edit.
 *
 * <p>Verified against Paper 26.3. Everything is looked up reflectively; if anything is missing the
 * server is treated as incompatible and Regionerator does not modify region files.
 */
final class MoonriseRegionFileAccess extends RegionFileAccess {

	private static final String IO_PACKAGE = "ca.spottedleaf.moonrise.patches.chunk_system.io.";
	/** Region file types in the same order as {@link AnvilRegion#DATA_SUBDIRS}. */
	private static final String[] REGION_FILE_TYPES = { "CHUNK_DATA", "ENTITY_DATA", "POI_DATA" };

	private final @NotNull Object level;
	private final @NotNull Object @NotNull [] regionFileTypes = new Object[REGION_FILE_TYPES.length];
	private final @NotNull Object @NotNull [] storages = new Object[REGION_FILE_TYPES.length];
	private final @NotNull Path @NotNull [] folders = new Path[REGION_FILE_TYPES.length];
	private final @NotNull Field regionCache;
	private final @NotNull Method regionCacheGet;
	private final @NotNull Method packRegionKey;
	private final @NotNull Method getIoPriority;
	private final @NotNull Object noQueuedIo;
	private final @NotNull Object chunkHolderManager;
	private final @NotNull Method getChunkHolder;

	/**
	 * Creates access coordinated with the server's chunk system for a world.
	 *
	 * @param world the world
	 * @return the access
	 * @throws ReflectiveOperationException if the server does not provide the expected internals
	 * @throws IOException if the server's state cannot be checked
	 */
	static @NotNull MoonriseRegionFileAccess create(@NotNull World world) throws ReflectiveOperationException, IOException {
		MoonriseRegionFileAccess access = new MoonriseRegionFileAccess(world);
		// Exercise every lookup once so an incompatible server fails now rather than mid-deletion.
		access.isRegionInUse(0, 0);
		access.isChunkBusy(0, 0);
		return access;
	}

	private MoonriseRegionFileAccess(@NotNull World world) throws ReflectiveOperationException {
		level = world.getClass().getMethod("getHandle").invoke(world);

		Class<?> regionFileIo = Class.forName(IO_PACKAGE + "MoonriseRegionFileIO");
		Class<?> regionFileType = Class.forName(IO_PACKAGE + "MoonriseRegionFileIO$RegionFileType");
		Class<?> regionFileStorage = Class.forName(IO_PACKAGE + "ChunkSystemRegionFileStorage");
		Method getControllerFor = findMethod(regionFileIo, "getControllerFor", 2);

		for (int i = 0; i < REGION_FILE_TYPES.length; ++i) {
			regionFileTypes[i] = enumConstant(regionFileType, REGION_FILE_TYPES[i]);
			Object controller = getControllerFor.invoke(null, level, regionFileTypes[i]);
			Object storage = controller.getClass().getMethod("getCache").invoke(controller);
			if (!regionFileStorage.isInstance(storage)) {
				throw new ReflectiveOperationException("Unexpected region storage " + storage.getClass().getName());
			}
			storages[i] = storage;

			Path folder = ((Path) findField(storage.getClass(), "folder").get(storage)).toAbsolutePath().normalize();
			String subdirectory = AnvilRegion.DATA_SUBDIRS[i];
			if (folder.getFileName() == null || !folder.getFileName().toString().equals(subdirectory)) {
				throw new ReflectiveOperationException("Unexpected " + subdirectory + " storage folder " + folder);
			}
			folders[i] = folder;
		}

		// Read the storages' caches directly. The storage's own lookup moves the file to the front of the
		// cache's eviction order, and reordering it makes Paper evict files its I/O threads are still using.
		regionCache = findField(storages[0].getClass(), "regionCache");
		regionCacheGet = regionCache.get(storages[0]).getClass().getMethod("get", long.class);
		packRegionKey = Class.forName("net.minecraft.world.level.ChunkPos").getMethod("pack", int.class, int.class);

		getIoPriority = findMethod(regionFileIo, "getPriority", 4);
		// Moonrise reports COMPLETING when no I/O task exists for a chunk.
		noQueuedIo = enumConstant(getIoPriority.getReturnType(), "COMPLETING");

		Object scheduler = findMethod(level.getClass(), "moonrise$getChunkTaskScheduler", 0).invoke(level);
		chunkHolderManager = scheduler.getClass().getField("chunkHolderManager").get(scheduler);
		getChunkHolder = chunkHolderManager.getClass().getMethod("getChunkHolder", int.class, int.class);
	}

	@Override
	@NotNull Path getFolder(@NotNull String subdirectory) {
		for (int i = 0; i < folders.length; ++i) {
			if (AnvilRegion.DATA_SUBDIRS[i].equals(subdirectory)) {
				return folders[i];
			}
		}
		throw new IllegalArgumentException("Unknown region data subdirectory " + subdirectory);
	}

	@Override
	boolean canModify() {
		return true;
	}

	@Override
	boolean isRegionInUse(int regionX, int regionZ) throws IOException {
		for (Object storage : storages) {
			synchronized (storage) {
				if (getLoadedRegionFile(storage, regionX, regionZ) != null) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	boolean runExclusive(int regionX, int regionZ, @NotNull ExclusiveAction action) throws IOException {
		return runExclusive(0, regionX, regionZ, action);
	}

	private boolean runExclusive(int index, int regionX, int regionZ, @NotNull ExclusiveAction action) throws IOException {
		if (index == storages.length) {
			return action.run();
		}

		Object storage = storages[index];
		// The server takes a storage's lock before any region file lock and never holds two storages'
		// locks at once, so taking the storages' locks in a fixed order cannot deadlock with it.
		synchronized (storage) {
			if (getLoadedRegionFile(storage, regionX, regionZ) != null) {
				// The server has the file open and may write to it at any time.
				return false;
			}
			return runExclusive(index + 1, regionX, regionZ, action);
		}
	}

	@Override
	boolean isChunkBusy(int chunkX, int chunkZ) throws IOException {
		try {
			if (getChunkHolder.invoke(chunkHolderManager, chunkX, chunkZ) != null) {
				return true;
			}
			for (Object type : regionFileTypes) {
				if (getIoPriority.invoke(null, level, chunkX, chunkZ, type) != noQueuedIo) {
					return true;
				}
			}
			return false;
		} catch (ReflectiveOperationException e) {
			throw unwrap("Unable to check server state for chunk " + chunkX + ", " + chunkZ, e);
		}
	}

	/**
	 * Gets the server's open handle for a region file. The caller must hold the storage's lock.
	 */
	private @Nullable Object getLoadedRegionFile(@NotNull Object storage, int regionX, int regionZ) throws IOException {
		try {
			Object cached = regionCacheGet.invoke(regionCache.get(storage), packRegionKey.invoke(null, regionX, regionZ));
			// Paper caches Optional<RegionFile>.
			return cached instanceof Optional<?> optional ? optional.orElse(null) : cached;
		} catch (ReflectiveOperationException e) {
			throw unwrap("Unable to check server state for region " + regionX + ", " + regionZ, e);
		}
	}

	private static @NotNull IOException unwrap(@NotNull String message, @NotNull ReflectiveOperationException e) {
		Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
		return new IOException(message, cause);
	}

	private static @NotNull Method findMethod(@NotNull Class<?> clazz, @NotNull String name, int parameters)
			throws NoSuchMethodException {
		for (Method method : clazz.getMethods()) {
			if (method.getName().equals(name) && method.getParameterCount() == parameters) {
				return method;
			}
		}
		throw new NoSuchMethodException(clazz.getName() + "#" + name);
	}

	private static @NotNull Field findField(@NotNull Class<?> clazz, @NotNull String name) throws NoSuchFieldException {
		for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
			try {
				Field field = current.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
				// Check superclass.
			}
		}
		throw new NoSuchFieldException(clazz.getName() + "#" + name);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static @NotNull Object enumConstant(@NotNull Class<?> clazz, @NotNull String name) {
		return Enum.valueOf((Class) clazz.asSubclass(Enum.class), name);
	}

}
