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

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Coordinates Regionerator's edits to region files with the server's own region file I/O.
 *
 * <p>The server keeps region files open in a cache, holds its own copy of each open file's header in
 * memory, and rewrites the whole header every time it saves a chunk. If Regionerator edits a file the
 * server has open, one side ends up writing a stale header. Stale headers can point a chunk at sectors
 * that now hold another chunk's data, or bring deleted chunks back.
 */
abstract class RegionFileAccess {

	/**
	 * Gets the folder containing region files for a data subdirectory.
	 *
	 * @param subdirectory the data subdirectory, one of {@link AnvilRegion#DATA_SUBDIRS}
	 * @return the folder
	 */
	abstract @NotNull Path getFolder(@NotNull String subdirectory);

	/**
	 * Gets whether region files can be modified at all.
	 *
	 * @return true if {@link #runExclusive(int, int, ExclusiveAction)} may run actions
	 */
	abstract boolean canModify();

	/**
	 * Gets whether {@link #isRegionInUse(int, int)} reflects the server's state.
	 *
	 * @return true if region use can be detected
	 */
	abstract boolean canDetectUse();

	/**
	 * Gets whether the server currently has any of a region's files open.
	 *
	 * @param regionX the region X coordinate
	 * @param regionZ the region Z coordinate
	 * @return true if the server has the region open
	 * @throws IOException if the server's state cannot be checked
	 */
	abstract boolean isRegionInUse(int regionX, int regionZ) throws IOException;

	/**
	 * Runs an action while the server cannot open, read or write any of a region's files.
	 *
	 * <p>If the server already has one of the region's files open, the action is not run.
	 *
	 * @param regionX the region X coordinate
	 * @param regionZ the region Z coordinate
	 * @param action the action to run
	 * @return false if the action was not run, otherwise the result of the action
	 * @throws IOException if the action throws or the server's state cannot be checked
	 */
	abstract boolean runExclusive(int regionX, int regionZ, @NotNull ExclusiveAction action) throws IOException;

	/**
	 * Gets whether the server has a chunk in memory at any stage of loading or unloading, or has I/O
	 * queued for it. Data for such a chunk may be written back after it is deleted.
	 *
	 * <p>Only meaningful from within {@link #runExclusive(int, int, ExclusiveAction)}.
	 *
	 * @param chunkX the chunk X coordinate
	 * @param chunkZ the chunk Z coordinate
	 * @return true if the chunk must not be deleted
	 * @throws IOException if the server's state cannot be checked
	 */
	abstract boolean isChunkBusy(int chunkX, int chunkZ) throws IOException;

	/**
	 * An action run with exclusive access to a region's files.
	 */
	@FunctionalInterface
	interface ExclusiveAction {

		/**
		 * Runs the action.
		 *
		 * @return true if the action completed
		 * @throws IOException if an I/O error occurs
		 */
		boolean run() throws IOException;

	}

}
