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

import com.github.jikoo.planarwrappers.util.Coords;
import com.github.jikoo.regionerator.DebugLevel;
import com.github.jikoo.regionerator.world.ChunkInfo;
import com.github.jikoo.regionerator.world.RegionInfo;
import com.google.common.base.Preconditions;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;

public class AnvilRegion extends RegionInfo {

	private static final int CHUNKS_PER_AXIS = 32;
	// Regions are a square of chunks.
	private static final int CHUNK_COUNT = CHUNKS_PER_AXIS * CHUNKS_PER_AXIS;
	private static final String SUBDIR_BLOCK_DATA = "region";
	private static final String SUBDIR_ENTITY_DATA = "entities";
	static final String[] DATA_SUBDIRS = { SUBDIR_BLOCK_DATA, SUBDIR_ENTITY_DATA, "poi" };

	private final boolean[] pointerWipes = new boolean[CHUNK_COUNT];
	// Header entries as read, used to detect chunks saved between reading and writing.
	private final int[] readBlockOffsets = new int[CHUNK_COUNT];
	private final int[] readBlockTimes = new int[CHUNK_COUNT];
	private final int[] readEntityOffsets = new int[CHUNK_COUNT];
	private final int[] readEntityTimes = new int[CHUNK_COUNT];
	private final ByteBuffer volatileRegionHeader;
	private final IntBuffer volatileChunkUsage;
	private final IntBuffer volatileChunkTimes;
	private final ByteBuffer storedRegionHeader;
	private final IntBuffer storedChunkUsage;
	private final IntBuffer storedChunkTimes;
	private final ByteBuffer chunkHeader;
	private final @NotNull RegionFileAccess access;
	private final int regionX;
	private final int regionZ;
	private final @NotNull String fileName;

	AnvilRegion(
					@NotNull AnvilWorld world,
					@NotNull RegionFileAccess access,
					int regionX,
					int regionZ,
					@NotNull String fileFormat) {
		super(world, Coords.regionToChunk(regionX), Coords.regionToChunk(regionZ));
		this.access = access;
		this.regionX = regionX;
		this.regionZ = regionZ;
		this.fileName = String.format(fileFormat, regionX, regionZ);

		volatileRegionHeader = ByteBuffer.allocateDirect(RegionFile.REGION_HEADER_LENGTH);
		volatileChunkUsage = volatileRegionHeader.slice(0, RegionFile.SECTOR_BYTES).asIntBuffer();
		volatileChunkTimes = volatileRegionHeader.slice(RegionFile.SECTOR_BYTES, RegionFile.SECTOR_BYTES).asIntBuffer();
		storedRegionHeader = ByteBuffer.allocateDirect(volatileRegionHeader.capacity());
		storedChunkUsage = storedRegionHeader.slice(0, RegionFile.SECTOR_BYTES).asIntBuffer();
		storedChunkTimes = storedRegionHeader.slice(RegionFile.SECTOR_BYTES, RegionFile.SECTOR_BYTES).asIntBuffer();
		chunkHeader = ByteBuffer.allocateDirect(RegionFile.CHUNK_HEADER_LENGTH);
	}

	@Contract("_ -> new")
	private @NotNull Path getRegionPath(@NotNull String dir) {
		return access.getFolder(dir).resolve(fileName);
	}

	@Contract("_ -> new")
	private @NotNull RegionFile createRegionFile(@NotNull Path regionFilePath) {
		return new RegionFile(regionFilePath,
						volatileRegionHeader,
						chunkHeader,
						"I am John RegionFile; I understand that providing my own buffer may be unsafe.");
	}

	@Override
	public boolean read() throws IOException {
		// We don't read POI data because it generally only updates with a world or entity change.
		// It's effectively a redundant disk operation.
		Path blockDataFile = getRegionPath(SUBDIR_BLOCK_DATA);
		boolean hasBlockData = Files.isRegularFile(blockDataFile);
		if (hasBlockData) {
			// If file exists, read block data.
			try (RegionFile regionFileBlockData = createRegionFile(blockDataFile)) {
				// Read world data.
				regionFileBlockData.open(AccessMode.READ);
				regionFileBlockData.readHeader();
				regionFileBlockData.close();

				// Clobber all our existing data with the new data.
				storeCurrentHeader();
				snapshotHeader(readBlockOffsets, readBlockTimes);
			} catch (IOException e) {
				return acceptOrRethrow(e);
			} catch (DataFormatException e) {
				throw new IOException(e);
			}
		} else {
			// If block data does not exist, entity data should be deleted too.
			// Wipe header in case this is a re-read.
			wipeStoredHeader();
			Arrays.fill(readBlockOffsets, 0);
			Arrays.fill(readBlockTimes, 0);
		}

		Path entityDataFile = getRegionPath(SUBDIR_ENTITY_DATA);
		if (!Files.isRegularFile(entityDataFile)) {
			// If entity data doesn't exist, our data is fully updated.
			Arrays.fill(readEntityOffsets, 0);
			Arrays.fill(readEntityTimes, 0);
			return true;
		}
		try (RegionFile regionFileEntityData = createRegionFile(entityDataFile)) {
			// Read entity data.
			regionFileEntityData.open(AccessMode.READ);
			regionFileEntityData.readHeader();
			regionFileEntityData.close();

			if (hasBlockData) {
				// Use the more recent of block data and entity data modification times.
				storeMoreRecentTimes();
			}
			snapshotHeader(readEntityOffsets, readEntityTimes);
		} catch (IOException e) {
			return acceptOrRethrow(e);
		} catch (DataFormatException e) {
			throw new IOException(e);
		}
		return true;
	}

	private void storeCurrentHeader() {
		storedRegionHeader.rewind();
		volatileRegionHeader.rewind();
		storedRegionHeader.put(volatileRegionHeader);
	}

	private void wipeStoredHeader() {
		for (int index = 0; index < RegionFile.REGION_HEADER_LENGTH; ++index) {
			storedRegionHeader.put(index, (byte) 0);
		}
	}

	private void storeMoreRecentTimes() {
		for (int i = 0; i < CHUNK_COUNT; ++i) {
			int blockDataTime = storedChunkTimes.get(i);
			int entityDataTime = volatileChunkTimes.get(i);
			if (entityDataTime > blockDataTime) {
				storedChunkTimes.put(i, entityDataTime);
			}
		}
	}

	private void snapshotHeader(int @NotNull [] offsets, int @NotNull [] times) {
		volatileChunkUsage.get(0, offsets);
		volatileChunkTimes.get(0, times);
	}

	@Override
	public boolean isInUseByServer() throws IOException {
		return access.isRegionInUse(regionX, regionZ);
	}

	@Override
	public boolean write() throws IOException {
		try {
			return access.runExclusive(regionX, regionZ, this::writeExclusive);
		} finally {
			Arrays.fill(pointerWipes, false);
		}
	}

	/**
	 * Write changes. Must only be called while the server cannot access the region's files.
	 *
	 * @return true if the region was written
	 * @throws IOException if an I/O error occurs
	 */
	private boolean writeExclusive() throws IOException {
		// Leave chunks the server still has in memory or queued saves for. Their data would be
		// written back after deletion, possibly for some data types but not others.
		for (int i = 0; i < CHUNK_COUNT; ++i) {
			if (!pointerWipes[i]) {
				continue;
			}
			int chunkX = getChunkX(i);
			int chunkZ = getChunkZ(i);
			if (access.isChunkBusy(chunkX, chunkZ)) {
				pointerWipes[i] = false;
				getPlugin().debug(DebugLevel.HIGH, () -> String.format("Keeping chunk %s_%s_%s - in use by server.",
						getWorld().getName(), chunkX, chunkZ));
			}
		}

		// Headers were read without excluding the server; leave chunks it has saved since.
		keepChangedChunks(SUBDIR_BLOCK_DATA, readBlockOffsets, readBlockTimes);
		keepChangedChunks(SUBDIR_ENTITY_DATA, readEntityOffsets, readEntityTimes);

		// May just remove this and later change method signature - is either true or an exception is thrown
		boolean failed = false;

		for (String dir : DATA_SUBDIRS) {
			failed |= !write(dir);
		}

		return !failed;
	}

	private void keepChangedChunks(@NotNull String subdirectory, int @NotNull [] readOffsets, int @NotNull [] readTimes)
			throws IOException {
		Path mcaFilePath = getRegionPath(subdirectory);
		if (Files.isRegularFile(mcaFilePath)) {
			try (RegionFile regionFile = createRegionFile(mcaFilePath)) {
				regionFile.open(AccessMode.READ);
				regionFile.readHeader();
			} catch (DataFormatException e) {
				throw new IOException(e);
			}
		} else {
			for (int index = 0; index < RegionFile.REGION_HEADER_LENGTH; ++index) {
				volatileRegionHeader.put(index, (byte) 0);
			}
		}

		for (int i = 0; i < CHUNK_COUNT; ++i) {
			if (pointerWipes[i] && (volatileChunkUsage.get(i) != readOffsets[i] || volatileChunkTimes.get(i) != readTimes[i])) {
				pointerWipes[i] = false;
				int chunkX = getChunkX(i);
				int chunkZ = getChunkZ(i);
				getPlugin().debug(DebugLevel.HIGH, () -> String.format("Keeping chunk %s_%s_%s - %s data changed since check.",
						getWorld().getName(), chunkX, chunkZ, subdirectory));
			}
		}
	}

	private int getChunkX(int index) {
		return getLowestChunkX() + RegionFile.unpackLocalX(index);
	}

	private int getChunkZ(int index) {
		return getLowestChunkZ() + RegionFile.unpackLocalZ(index);
	}

	private boolean write(@NotNull String subdirectory) throws IOException {
		Path mcaFilePath = getRegionPath(subdirectory);
		if (!Files.isRegularFile(mcaFilePath)) {
			getPlugin().debug(DebugLevel.HIGH, () -> String.format("Skipped nonexistent region %s/%s", subdirectory, getIdentifier()));
			// Return true even if file already did not exist; end goal was still accomplished
			return true;
		}

		try (RegionFile regionFile = createRegionFile(mcaFilePath)) {
			regionFile.open(AccessMode.WRITE_DSYNC);
			regionFile.readHeader();
			boolean headerEmpty = true;
			boolean modified = false;
			for (int i = 0; i < pointerWipes.length; ++i) {
				if (pointerWipes[i]) {
					modified |= regionFile.isPresent(i);
					regionFile.deleteChunk(i);
				} else if (headerEmpty && regionFile.isPresent(i)) {
					headerEmpty = false;
					if (getPlugin().debug(DebugLevel.HIGH)) {
						int chunkX = getChunkX(i);
						int chunkZ = getChunkZ(i);
						getPlugin().getLogger().info(() ->
								String.format(
										"Rewriting header of region %s/%s due to non-zero index of chunk %s_%s_%s",
										subdirectory,
										getIdentifier(),
										getWorld().getName(),
										chunkX,
										chunkZ));
					}
				}
			}
			if (!headerEmpty) {
				if (modified) {
					regionFile.writeHeader();
				}
				regionFile.close();

				// Since the region still has content, update our stored data with the current data.
				if (subdirectory.equals(SUBDIR_BLOCK_DATA)) {
					storeCurrentHeader();
				} else if (subdirectory.equals(SUBDIR_ENTITY_DATA)) {
					storeMoreRecentTimes();
				}

				return true;
			}
		} catch (IOException e) {
			return acceptOrRethrow(e);
		} catch (DataFormatException e) {
			throw new IOException(e);
		}

		try {
			// Header contains no content, delete data.
			Files.deleteIfExists(mcaFilePath);

			// Also delete oversized chunks belonging to this region.
			Path folder = access.getFolder(subdirectory);
			for (int dX = 0; dX < CHUNKS_PER_AXIS; ++dX) {
				for (int dZ = 0; dZ < CHUNKS_PER_AXIS; ++dZ) {
					String xlChunkData = String.format("c.%s.%s.mcc", getLowestChunkX() + dX, getLowestChunkZ() + dZ);
					Files.deleteIfExists(folder.resolve(xlChunkData));
				}
			}
		} catch (IOException e) {
			acceptOrRethrow(e);
		}

		if (subdirectory.equals(SUBDIR_BLOCK_DATA)) {
			wipeStoredHeader();
		}

		getPlugin().debug(DebugLevel.HIGH, () -> String.format("Deleted region %s/%s with empty header", subdirectory, getIdentifier()));
		return true;
	}

	@Override
	public @NotNull AnvilWorld getWorldInfo() {
		return (AnvilWorld) super.getWorldInfo();
	}

	@Override
	public boolean exists() {
		for (String dir : DATA_SUBDIRS) {
			if (Files.exists(getRegionPath(dir))) {
				return true;
			}
		}
		return false;
	}

	@Override
	public @NotNull ChunkInfo getLocalChunk(int localChunkX, int localChunkZ) {
		Preconditions.checkArgument(localChunkX >= 0 && localChunkX < 32 && localChunkZ >= 0 && localChunkZ < 32,
				"Local chunk coords must be within range 0-31! Received values X: %s, Z: %s", localChunkX, localChunkZ);
		return new AnvilChunk(localChunkX, localChunkZ);
	}

	@Override
	public @NotNull Stream<ChunkInfo> getChunks() {
		return IntStream.range(0, CHUNK_COUNT).mapToObj(index -> {
			int localChunkX = RegionFile.unpackLocalX(index);
			int localChunkZ = RegionFile.unpackLocalZ(index);
			return getLocalChunk(localChunkX, localChunkZ);
		});
	}

	@Override
	public int getChunksPerRegion() {
		return CHUNK_COUNT;
	}

	private class AnvilChunk extends ChunkInfo {
		private AnvilChunk(int localChunkX, int localChunkZ) {
			super(AnvilRegion.this, localChunkX, localChunkZ);
			Preconditions.checkArgument(localChunkX >= 0 && localChunkX < 32, "localChunkX must be between 0 and 31");
			Preconditions.checkArgument(localChunkZ >= 0 && localChunkZ < 32, "localChunkZ must be between 0 and 31");
		}

		@Override
		public boolean isOrphaned() {
			int index = RegionFile.packIndex(getLocalChunkX(), getLocalChunkZ());
			// Return true if chunk is slated to be orphaned on region write or already orphaned.
			return pointerWipes[index] || storedChunkUsage.get(index) == RegionFile.CHUNK_NOT_PRESENT;
		}

		@Override
		public void setOrphaned() {
			pointerWipes[RegionFile.packIndex(getLocalChunkX(), getLocalChunkZ())] = true;
		}

		@Override
		public long getLastModified() {
			return TimeUnit.MILLISECONDS.convert(storedChunkTimes.get(RegionFile.packIndex(getLocalChunkX(), getLocalChunkZ())), TimeUnit.SECONDS);
		}
	}
}
