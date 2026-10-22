/*
 * Copyright (c) 2015-2026 by Jikoo.
 *
 * Regionerator is licensed under a Creative Commons
 * Attribution-ShareAlike 4.0 International License.
 *
 * You should have received a copy of the license along with this
 * work. If not, see <http://creativecommons.org/licenses/by-sa/4.0/>.
 */

package com.github.jikoo.regionerator;

import com.github.jikoo.regionerator.database.FlagFilter;
import com.github.jikoo.regionerator.database.Pacer;
import com.github.jikoo.regionerator.util.yaml.Config;
import com.github.jikoo.regionerator.world.RegionInfo;
import com.github.jikoo.regionerator.world.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decides which stored flags no longer have any effect.
 *
 * <ul>
 *   <li>A visit flag that has expired is treated exactly like no flag at all.</li>
 *   <li>A flag for a chunk that does not exist protects nothing. If the chunk is generated later, generation sets a
 *   new flag, which replaces any existing one.</li>
 * </ul>
 *
 * <p>Permanent flags are always kept. Chunks are only treated as missing in worlds Regionerator is enabled in, where
 * the server's use of regions can be detected. Region headers are first read without coordinating with the server
 * to find candidates cheaply. Each candidate is then confirmed while the server cannot open the region: the chunk
 * must still be absent from the region's files and the server must not have it in memory or queued for saving.
 *
 * <p>The pacer pauses between regions, so checking many regions in a row does not hold up the server's own region
 * file use. Once the pacer says to stop, no more regions are checked and their flags are kept.
 */
final class FlagPruner implements FlagFilter {

	private static final int CHUNKS_PER_AXIS = 32;

	private final @NotNull Map<String, WorldInfo> worlds;
	private final @NotNull Predicate<String> held;
	private final @NotNull Pacer pacer;
	private final @NotNull Logger logger;
	private final long now;
	/**
	 * Chunks found in each region, by world and region, kept for the whole run so each region is only read once. An
	 * outdated list is safe: it only picks candidates, and every candidate is confirmed before removal.
	 */
	private final @NotNull Map<String, Map<Long, BitSet>> presence = new HashMap<>();
	private final @NotNull Map<String, ChunkCoordinate> missingCandidates = new HashMap<>();
	private long expiredFlags;
	private long missingChunkFlags;
	private boolean regionChecked;
	private boolean stopped;
	private boolean warned;

	/**
	 * Constructs a new {@code FlagPruner}.
	 *
	 * @param worlds the worlds whose chunks may be checked for existence, by name
	 * @param held whether a flag is currently held in memory
	 * @param pacer pauses between regions and decides when to stop
	 * @param logger the logger for problems reading regions
	 */
	FlagPruner(
			@NotNull Map<String, WorldInfo> worlds,
			@NotNull Predicate<String> held,
			@NotNull Pacer pacer,
			@NotNull Logger logger) {
		this.worlds = worlds;
		this.held = held;
		this.pacer = pacer;
		this.logger = logger;
		this.now = System.currentTimeMillis();
	}

	@Override
	public boolean canRemove(@NotNull String chunkId, long time) {
		if (time == Config.FLAG_ETERNAL) {
			return false;
		}

		if (isTimestamp(time) && time < now) {
			return true;
		}

		ChunkCoordinate chunk = parse(chunkId);
		if (chunk == null) {
			return false;
		}

		WorldInfo world = worlds.get(chunk.world());
		if (world == null || !world.canDetectRegionUse()) {
			return false;
		}

		if (isChunkMissing(chunk.world(), world, chunk.x(), chunk.z())) {
			missingCandidates.put(chunkId, chunk);
			return true;
		}
		return false;
	}

	@Override
	public @NotNull Collection<String> confirm(@NotNull Collection<String> chunkIds) {
		List<String> confirmed = new ArrayList<>(chunkIds.size());
		Map<RegionKey, Map<String, ChunkCoordinate>> regions = new HashMap<>();
		for (String chunkId : chunkIds) {
			ChunkCoordinate chunk = missingCandidates.remove(chunkId);
			if (chunk == null) {
				// Expired; nothing to confirm.
				++expiredFlags;
				confirmed.add(chunkId);
			} else {
				regions.computeIfAbsent(new RegionKey(chunk.world(), chunk.x() >> 5, chunk.z() >> 5), key -> new HashMap<>())
						.put(chunkId, chunk);
			}
		}
		missingCandidates.clear();

		for (Map.Entry<RegionKey, Map<String, ChunkCoordinate>> entry : regions.entrySet()) {
			if (!recoverBetweenRegions()) {
				break;
			}
			RegionKey key = entry.getKey();
			BitSet candidates = new BitSet(CHUNKS_PER_AXIS * CHUNKS_PER_AXIS);
			entry.getValue().values().forEach(chunk -> candidates.set(getIndex(chunk.x(), chunk.z())));

			BitSet absent;
			try {
				absent = worlds.get(key.world()).getRegion(key.x(), key.z()).getAbsentChunks(candidates);
			} catch (IOException e) {
				warn(e);
				continue;
			}

			for (Map.Entry<String, ChunkCoordinate> chunk : entry.getValue().entrySet()) {
				if (absent.get(getIndex(chunk.getValue().x(), chunk.getValue().z()))) {
					++missingChunkFlags;
					confirmed.add(chunk.getKey());
				}
			}
		}

		return confirmed;
	}

	@Override
	public boolean isHeld(@NotNull String chunkId) {
		return held.test(chunkId);
	}

	/**
	 * Gets the number of expired visit flags this pruner confirmed for removal.
	 *
	 * @return the number of expired flags
	 */
	long getExpiredFlags() {
		return expiredFlags;
	}

	/**
	 * Gets the number of flags for chunks that do not exist this pruner confirmed for removal.
	 *
	 * @return the number of flags for missing chunks
	 */
	long getMissingChunkFlags() {
		return missingChunkFlags;
	}

	private static boolean isTimestamp(long time) {
		return time >= 0 && time < Config.FLAG_OH_NO;
	}

	private static int getIndex(int chunkX, int chunkZ) {
		return (chunkZ & (CHUNKS_PER_AXIS - 1)) * CHUNKS_PER_AXIS + (chunkX & (CHUNKS_PER_AXIS - 1));
	}

	private boolean isChunkMissing(@NotNull String worldName, @NotNull WorldInfo world, int chunkX, int chunkZ) {
		int regionX = chunkX >> 5;
		int regionZ = chunkZ >> 5;
		try {
			BitSet chunks = getChunks(worldName, world, regionX, regionZ);
			if (chunks == null || chunks.get(getIndex(chunkX, chunkZ))) {
				return false;
			}
			// Checked again: the server may have opened the region since its chunks were listed.
			return !world.isRegionInUse(regionX, regionZ);
		} catch (IOException e) {
			warn(e);
			return false;
		}
	}

	private @Nullable BitSet getChunks(@NotNull String worldName, @NotNull WorldInfo world, int regionX, int regionZ)
			throws IOException {
		Map<Long, BitSet> worldPresence = presence.computeIfAbsent(worldName, key -> new HashMap<>());
		long regionKey = (long) regionX << 32 | (regionZ & 0xFFFFFFFFL);
		if (worldPresence.containsKey(regionKey)) {
			return worldPresence.get(regionKey);
		}

		if (!recoverBetweenRegions()) {
			// Not recorded; pruning is stopping.
			return null;
		}

		BitSet chunks = null;
		// Flags in regions the server has open are kept for this run.
		if (!world.isRegionInUse(regionX, regionZ)) {
			RegionInfo region = world.getRegion(regionX, regionZ);
			if (region.read()) {
				chunks = new BitSet(CHUNKS_PER_AXIS * CHUNKS_PER_AXIS);
				for (int localZ = 0; localZ < CHUNKS_PER_AXIS; ++localZ) {
					for (int localX = 0; localX < CHUNKS_PER_AXIS; ++localX) {
						if (region.hasChunk(localX, localZ)) {
							chunks.set(getIndex(localX, localZ));
						}
					}
				}
			}
		}
		worldPresence.put(regionKey, chunks);
		return chunks;
	}

	/**
	 * Waits between regions, so reading many regions in a row does not hold up the server's own region file use.
	 *
	 * @return false if pruning must stop
	 */
	private boolean recoverBetweenRegions() {
		if (stopped || pacer.isStopping()) {
			stopped = true;
			return false;
		}
		if (regionChecked) {
			try {
				pacer.pause();
			} catch (InterruptedException e) {
				// Leave the interrupt for the batch loop, which stops on it.
				Thread.currentThread().interrupt();
				stopped = true;
				return false;
			}
			if (pacer.isStopping()) {
				stopped = true;
				return false;
			}
		}
		regionChecked = true;
		return true;
	}

	private void warn(@NotNull IOException e) {
		if (!warned) {
			warned = true;
			logger.log(Level.WARNING, "Unable to read region data while pruning flags; keeping flags for unreadable regions.", e);
		}
	}

	/**
	 * Parses a chunk identifier of the form {@code <world>_<x>_<z>}. World names may contain underscores.
	 *
	 * @param chunkId the chunk identifier
	 * @return the chunk coordinate, or {@code null} if the identifier is not in the expected form
	 */
	static @Nullable ChunkCoordinate parse(@NotNull String chunkId) {
		int zSeparator = chunkId.lastIndexOf('_');
		if (zSeparator <= 0) {
			return null;
		}
		int xSeparator = chunkId.lastIndexOf('_', zSeparator - 1);
		if (xSeparator <= 0) {
			return null;
		}
		try {
			int x = Integer.parseInt(chunkId, xSeparator + 1, zSeparator, 10);
			int z = Integer.parseInt(chunkId, zSeparator + 1, chunkId.length(), 10);
			return new ChunkCoordinate(chunkId.substring(0, xSeparator), x, z);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	record ChunkCoordinate(@NotNull String world, int x, int z) {}

	private record RegionKey(@NotNull String world, int x, int z) {}

}
