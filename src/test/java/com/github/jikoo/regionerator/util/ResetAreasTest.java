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

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResetAreasTest {

  private static final int WIDTH = 32;

  @Test
  void sizeOneKeepsEveryChunk() {
    BitSet deletable = new BitSet();
    deletable.set(index(3, 4));
    deletable.set(index(20, 30));

    assertEquals(deletable, ResetAreas.coveredBySquares(deletable, WIDTH, 1));
    assertEquals(deletable, ResetAreas.coveredBySquares(deletable, WIDTH, 0));
  }

  @Test
  void scatteredChunksAreKept() {
    BitSet deletable = new BitSet();
    for (int z = 0; z < WIDTH; ++z) {
      for (int x = 0; x < WIDTH; ++x) {
        if ((x + z) % 2 == 0) {
          deletable.set(index(x, z));
        }
      }
    }

    assertTrue(ResetAreas.coveredBySquares(deletable, WIDTH, 8).isEmpty(), "No 8x8 square in a checkerboard");
  }

  @Test
  void exactSquareIsCovered() {
    BitSet deletable = rectangle(5, 5, 8, 8);

    assertEquals(deletable, ResetAreas.coveredBySquares(deletable, WIDTH, 8));
  }

  @Test
  void rectangleTooNarrowIsKept() {
    BitSet deletable = rectangle(5, 5, 8, 7);

    assertTrue(ResetAreas.coveredBySquares(deletable, WIDTH, 8).isEmpty(), "8x7 is smaller than 8x8");
  }

  @Test
  void largerAreaIsFullyCovered() {
    BitSet deletable = rectangle(2, 3, 10, 13);

    assertEquals(deletable, ResetAreas.coveredBySquares(deletable, WIDTH, 8));
  }

  @Test
  void strayChunkBesideSquareIsKept() {
    BitSet deletable = rectangle(0, 0, 8, 8);
    deletable.set(index(8, 0));
    deletable.set(index(15, 15));

    BitSet covered = ResetAreas.coveredBySquares(deletable, WIDTH, 8);

    assertEquals(rectangle(0, 0, 8, 8), covered);
  }

  @Test
  void squareAtRegionEdgeIsCovered() {
    BitSet deletable = rectangle(24, 24, 8, 8);

    assertEquals(deletable, ResetAreas.coveredBySquares(deletable, WIDTH, 8));
  }

  @Test
  void lShapeKeepsOnlyCoveredPart() {
    // 8x8 square plus a 2-wide arm along its top: the arm cannot hold an 8x8 square of its own.
    BitSet deletable = rectangle(0, 0, 8, 8);
    deletable.or(rectangle(8, 0, 6, 2));

    assertEquals(rectangle(0, 0, 8, 8), ResetAreas.coveredBySquares(deletable, WIDTH, 8));
  }

  @Test
  void wholeRegionIsCovered() {
    BitSet deletable = rectangle(0, 0, WIDTH, WIDTH);

    assertEquals(deletable, ResetAreas.coveredBySquares(deletable, WIDTH, 8));
    assertEquals(deletable, ResetAreas.coveredBySquares(deletable, WIDTH, WIDTH));
  }

  @Test
  void squareLargerThanRegionCoversNothing() {
    BitSet deletable = rectangle(0, 0, WIDTH, WIDTH);

    assertTrue(ResetAreas.coveredBySquares(deletable, WIDTH, WIDTH + 1).isEmpty());
  }

  @Test
  void inputIsNotModified() {
    BitSet deletable = rectangle(5, 5, 8, 7);
    BitSet copy = (BitSet) deletable.clone();

    ResetAreas.coveredBySquares(deletable, WIDTH, 8);

    assertEquals(copy, deletable);
  }

  private static BitSet rectangle(int startX, int startZ, int sizeX, int sizeZ) {
    BitSet bits = new BitSet();
    for (int z = startZ; z < startZ + sizeZ; ++z) {
      for (int x = startX; x < startX + sizeX; ++x) {
        bits.set(index(x, z));
      }
    }
    return bits;
  }

  private static int index(int x, int z) {
    return z * WIDTH + x;
  }

}
