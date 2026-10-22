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
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static com.github.jikoo.regionerator.database.FlagUpdateTest.flag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PruneTest {

  private static final long NOW = System.currentTimeMillis();
  private static final long PAST = NOW - TimeUnit.DAYS.toMillis(1);
  private static final long FUTURE = NOW + TimeUnit.DAYS.toMillis(1);
  private static final long CUTOFF = NOW - TimeUnit.DAYS.toMillis(90);
  private static final FlagFilter EXPIRED = (chunkId, time) -> time < NOW;

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
  void removesFlagsTheFilterAllows(String name, FlagUpdateTest.AdapterFactory factory) throws Exception {
    adapter = open(factory);
    adapter.update(List.of(
        flag("world_0_0", PAST),
        flag("world_0_1", FUTURE),
        flag("world_0_2", Config.FLAG_ETERNAL),
        flag("world_0_3", Config.FLAG_GENERATED)));

    PruneResult result = adapter.prune(EXPIRED, CUTOFF, () -> false);

    assertTrue(result.complete());
    assertEquals(4, result.rowsChecked());
    assertEquals(1, result.flagsRemoved());
    assertEquals(Config.FLAG_DEFAULT, adapter.get("world_0_0"));
    assertEquals(FUTURE, adapter.get("world_0_1"));
    assertEquals(Config.FLAG_ETERNAL, adapter.get("world_0_2"));
    assertEquals(Config.FLAG_GENERATED, adapter.get("world_0_3"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("com.github.jikoo.regionerator.database.FlagUpdateTest#adapters")
  void neverPassesDeletionRecordsToFilter(String name, FlagUpdateTest.AdapterFactory factory) throws Exception {
    adapter = open(factory);
    insertRecord("world_0_0_old", PAST, NOW);
    List<String> seen = new ArrayList<>();

    adapter.prune((chunkId, time) -> seen.add(chunkId), CUTOFF, () -> false);

    assertEquals(List.of(), seen);
    assertEquals(PAST, adapter.get("world_0_0_old"));
  }

  @Test
  void pruningDoesNotCreateDeletionRecords() throws Exception {
    adapter = open(SQLiteAdapter::new);
    adapter.update(List.of(flag("world_0_0", PAST)));

    adapter.prune(EXPIRED, CUTOFF, () -> false);

    assertEquals(Config.FLAG_DEFAULT, adapter.get("world_0_0"));
    assertEquals(Config.FLAG_DEFAULT, adapter.get("world_0_0_old"), "A pruned flag was not a deleted chunk");
  }

  @Test
  void deletionStillRecordedAfterPruning() throws Exception {
    adapter = open(SQLiteAdapter::new);
    adapter.update(List.of(flag("world_0_0", PAST)));
    adapter.prune(EXPIRED, CUTOFF, () -> false);

    adapter.update(List.of(flag("world_0_1", FUTURE)));
    adapter.update(List.of(flag("world_0_1", Config.FLAG_DEFAULT)));

    assertEquals(FUTURE, adapter.get("world_0_1_old"));
    Long deleted = getDeleted("world_0_1_old");
    assertNotNull(deleted, "Deletion records must record when they were made");
    assertTrue(Math.abs(deleted - System.currentTimeMillis()) < TimeUnit.MINUTES.toMillis(1));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("com.github.jikoo.regionerator.database.FlagUpdateTest#adapters")
  void datesUndatedDeletionRecords(String name, FlagUpdateTest.AdapterFactory factory) throws Exception {
    adapter = open(factory);
    insertRecord("world_0_0_old", PAST, null);

    PruneResult result = adapter.prune(EXPIRED, CUTOFF, () -> false);

    assertEquals(1, result.recordsDated());
    assertEquals(0, result.recordsRemoved());
    assertEquals(PAST, adapter.get("world_0_0_old"), "Undated records start their retention period instead");
    Long deleted = getDeleted("world_0_0_old");
    assertNotNull(deleted);
    assertTrue(deleted >= NOW);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("com.github.jikoo.regionerator.database.FlagUpdateTest#adapters")
  void removesDeletionRecordsOlderThanCutoff(String name, FlagUpdateTest.AdapterFactory factory) throws Exception {
    adapter = open(factory);
    insertRecord("world_0_0_old", PAST, CUTOFF - 1);
    insertRecord("world_0_1_old", PAST, CUTOFF + 1);

    PruneResult result = adapter.prune(EXPIRED, CUTOFF, () -> false);

    assertEquals(1, result.recordsRemoved());
    assertEquals(0, result.flagsRemoved());
    assertEquals(Config.FLAG_DEFAULT, adapter.get("world_0_0_old"));
    assertEquals(PAST, adapter.get("world_0_1_old"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("com.github.jikoo.regionerator.database.FlagUpdateTest#adapters")
  @Timeout(30)
  void keepsFlagChangedAfterItWasExamined(String name, FlagUpdateTest.AdapterFactory factory) throws Exception {
    adapter = open(factory);
    adapter.update(List.of(flag("world_0_0", PAST)));

    adapter.prune((chunkId, time) -> {
      try {
        // A player visits while the filter is deciding.
        adapter.update(List.of(flag(chunkId, FUTURE)));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return true;
    }, CUTOFF, () -> false);

    assertEquals(FUTURE, adapter.get("world_0_0"), "A flag that changed after it was examined must be kept");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("com.github.jikoo.regionerator.database.FlagUpdateTest#adapters")
  void keepsFlagsNotConfirmed(String name, FlagUpdateTest.AdapterFactory factory) throws Exception {
    adapter = open(factory);
    adapter.update(List.of(flag("world_0_0", PAST), flag("world_0_1", PAST)));

    PruneResult result = adapter.prune(new FlagFilter() {
      @Override
      public boolean canRemove(@NotNull String chunkId, long time) {
        return true;
      }

      @Override
      public @NotNull Collection<String> confirm(@NotNull Collection<String> chunkIds) {
        return chunkIds.stream().filter("world_0_1"::equals).toList();
      }
    }, CUTOFF, () -> false);

    assertEquals(1, result.flagsRemoved());
    assertEquals(PAST, adapter.get("world_0_0"));
    assertEquals(Config.FLAG_DEFAULT, adapter.get("world_0_1"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("com.github.jikoo.regionerator.database.FlagUpdateTest#adapters")
  void keepsFlagsHeldInMemory(String name, FlagUpdateTest.AdapterFactory factory) throws Exception {
    adapter = open(factory);
    adapter.update(List.of(flag("world_0_0", PAST), flag("world_0_1", PAST)));

    PruneResult result = adapter.prune(new FlagFilter() {
      @Override
      public boolean canRemove(@NotNull String chunkId, long time) {
        return true;
      }

      @Override
      public boolean isHeld(@NotNull String chunkId) {
        assertTrue(Thread.holdsLock(adapter.lock), "Must be checked while the database is locked");
        return "world_0_0".equals(chunkId);
      }
    }, CUTOFF, () -> false);

    assertEquals(1, result.flagsRemoved());
    assertEquals(PAST, adapter.get("world_0_0"));
    assertEquals(Config.FLAG_DEFAULT, adapter.get("world_0_1"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("com.github.jikoo.regionerator.database.FlagUpdateTest#adapters")
  void prunesInBatches(String name, FlagUpdateTest.AdapterFactory factory) throws Exception {
    adapter = open(factory);
    List<ChunkFlagger.FlagData> flags = new ArrayList<>();
    for (int i = 0; i < 2_500; ++i) {
      flags.add(flag("world_" + i + "_0", i % 2 == 0 ? PAST : FUTURE));
    }
    adapter.update(flags);

    PruneResult result = adapter.prune(EXPIRED, CUTOFF, () -> false);

    assertTrue(result.complete());
    assertEquals(2_500, result.rowsChecked());
    assertEquals(1_250, result.flagsRemoved());
    assertEquals(1_250, count());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("com.github.jikoo.regionerator.database.FlagUpdateTest#adapters")
  void stopsWhenCancelled(String name, FlagUpdateTest.AdapterFactory factory) throws Exception {
    adapter = open(factory);
    List<ChunkFlagger.FlagData> flags = new ArrayList<>();
    for (int i = 0; i < 2_500; ++i) {
      flags.add(flag("world_" + i + "_0", PAST));
    }
    adapter.update(flags);
    int[] batches = { 0 };

    PruneResult result = adapter.prune(EXPIRED, CUTOFF, () -> batches[0]++ > 0);

    assertFalse(result.complete());
    assertEquals(1_000, result.rowsChecked());
    assertEquals(1_500, count());
  }

  @Test
  void upgradesEarlierSchema() throws Exception {
    // Schema and trigger as created by earlier versions.
    try (Connection connection = connect(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE `chunkdata`(`chunk_id` TEXT NOT NULL UNIQUE, `time` BIGINT NOT NULL)");
      statement.executeUpdate("CREATE TRIGGER chunkdataold AFTER DELETE ON chunkdata WHEN OLD.time NOT NULL"
          + " BEGIN INSERT OR REPLACE INTO chunkdata(chunk_id,time) VALUES (OLD.chunk_id || '_old', OLD.time); END");
      statement.executeUpdate("INSERT INTO chunkdata(chunk_id,time) VALUES ('world_0_0', " + FUTURE + ")");
      statement.executeUpdate("INSERT INTO chunkdata(chunk_id,time) VALUES ('world_0_1_old', " + PAST + ")");
    }

    adapter = open(SQLiteAdapter::new);

    assertEquals(FUTURE, adapter.get("world_0_0"));
    assertEquals(PAST, adapter.get("world_0_1_old"));
    assertNull(getDeleted("world_0_1_old"));
    adapter.update(List.of(flag("world_0_0", Config.FLAG_DEFAULT)));
    assertEquals(FUTURE, adapter.get("world_0_0_old"));
    assertNotNull(getDeleted("world_0_0_old"), "The replacement trigger must record when");
    // Removing a deletion record must not make a record of the record.
    synchronized (adapter.lock) {
      try (Statement statement = adapter.database.createStatement()) {
        statement.executeUpdate("DELETE FROM chunkdata WHERE chunk_id='world_0_0_old'");
        adapter.database.commit();
      }
    }
    assertEquals(Config.FLAG_DEFAULT, adapter.get("world_0_0_old_old"));
  }

  private SQLeadenAdapter open(FlagUpdateTest.AdapterFactory factory) throws SQLException {
    return factory.create(Logger.getLogger(getClass().getName()), dir.resolve("data.db"));
  }

  private Connection connect() throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("data.db"));
  }

  private void insertRecord(String chunkId, long time, Long deleted) throws SQLException {
    synchronized (adapter.lock) {
      try (PreparedStatement statement = adapter.database.prepareStatement(
          "INSERT INTO chunkdata(chunk_id,time,deleted) VALUES (?,?,?)")) {
        statement.setString(1, chunkId);
        statement.setLong(2, time);
        if (deleted == null) {
          statement.setNull(3, java.sql.Types.BIGINT);
        } else {
          statement.setLong(3, deleted);
        }
        statement.executeUpdate();
        adapter.database.commit();
      }
    }
  }

  private Long getDeleted(String chunkId) throws SQLException {
    synchronized (adapter.lock) {
      try (PreparedStatement statement = adapter.database.prepareStatement(
          "SELECT deleted FROM chunkdata WHERE chunk_id=?")) {
        statement.setString(1, chunkId);
        try (ResultSet resultSet = statement.executeQuery()) {
          assertTrue(resultSet.next(), "Missing row " + chunkId);
          long deleted = resultSet.getLong(1);
          Long value = resultSet.wasNull() ? null : deleted;
          adapter.database.commit();
          return value;
        }
      }
    }
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

}
