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

import com.github.jikoo.regionerator.util.yaml.Config;
import com.github.jikoo.regionerator.world.ChunkInfo;
import com.github.jikoo.regionerator.world.RegionInfo;
import com.github.jikoo.regionerator.world.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlagPrunerTest {

  private static final long FUTURE = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1);
  private static final long PAST = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
  private static final Logger LOGGER = Logger.getLogger(FlagPrunerTest.class.getName());

  @Test
  void parsesChunkIds() {
    assertEquals(new FlagPruner.ChunkCoordinate("world", 10, -3), FlagPruner.parse("world_10_-3"));
    assertEquals(new FlagPruner.ChunkCoordinate("my_world_nether", -5, 7), FlagPruner.parse("my_world_nether_-5_7"));
  }

  @Test
  void rejectsMalformedChunkIds() {
    assertNull(FlagPruner.parse("world"));
    assertNull(FlagPruner.parse("world_1"));
    assertNull(FlagPruner.parse("_1_2"));
    assertNull(FlagPruner.parse("world_a_2"));
    assertNull(FlagPruner.parse("world_1_2_old"));
    assertNull(FlagPruner.parse("world_1_"));
  }

  @Test
  void keepsPermanentFlags() {
    FlagPruner pruner = pruner(new StubWorld());

    assertFalse(pruner.canRemove("world_0_0", Config.FLAG_ETERNAL), "Permanent flags are kept even for missing chunks");
  }

  @Test
  void removesExpiredVisits() {
    FlagPruner pruner = new FlagPruner(Map.of(), id -> false, LOGGER);

    assertTrue(pruner.canRemove("world_0_0", PAST));
    assertTrue(pruner.canRemove("unknown", 0));
    assertEquals(List.of("world_0_0", "unknown"), pruner.confirm(List.of("world_0_0", "unknown")));
    assertEquals(2, pruner.getExpiredFlags());
  }

  @Test
  void keepsCurrentFlagsForExistingChunks() {
    StubWorld world = new StubWorld();
    world.chunks.add(chunk(5, -40));
    FlagPruner pruner = pruner(world);

    assertFalse(pruner.canRemove("world_5_-40", FUTURE));
    assertFalse(pruner.canRemove("world_5_-40", Config.FLAG_GENERATED));
  }

  @Test
  void removesFlagsForMissingChunks() {
    StubWorld world = new StubWorld();
    world.chunks.add(chunk(5, -40));
    FlagPruner pruner = pruner(world);

    assertTrue(pruner.canRemove("world_6_-40", FUTURE));
    assertTrue(pruner.canRemove("world_5_-41", Config.FLAG_GENERATED));
    assertEquals(Set.of("world_6_-40", "world_5_-41"), Set.copyOf(pruner.confirm(List.of("world_6_-40", "world_5_-41"))));
    assertEquals(2, pruner.getMissingChunkFlags());
    assertEquals(0, pruner.getExpiredFlags());
  }

  @Test
  void confirmsEachRegionOnce() {
    StubWorld world = new StubWorld();
    FlagPruner pruner = pruner(world);
    List<String> candidates = List.of("world_0_0", "world_1_0", "world_31_31", "world_32_0");
    candidates.forEach(id -> assertTrue(pruner.canRemove(id, FUTURE)));

    assertEquals(Set.copyOf(candidates), Set.copyOf(pruner.confirm(candidates)));
    assertEquals(2, world.confirmations);
  }

  @Test
  void keepsChunksGeneratedSinceRegionWasListed() {
    StubWorld world = new StubWorld();
    FlagPruner pruner = pruner(world);
    assertTrue(pruner.canRemove("world_0_0", FUTURE));
    assertTrue(pruner.canRemove("world_1_0", FUTURE));

    world.chunks.add(chunk(0, 0));

    assertEquals(List.of("world_1_0"), pruner.confirm(List.of("world_0_0", "world_1_0")));
  }

  @Test
  void keepsChunksTheServerHolds() {
    StubWorld world = new StubWorld();
    FlagPruner pruner = pruner(world);
    assertTrue(pruner.canRemove("world_0_0", FUTURE));

    world.busyChunks.add(chunk(0, 0));

    assertEquals(List.of(), pruner.confirm(List.of("world_0_0")), "Chunks in memory may not be saved yet");
  }

  @Test
  void keepsFlagsWhenRegionOpenedBeforeConfirming() {
    StubWorld world = new StubWorld();
    FlagPruner pruner = pruner(world);
    assertTrue(pruner.canRemove("world_0_0", FUTURE));

    world.regionsInUse.add(region(0, 0));

    assertEquals(List.of(), pruner.confirm(List.of("world_0_0")));
    assertEquals(0, pruner.getMissingChunkFlags());
  }

  @Test
  void keepsFlagsWhenConfirmationFails() {
    StubWorld world = new StubWorld();
    FlagPruner pruner = pruner(world);
    assertTrue(pruner.canRemove("world_0_0", FUTURE));

    world.failReads = true;

    assertEquals(List.of(), pruner.confirm(List.of("world_0_0")));
  }

  @Test
  void reportsHeldFlags() {
    FlagPruner pruner = new FlagPruner(Map.of(), "world_0_0"::equals, LOGGER);

    assertTrue(pruner.isHeld("world_0_0"));
    assertFalse(pruner.isHeld("world_0_1"));
  }

  @Test
  void keepsFlagsInRegionsInUse() {
    StubWorld world = new StubWorld();
    world.regionsInUse.add(region(0, 0));
    FlagPruner pruner = pruner(world);

    assertFalse(pruner.canRemove("world_31_31", FUTURE), "The server may be generating chunks in an open region");
    assertTrue(pruner.canRemove("world_32_31", FUTURE));
  }

  @Test
  void checksRegionUseEveryTime() {
    StubWorld world = new StubWorld();
    FlagPruner pruner = pruner(world);
    assertTrue(pruner.canRemove("world_0_0", FUTURE));

    world.regionsInUse.add(region(0, 0));

    assertFalse(pruner.canRemove("world_1_0", FUTURE), "The region was opened after its chunks were listed");
  }

  @Test
  void readsEachRegionOnce() {
    StubWorld world = new StubWorld();
    FlagPruner pruner = pruner(world);

    for (int x = 0; x < 32; ++x) {
      pruner.canRemove("world_" + x + "_0", FUTURE);
    }
    pruner.canRemove("world_32_0", FUTURE);

    assertEquals(2, world.reads);
  }

  @Test
  void keepsFlagsInOtherWorlds() {
    FlagPruner pruner = pruner(new StubWorld());

    assertFalse(pruner.canRemove("world_nether_0_0", FUTURE), "Only worlds Regionerator is enabled in are checked");
  }

  @Test
  void keepsFlagsWhenRegionUseIsUnknown() {
    StubWorld world = new StubWorld();
    world.canDetectUse = false;
    FlagPruner pruner = pruner(world);

    assertFalse(pruner.canRemove("world_0_0", FUTURE));
  }

  @Test
  void keepsFlagsWhenRegionCannotBeRead() {
    StubWorld world = new StubWorld();
    world.readable = false;
    FlagPruner pruner = pruner(world);

    assertFalse(pruner.canRemove("world_0_0", FUTURE));
  }

  @Test
  void keepsFlagsWhenRegionReadFails() {
    StubWorld world = new StubWorld();
    world.failReads = true;
    FlagPruner pruner = pruner(world);

    assertFalse(pruner.canRemove("world_0_0", FUTURE));
  }

  private static FlagPruner pruner(StubWorld world) {
    return new FlagPruner(Map.of("world", world), id -> false, LOGGER);
  }

  private static long chunk(int x, int z) {
    return (long) x << 32 | (z & 0xFFFFFFFFL);
  }

  private static long region(int x, int z) {
    return chunk(x, z);
  }

  /** A world with no server behind it, made of the chunks and open regions it is given. */
  private static final class StubWorld extends WorldInfo {

    final Set<Long> chunks = new HashSet<>();
    final Set<Long> regionsInUse = new HashSet<>();
    final Set<Long> busyChunks = new HashSet<>();
    boolean canDetectUse = true;
    boolean readable = true;
    boolean failReads;
    int reads;
    int confirmations;

    @SuppressWarnings("DataFlowIssue")
    StubWorld() {
      super(null, null);
    }

    @Override
    public boolean canDetectRegionUse() {
      return canDetectUse;
    }

    @Override
    public boolean isRegionInUse(int regionX, int regionZ) {
      return regionsInUse.contains(region(regionX, regionZ));
    }

    @Override
    public @NotNull RegionInfo getRegion(int regionX, int regionZ) {
      return new StubRegion(this, regionX << 5, regionZ << 5);
    }

    @Override
    public @NotNull Stream<RegionInfo> getRegions() {
      return Stream.empty();
    }

  }

  private static final class StubRegion extends RegionInfo {

    private final StubWorld world;

    StubRegion(StubWorld world, int lowestChunkX, int lowestChunkZ) {
      super(world, lowestChunkX, lowestChunkZ);
      this.world = world;
    }

    @Override
    public boolean read() throws IOException {
      ++world.reads;
      if (world.failReads) {
        throw new IOException("Unreadable");
      }
      return world.readable;
    }

    @Override
    public boolean hasChunk(int localChunkX, int localChunkZ) {
      return world.chunks.contains(chunk(getLowestChunkX() + localChunkX, getLowestChunkZ() + localChunkZ));
    }

    @Override
    public @NotNull BitSet getAbsentChunks(@NotNull BitSet localChunks) throws IOException {
      ++world.confirmations;
      BitSet absent = new BitSet();
      if (world.failReads) {
        throw new IOException("Unreadable");
      }
      if (world.regionsInUse.contains(region(getLowestChunkX() >> 5, getLowestChunkZ() >> 5))) {
        return absent;
      }
      localChunks.stream().forEach(index -> {
        long chunk = chunk(getLowestChunkX() + index % 32, getLowestChunkZ() + index / 32);
        if (!world.chunks.contains(chunk) && !world.busyChunks.contains(chunk)) {
          absent.set(index);
        }
      });
      return absent;
    }

    @Override
    public boolean write() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean exists() {
      throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull ChunkInfo getLocalChunk(int localChunkX, int localChunkZ) {
      throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Stream<ChunkInfo> getChunks() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int getChunksPerRegion() {
      return 1024;
    }

  }

}
