/*
 * Copyright (c) 2015-2026 by Jikoo.
 *
 * Regionerator is licensed under a Creative Commons
 * Attribution-ShareAlike 4.0 International License.
 *
 * You should have received a copy of the license along with this
 * work. If not, see <http://creativecommons.org/licenses/by-sa/4.0/>.
 */

package com.github.jikoo.regionerator.util;

import org.jetbrains.annotations.NotNull;

import java.util.BitSet;

/**
 * Finds the chunks of a region that lie inside large deletable areas.
 *
 * <p>Deleting a lone chunk next to chunks that are kept leaves a sharp border between old and newly generated
 * terrain right beside the area players use. Only deleting chunks that are part of a large square of deletable chunks
 * moves those borders away from the area players use.
 */
public final class ResetAreas {

	private ResetAreas() {}

	/**
	 * Gets the deletable chunks covered by at least one square of the given size in which every chunk is deletable.
	 *
	 * <p>Chunks are indexed by {@code localZ * width + localX}. Only squares lying entirely inside the region are
	 * considered.
	 *
	 * @param deletable the chunks that may be deleted
	 * @param width the number of chunks along each side of the region
	 * @param size the number of chunks along each side of the square; 1 or less keeps every deletable chunk
	 * @return the deletable chunks that are part of a large enough square
	 */
	public static @NotNull BitSet coveredBySquares(@NotNull BitSet deletable, int width, int size) {
		if (size <= 1) {
			return (BitSet) deletable.clone();
		}

		BitSet covered = new BitSet(width * width);
		if (size > width) {
			return covered;
		}

		// sums[z][x] is the number of deletable chunks with localX < x and localZ < z.
		int[][] sums = new int[width + 1][width + 1];
		for (int z = 0; z < width; ++z) {
			for (int x = 0; x < width; ++x) {
				int value = deletable.get(z * width + x) ? 1 : 0;
				sums[z + 1][x + 1] = sums[z][x + 1] + sums[z + 1][x] - sums[z][x] + value;
			}
		}

		int squareCount = size * size;
		for (int z = 0; z + size <= width; ++z) {
			for (int x = 0; x + size <= width; ++x) {
				int count = sums[z + size][x + size] - sums[z][x + size] - sums[z + size][x] + sums[z][x];
				if (count != squareCount) {
					continue;
				}
				for (int dZ = 0; dZ < size; ++dZ) {
					int start = (z + dZ) * width + x;
					covered.set(start, start + size);
				}
			}
		}

		return covered;
	}

}
