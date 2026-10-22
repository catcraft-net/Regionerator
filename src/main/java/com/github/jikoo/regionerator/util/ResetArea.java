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

import org.jetbrains.annotations.Nullable;

import java.util.BitSet;

/**
 * The chunks in and around a region that count as empty, and which of the region's own chunks lie inside a square of
 * empty chunks at least {@code size} chunks wide.
 *
 * <p>Coordinates are relative to the region's lowest chunk. A square may reach up to {@code size - 1} chunks into
 * neighbouring regions, so a large empty area crossing a region border counts as one area.
 */
public final class ResetArea {

	private final int width;
	private final int size;
	private final int margin;
	private final int extent;
	private final BitSet empty;
	private @Nullable BitSet covered;

	/**
	 * Constructs a new {@code ResetArea} with no empty chunks.
	 *
	 * @param width the number of chunks along each side of the region
	 * @param size the number of chunks along each side of a square
	 */
	public ResetArea(int width, int size) {
		this.width = width;
		this.size = Math.max(1, size);
		this.margin = this.size - 1;
		this.extent = width + 2 * margin;
		this.empty = new BitSet(extent * extent);
	}

	/**
	 * Gets how many chunks a square may reach into a neighbouring region.
	 *
	 * @return the margin in chunks
	 */
	public int getMargin() {
		return margin;
	}

	/**
	 * Sets whether a chunk counts as empty.
	 *
	 * @param x the chunk X coordinate relative to the region, from {@code -margin} to {@code width + margin - 1}
	 * @param z the chunk Z coordinate relative to the region, from {@code -margin} to {@code width + margin - 1}
	 * @param value true if the chunk counts as empty
	 */
	public void setEmpty(int x, int z, boolean value) {
		empty.set(index(x, z), value);
		covered = null;
	}

	/**
	 * Gets whether a chunk lies inside a square of empty chunks at least {@code size} chunks wide.
	 *
	 * @param x the chunk X coordinate relative to the region
	 * @param z the chunk Z coordinate relative to the region
	 * @return true if the chunk is covered by a large enough empty square
	 */
	public boolean isCovered(int x, int z) {
		if (covered == null) {
			covered = ResetAreas.coveredBySquares(empty, extent, size);
		}
		return covered.get(index(x, z));
	}

	private int index(int x, int z) {
		if (x < -margin || x >= width + margin || z < -margin || z >= width + margin) {
			throw new IndexOutOfBoundsException("Chunk " + x + ", " + z + " is outside the area");
		}
		return (z + margin) * extent + x + margin;
	}

}
