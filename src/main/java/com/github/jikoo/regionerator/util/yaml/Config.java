/*
 * Copyright (c) 2015-2021 by Jikoo.
 *
 * Regionerator is licensed under a Creative Commons
 * Attribution-ShareAlike 4.0 International License.
 *
 * You should have received a copy of the license along with this
 * work. If not, see <http://creativecommons.org/licenses/by-sa/4.0/>.
 */

package com.github.jikoo.regionerator.util.yaml;

import com.github.jikoo.regionerator.DebugLevel;
import com.google.common.collect.ImmutableMap;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class Config extends ConfigYamlData {

	/** Constant representing the default flag timestamp. */
	public static final long FLAG_DEFAULT = -1;
	/** Constant representing a flag that will never expire. */
	public static final long FLAG_ETERNAL = Long.MAX_VALUE - 1;
	/** Constant representing a failure to load data. */
	public static final long FLAG_OH_NO = Long.MAX_VALUE - 2;
	/** Constant representing a chunk that has been generated but not visited. Replaced by the first visit. */
	public static final long FLAG_GENERATED = Long.MAX_VALUE;

	private final Object lock = new Object();
	private DebugLevel debugLevel;
	private Map<String, Long> worlds;
	private final AtomicLong ticksPerFlag = new AtomicLong();
	private final AtomicLong millisBetweenCycles = new AtomicLong();
	private final AtomicLong deletionRecovery = new AtomicLong();
	private final AtomicInteger flaggingRadius = new AtomicInteger();
	private final AtomicInteger deletionChunkCount = new AtomicInteger();
	private final AtomicBoolean rememberCycleDelay = new AtomicBoolean();
	private final AtomicBoolean deleteFreshChunks = new AtomicBoolean();
	private final AtomicBoolean deleteWholeRegionsOnly = new AtomicBoolean();
	private final AtomicInteger minResetArea = new AtomicInteger();
	private final AtomicBoolean allowUnsafeRegionAccess = new AtomicBoolean();
	private final AtomicBoolean pruneAfterCycle = new AtomicBoolean();
	private final AtomicLong deletionRecordRetention = new AtomicLong();
	private long cacheExpirationFrequency;
	private long cacheRetention;
	private int cacheBatchMax;
	private long cacheBatchDelay;
	private int cacheMaxSize;

	public Config(@NotNull Plugin plugin) {
		super(plugin);
		reload();
	}

	@Override
	public void reload() {
		super.reload();

		ConfigUpdater.doUpdates(this);

		reconsiderWorldValidity();

		synchronized (lock) {
			debugLevel = DebugLevel.of(getString("debug-level"));
		}

		deleteFreshChunks.set(!getBoolean("flagging.flag-generated-chunks-until-visited"));
		flaggingRadius.set(Math.max(0, getInt("flagging.chunk-flag-radius")));

		int secondsPerFlag = getInt("flagging.seconds-per-flag");
		if (secondsPerFlag < 1) {
			ticksPerFlag.set(10);
		} else {
			ticksPerFlag.set(20L * secondsPerFlag);
		}

		deletionRecovery.set(Math.max(0, readRecoveryTime()));
		deletionChunkCount.set(Math.max(1, getInt("deletion.expensive-checks-between-recovery")));
		millisBetweenCycles.set(TimeUnit.HOURS.toMillis(Math.max(0, getInt("deletion.hours-between-cycles"))));
		rememberCycleDelay.set(getBoolean("deletion.remember-next-cycle-time"));
		deleteWholeRegionsOnly.set(getBoolean("deletion.whole-regions-only"));
		minResetArea.set(Math.max(0, Math.min(32, getInt("deletion.min-reset-area"))));
		allowUnsafeRegionAccess.set(getBoolean("deletion.allow-unsafe-region-access"));
		pruneAfterCycle.set(getBoolean("database.prune-after-cycle"));
		deletionRecordRetention.set(TimeUnit.DAYS.toMillis(Math.max(0, getInt("database.keep-deletion-records-days"))));

		cacheExpirationFrequency = TimeUnit.MILLISECONDS.convert(Math.max(0, getInt("cache.minimum-expiration-frequency")), TimeUnit.SECONDS);
		cacheRetention = TimeUnit.MILLISECONDS.convert(Math.max(1, getInt("cache.retention")), TimeUnit.MINUTES);
		cacheBatchMax = Math.max(1, getInt("cache.maximum-batch-size"));
		cacheBatchDelay = Math.max(0L, getLong("cache.batch-delay"));
		cacheMaxSize = Math.max(50_000, getInt("cache.max-cache-size"));

	}

	private long readRecoveryTime() {
		// Configs generated before 3.0.0 shipped the key as deletion-recovery-time, which was never read.
		// Prefer the real key, but honour the legacy one so those configs get the delay they ask for.
		if (raw().isSet("deletion.recovery-time")) {
			return getLong("deletion.recovery-time");
		}
		if (raw().isSet("deletion.deletion-recovery-time")) {
			return getLong("deletion.deletion-recovery-time");
		}
		// Fall through to the bundled default.
		return getLong("deletion.recovery-time");
	}

	public void reconsiderWorldValidity() {
		ConfigurationSection worldsSection = raw().getConfigurationSection("worlds");
		Map<String, Long> worldFlagDurations = new HashMap<>();
		if (worldsSection != null) {

			List<String> activeWorlds = Bukkit.getWorlds().stream().map(World::getName).toList();

			for (String key : worldsSection.getKeys(false)) {
				String validCase;
				// Attempt to correct case for quick getting later.
				if (activeWorlds.contains(key)) {
					validCase = key;
				} else {
					// World may still be active, attempt to correct case.
					Optional<String> match = activeWorlds.stream().filter(key::equalsIgnoreCase).findFirst();
					validCase = match.orElse(key);
				}

				ConfigurationSection worldSection = worldsSection.getConfigurationSection(key);

				if (worldSection == null) {
					continue;
				}

				int days = worldSection.getInt("days-till-flag-expires", worldsSection.getInt("default.days-till-flag-expires", -1));
				worldFlagDurations.put(validCase, days < 0 ? -1 : TimeUnit.MILLISECONDS.convert(days, TimeUnit.DAYS));
			}
		}

		synchronized (lock) {
			// Immutable, this should not be changed during run.
			this.worlds = ImmutableMap.copyOf(worldFlagDurations);
		}
	}

	public DebugLevel getDebugLevel() {
		synchronized (lock) {
			return debugLevel;
		}
	}

	public int getDeletionChunkCount() {
		return deletionChunkCount.get();
	}

	public long getDeletionRecoveryMillis() {
		return deletionRecovery.get();
	}

	public long getCycleDelayMillis() {
		return millisBetweenCycles.get();
	}

	public boolean isRememberCycleDelay() {
		return rememberCycleDelay.get();
	}

	public boolean isDeleteWholeRegionsOnly() {
		return deleteWholeRegionsOnly.get();
	}

	/**
	 * Gets the side length, in chunks, of the square of deletable chunks a chunk must be part of to be deleted on its
	 * own. 1 or less deletes chunks individually.
	 *
	 * @return the minimum reset area's side length in chunks
	 */
	public int getMinResetArea() {
		return minResetArea.get();
	}

	public boolean isUnsafeRegionAccessAllowed() {
		return allowUnsafeRegionAccess.get();
	}

	public boolean isPruneAfterCycle() {
		return pruneAfterCycle.get();
	}

	public long getDeletionRecordRetentionMillis() {
		return deletionRecordRetention.get();
	}

	public boolean isDeleteFreshChunks(@NotNull World world) {
		return isDeleteFreshChunks(world.getName());
	}

	public boolean isDeleteFreshChunks(@NotNull String worldName) {
		return deleteFreshChunks.get() && getFlagDuration(worldName) > 0;
	}

	public long getFlagDuration(@NotNull World world) {
		return getFlagDuration(world.getName());
	}

	public long getFlagDuration(String worldName) {
		synchronized (lock) {
			return worlds.getOrDefault(worldName, worlds.getOrDefault("default", -1L));
		}
	}

	public long getFlagGenerated(@NotNull World world) {
		return getFlagGenerated(world.getName());
	}

	public long getFlagGenerated(@NotNull String worldName) {
		return isDeleteFreshChunks(worldName) ? getFlagVisit(worldName) : FLAG_GENERATED;
	}

	public long getFlagVisit(@NotNull World world) {
		return getFlagVisit(world.getName());
	}

	public long getFlagVisit(String worldName) {
		return System.currentTimeMillis() + getFlagDuration(worldName);
	}

	public long getFlaggingInterval() {
		return ticksPerFlag.get();
	}

	public int getFlaggingRadius() {
		return flaggingRadius.get();
	}

	public boolean isEnabled(String worldName) {
		return getFlagDuration(worldName) >= 0;
	}

	public @NotNull @Unmodifiable Collection<String> enabledWorlds() {
		return plugin.getServer().getWorlds().stream().map(World::getName).filter(this::isEnabled).collect(Collectors.toUnmodifiableSet());
	}

	public long getCacheExpirationFrequency() {
		return cacheExpirationFrequency;
	}

	public long getCacheRetention() {
		return cacheRetention;
	}

	public int getCacheBatchMax() {
		return cacheBatchMax;
	}

	public long getCacheBatchDelay() {
		return cacheBatchDelay;
	}

	public int getCacheMaxSize() {
		return cacheMaxSize;
	}

	public boolean startPaused() {
		return getBoolean("deletion.start-paused");
	}

	public boolean rescueEnabled() {
		return getBoolean("safe-login.enabled");
	}

	public boolean rescueIfSafe() {
		return getBoolean("safe-login.rescue-if-safe");
	}

	public boolean rescueToTopBlock() {
		return getBoolean("safe-login.try-top-block");
	}

	public boolean rescueToRespawn() {
		return getBoolean("safe-login.try-respawn");
	}

	public @NotNull World getRescueWorld(@NotNull World current) {
		String worldName = getString("safe-login.world-override");
		if (worldName == null) {
			return current;
		}
		World world = Bukkit.getWorld(worldName);
		if (world == null) {
			return current;
		}
		return world;
	}

}
