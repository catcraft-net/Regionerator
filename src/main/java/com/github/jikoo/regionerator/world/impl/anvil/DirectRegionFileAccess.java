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
 * Region file access without any coordination with the server.
 *
 * <p>Used where the server's region file I/O cannot be coordinated with. Modification is only
 * permitted if the user explicitly accepts the risk; otherwise this only allows reading.
 */
final class DirectRegionFileAccess extends RegionFileAccess {

	private final @NotNull Path dataFolder;
	private final boolean allowModification;

	DirectRegionFileAccess(@NotNull Path dataFolder, boolean allowModification) {
		this.dataFolder = dataFolder;
		this.allowModification = allowModification;
	}

	@Override
	@NotNull Path getFolder(@NotNull String subdirectory) {
		return dataFolder.resolve(subdirectory);
	}

	@Override
	boolean canModify() {
		return allowModification;
	}

	@Override
	boolean canDetectUse() {
		return false;
	}

	@Override
	boolean isRegionInUse(int regionX, int regionZ) {
		return false;
	}

	@Override
	boolean runExclusive(int regionX, int regionZ, @NotNull ExclusiveAction action) throws IOException {
		return allowModification && action.run();
	}

	@Override
	boolean isChunkBusy(int chunkX, int chunkZ) {
		return false;
	}

}
