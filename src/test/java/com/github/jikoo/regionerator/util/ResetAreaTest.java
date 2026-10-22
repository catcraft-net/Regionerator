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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResetAreaTest {

  private static final int WIDTH = 32;

  @Test
  void marginIsSizeMinusOne() {
    assertEquals(7, new ResetArea(WIDTH, 8).getMargin());
    assertEquals(0, new ResetArea(WIDTH, 1).getMargin());
  }

  @Test
  void coordinatesAreXThenZ() {
    // A 1-wide, 8-tall column: covered only if x and z are not swapped somewhere.
    ResetArea area = new ResetArea(WIDTH, 1);
    area.setEmpty(3, 20, true);

    assertTrue(area.isCovered(3, 20));
    assertFalse(area.isCovered(20, 3));
  }

  @Test
  void squareInsideRegionIsCovered() {
    ResetArea area = new ResetArea(WIDTH, 8);
    fill(area, 10, 10, 8, 8);

    assertTrue(area.isCovered(10, 10));
    assertTrue(area.isCovered(17, 17));
    assertFalse(area.isCovered(18, 17));
  }

  @Test
  void squareCrossingRegionBorderCoversOwnChunks() {
    ResetArea area = new ResetArea(WIDTH, 8);
    // Own region: a 4-wide band along the east edge. Neighbour: 4 more columns past the edge.
    fill(area, 28, 0, 4, 8);
    assertFalse(area.isCovered(28, 0), "4 wide on its own is too narrow");

    fill(area, 32, 0, 4, 8);
    assertTrue(area.isCovered(28, 0));
    assertTrue(area.isCovered(31, 7));
  }

  @Test
  void squareCrossingNegativeBorderCoversOwnChunks() {
    ResetArea area = new ResetArea(WIDTH, 8);
    fill(area, 0, 0, 3, 8);
    fill(area, -5, 0, 5, 8);

    assertTrue(area.isCovered(0, 0));
    assertTrue(area.isCovered(2, 7));
  }

  @Test
  void keptChunkSplitsSquare() {
    ResetArea area = new ResetArea(WIDTH, 8);
    fill(area, 0, 0, 8, 8);
    assertTrue(area.isCovered(0, 0));

    area.setEmpty(4, 4, false);

    for (int z = 0; z < 8; ++z) {
      for (int x = 0; x < 8; ++x) {
        assertFalse(area.isCovered(x, z), "No chunk of a split 8x8 square stays covered: " + x + ", " + z);
      }
    }
  }

  @Test
  void keptChunkLeavesOtherSquaresCovered() {
    ResetArea area = new ResetArea(WIDTH, 8);
    fill(area, 0, 0, 16, 8);

    area.setEmpty(0, 0, false);

    assertFalse(area.isCovered(0, 3));
    assertTrue(area.isCovered(1, 0), "Square from x=1 to 8 is still whole");
    assertTrue(area.isCovered(15, 7));
  }

  @Test
  void outsideMarginIsRejected() {
    ResetArea area = new ResetArea(WIDTH, 8);

    assertThrows(IndexOutOfBoundsException.class, () -> area.setEmpty(-8, 0, true));
    assertThrows(IndexOutOfBoundsException.class, () -> area.isCovered(0, WIDTH + 7));
  }

  private static void fill(ResetArea area, int startX, int startZ, int sizeX, int sizeZ) {
    for (int z = startZ; z < startZ + sizeZ; ++z) {
      for (int x = startX; x < startX + sizeX; ++x) {
        area.setEmpty(x, z, true);
      }
    }
  }

}
