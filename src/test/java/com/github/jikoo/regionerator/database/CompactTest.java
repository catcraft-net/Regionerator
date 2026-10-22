/*
 * Copyright (c) 2015-2026 by Jikoo.
 *
 * Regionerator is licensed under a Creative Commons
 * Attribution-ShareAlike 4.0 International License.
 *
 * You should have received a copy of the license along with this
 * work. If not, see <http://creativecommons.org/licenses/by-sa/4.0/>.
 */

package com.github.jikoo.regionerator.database;

import com.github.jikoo.regionerator.ChunkFlagger;
import com.github.jikoo.regionerator.util.yaml.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static com.github.jikoo.regionerator.database.FlagUpdateTest.flag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(60)
class CompactTest {

  private static final long FUTURE = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1);
  private static final int ROWS = 20_000;

  @TempDir
  Path dir;
  private SQLeadenAdapter adapter;

  @AfterEach
  void afterEach() {
    if (adapter != null) {
      adapter.close();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("com.github.jikoo.regionerator.database.FlagUpdateTest#adapters")
  void usesWriteAheadLogging(String name, FlagUpdateTest.AdapterFactory factory) throws Exception {
    adapter = open(factory);

    synchronized (adapter.lock) {
      try (Statement statement = adapter.database.createStatement();
          ResultSet resultSet = statement.executeQuery("PRAGMA journal_mode")) {
        assertTrue(resultSet.next());
        assertEquals("wal", resultSet.getString(1));
      }
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("com.github.jikoo.regionerator.database.FlagUpdateTest#adapters")
  void releasesUnusedSpace(String name, FlagUpdateTest.AdapterFactory factory) throws Exception {
    adapter = open(factory);
    fill(0, ROWS);
    // Keep every tenth flag.
    adapter.prune((chunkId, time) -> (time - FUTURE) % 10 != 0, 0, () -> false);
    assertTrue(adapter.getFreeBytes() > 0);

    CompactResult result = adapter.compact(() -> false);

    assertNotNull(result);
    assertTrue(result.complete());
    assertTrue(result.sizeAfter() < result.sizeBefore() / 2, result.toString());
    assertEquals(0, adapter.getFreeBytes());
    assertEquals(ROWS / 10, count());
    assertEquals(FUTURE + 10, adapter.get("world_10_0"));
    assertNoCompactionFiles();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("com.github.jikoo.regionerator.database.FlagUpdateTest#adapters")
  void keepsFlagsWrittenWhileCompacting(String name, FlagUpdateTest.AdapterFactory factory) throws Exception {
    adapter = open(factory);
    fill(0, ROWS);
    AtomicInteger checks = new AtomicInteger();

    CompactResult result = adapter.compact(() -> {
      try {
        switch (checks.incrementAndGet()) {
          // Before copying: in the snapshot.
          case 1 -> adapter.update(List.of(flag("world_0_0", FUTURE + ROWS)));
          // After copying: more than the final step copies, so copied in rounds.
          case 2 -> {
            fill(ROWS, ROWS + 2_500);
            adapter.update(List.of(flag("world_1_0", Config.FLAG_ETERNAL), flag("world_2_0", Config.FLAG_DEFAULT)));
          }
          // Final step, while the lock is held.
          default -> adapter.update(List.of(flag("world_3_0", Config.FLAG_DEFAULT), flag("late_0_0", FUTURE)));
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return false;
    });

    assertNotNull(result);
    assertTrue(result.complete());
    assertTrue(checks.get() >= 3);
    assertEquals(FUTURE + ROWS, adapter.get("world_0_0"));
    assertEquals(Config.FLAG_ETERNAL, adapter.get("world_1_0"));
    assertEquals(Config.FLAG_DEFAULT, adapter.get("world_2_0"));
    assertEquals(Config.FLAG_DEFAULT, adapter.get("world_3_0"));
    assertEquals(FUTURE, adapter.get("late_0_0"));
    assertEquals(FUTURE + ROWS + 2_499, adapter.get("world_" + (ROWS + 2_499) + "_0"));
    if (adapter instanceof SQLiteAdapter) {
      assertEquals(FUTURE + 2, adapter.get("world_2_0_old"), "Deletion records made while compacting must be kept");
      assertEquals(FUTURE + 3, adapter.get("world_3_0_old"));
    }
    assertEquals(ROWS + 2_500 - 2 + 1, count() - (adapter instanceof SQLiteAdapter ? 2 : 0));
    assertNoCompactionFiles();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("com.github.jikoo.regionerator.database.FlagUpdateTest#adapters")
  void keepsFlagsWrittenConcurrently(String name, FlagUpdateTest.AdapterFactory factory) throws Exception {
    adapter = open(factory);
    fill(0, ROWS);
    Map<String, Long> expected = new ConcurrentHashMap<>();
    AtomicBoolean stop = new AtomicBoolean();
    List<Throwable> failures = new ArrayList<>();

    Thread writer = new Thread(() -> {
      try {
        for (int i = 0; !stop.get(); ++i) {
          String added = "writer_" + i + "_0";
          adapter.update(List.of(flag(added, FUTURE + i)));
          expected.put(added, FUTURE + i);
          if (i % 3 == 0) {
            String removed = "world_" + i + "_0";
            adapter.update(List.of(flag(removed, Config.FLAG_DEFAULT)));
            expected.put(removed, Config.FLAG_DEFAULT);
          }
        }
      } catch (Throwable t) {
        failures.add(t);
      }
    });
    writer.start();

    CompactResult result;
    try {
      result = adapter.compact(() -> false);
    } finally {
      stop.set(true);
      writer.join();
    }

    assertEquals(List.of(), failures);
    assertNotNull(result);
    assertTrue(result.complete());
    assertFalse(expected.isEmpty());
    for (Map.Entry<String, Long> entry : expected.entrySet()) {
      assertEquals(entry.getValue(), adapter.get(entry.getKey()), entry.getKey());
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("com.github.jikoo.regionerator.database.FlagUpdateTest#adapters")
  void leavesDatabaseUnchangedWhenCancelled(String name, FlagUpdateTest.AdapterFactory factory) throws Exception {
    adapter = open(factory);
    fill(0, ROWS);
    AtomicInteger checks = new AtomicInteger();
    BooleanSupplier cancelAfterCopy = () -> checks.incrementAndGet() > 1;

    CompactResult result = adapter.compact(cancelAfterCopy);

    assertNotNull(result);
    assertFalse(result.complete());
    assertEquals(ROWS, count());
    assertNoCompactionFiles();
    adapter.update(List.of(flag("world_0_0", Config.FLAG_ETERNAL)));
    assertEquals(Config.FLAG_ETERNAL, adapter.get("world_0_0"));
  }

  @Test
  void deletionStillRecordedAfterCompacting() throws Exception {
    adapter = open(SQLiteAdapter::new);
    fill(0, 10);

    adapter.compact(() -> false);
    adapter.update(List.of(flag("world_5_0", Config.FLAG_DEFAULT)));

    assertEquals(FUTURE + 5, adapter.get("world_5_0_old"));
  }

  @Test
  void removesUnfinishedCompactionOnOpen() throws Exception {
    Files.writeString(dir.resolve("data.db.compact"), "unfinished");

    adapter = open(SQLiteAdapter::new);

    assertNoCompactionFiles();
  }

  private SQLeadenAdapter open(FlagUpdateTest.AdapterFactory factory) throws SQLException {
    return factory.create(Logger.getLogger(getClass().getName()), dir.resolve("data.db"));
  }

  private void fill(int from, int to) throws Exception {
    List<ChunkFlagger.FlagData> flags = new ArrayList<>();
    for (int i = from; i < to; ++i) {
      flags.add(flag("world_" + i + "_0", FUTURE + i));
    }
    adapter.update(flags);
  }

  private long count() throws SQLException {
    synchronized (adapter.lock) {
      try (Statement statement = adapter.database.createStatement();
          ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM chunkdata")) {
        resultSet.next();
        long count = resultSet.getLong(1);
        adapter.database.commit();
        return count;
      }
    }
  }

  private void assertNoCompactionFiles() throws Exception {
    try (var files = Files.list(dir)) {
      assertEquals(List.of(), files.map(path -> path.getFileName().toString()).filter(file -> file.contains(".compact")).toList());
    }
  }

}
