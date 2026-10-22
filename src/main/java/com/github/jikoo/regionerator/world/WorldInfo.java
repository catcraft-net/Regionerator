/*
 * Copyright (c) 2015-2021 by Jikoo.
 *
 * Regionerator is licensed under a Creative Commons
 * Attribution-ShareAlike 4.0 International License.
 *
 * You should have received a copy of the license along with this
 * work. If not, see <http://creativecommons.org/licenses/by-sa/4.0/>.
 */

package com.github.jikoo.regionerator.world;

import com.github.jikoo.regionerator.Regionerator;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

/**
 * A container used to generate {@link RegionInfo} for a {@link World}.
 */
public abstract class WorldInfo {

	private final @NotNull Regionerator plugin;
	private final @NotNull World world;
	protected final @NotNull Map<String, RegionInfo> regions;

	public WorldInfo(@NotNull Regionerator plugin, @NotNull World world) {
		this.plugin = plugin;
		this.world = world;
		regions = new ConcurrentHashMap<>();
	}

	/**
	 * Gets the {@link World} the WorldInfo represents.
	 *
	 * @return the Bukkit world
	 */
	public @NotNull World getWorld() {
		return world;
	}

	/**
	 * Gets whether region data in the world can be modified safely.
	 *
	 * @return true if regions may be deleted
	 */
	public boolean canModifyRegions() {
		return true;
	}

	/**
	 * Gets whether {@link #isRegionInUse(int, int)} reflects the server's state. If not, regions must be assumed to
	 * be in use.
	 *
	 * @return true if region use can be detected
	 */
	public boolean canDetectRegionUse() {
		return false;
	}

	/**
	 * Gets whether the server currently has any of a region's files open.
	 *
	 * @param regionX the region X coordinate
	 * @param regionZ the region Z coordinate
	 * @return true if the server is using the region
	 * @throws IOException if the server's state cannot be checked
	 * @see #canDetectRegionUse()
	 */
	public boolean isRegionInUse(int regionX, int regionZ) throws IOException {
		return false;
	}

	/**
	 * Gets {@link RegionInfo} for the specified region coordinates. Note that the region may not exist - check {@link RegionInfo#exists()}.
	 *
	 * @param regionX the region's X coordinate
	 * @param regionZ the region's Z coordinate
	 * @return the {@link RegionInfo}
	 */
	public abstract @NotNull RegionInfo getRegion(int regionX, int regionZ);

	/**
	 * Gets a {@link Stream<RegionInfo>} requesting every {@link RegionInfo} contained by the WorldInfo.
	 *
	 * @return a {@link Stream<RegionInfo>}
	 */
	public abstract @NotNull Stream<RegionInfo> getRegions();

	/**
	 * Gets the instance of Regionerator loading the WorldInfo.
	 *
	 * @return the Regionerator instance
	 */
	protected @NotNull Regionerator getPlugin() {
		return plugin;
	}

}
