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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlagUpdateTest {

  private static final String CHUNK = "world_10_-3";
  private static final long VISIT = 1_800_000_000_000L;

  @TempDir
  Path dir;
  private SQLeadenAdapter adapter;

  @AfterEach
  void afterEach() {
    if (adapter != null) {
      adapter.close();
    }
  }

  static Stream<Arguments> adapters() {
    return Stream.of(
        Arguments.of("SQLite", (AdapterFactory) SQLiteAdapter::new),
        Arguments.of("SQLeaden", (AdapterFactory) SQLeadenAdapter::new));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("adapters")
  void visitReplacesGeneratedMarker(String name, AdapterFactory factory) throws Exception {
    adapter = open(factory);
    adapter.update(List.of(flag(CHUNK, Config.FLAG_GENERATED)));
    adapter.update(List.of(flag(CHUNK, VISIT)));

    assertEquals(VISIT, adapter.get(CHUNK), "A visit must replace the generated marker");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("adapters")
  void permanentFlagReplacesGeneratedMarker(String name, AdapterFactory factory) throws Exception {
    adapter = open(factory);
    adapter.update(List.of(flag(CHUNK, Config.FLAG_GENERATED)));
    adapter.update(List.of(flag(CHUNK, Config.FLAG_ETERNAL)));

    assertEquals(Config.FLAG_ETERNAL, adapter.get(CHUNK));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("adapters")
  void generatedMarkerDoesNotReplacePermanentFlag(String name, AdapterFactory factory) throws Exception {
    adapter = open(factory);
    adapter.update(List.of(flag(CHUNK, Config.FLAG_ETERNAL)));
    adapter.update(List.of(flag(CHUNK, Config.FLAG_GENERATED)));

    assertEquals(Config.FLAG_ETERNAL, adapter.get(CHUNK));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("adapters")
  void generatedMarkerReplacesVisit(String name, AdapterFactory factory) throws Exception {
    adapter = open(factory);
    adapter.update(List.of(flag(CHUNK, VISIT)));
    // A chunk is only marked generated when it is generated again after being removed.
    adapter.update(List.of(flag(CHUNK, Config.FLAG_GENERATED)));

    assertEquals(Config.FLAG_GENERATED, adapter.get(CHUNK));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("adapters")
  void laterVisitReplacesEarlier(String name, AdapterFactory factory) throws Exception {
    adapter = open(factory);
    adapter.update(List.of(flag(CHUNK, VISIT)));
    adapter.update(List.of(flag(CHUNK, VISIT + 1)));

    assertEquals(VISIT + 1, adapter.get(CHUNK));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("adapters")
  void earlierVisitDoesNotReplaceLater(String name, AdapterFactory factory) throws Exception {
    adapter = open(factory);
    adapter.update(List.of(flag(CHUNK, VISIT)));
    adapter.update(List.of(flag(CHUNK, VISIT - 1)));

    assertEquals(VISIT, adapter.get(CHUNK));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("adapters")
  void defaultRemovesFlag(String name, AdapterFactory factory) throws Exception {
    adapter = open(factory);
    adapter.update(List.of(flag(CHUNK, VISIT)));
    adapter.update(List.of(flag(CHUNK, Config.FLAG_DEFAULT)));

    assertEquals(Config.FLAG_DEFAULT, adapter.get(CHUNK));
  }

  @Test
  void removalKeepsDeletionRecord() throws Exception {
    adapter = open(SQLiteAdapter::new);
    adapter.update(List.of(flag(CHUNK, VISIT)));
    adapter.update(List.of(flag(CHUNK, Config.FLAG_DEFAULT)));

    assertEquals(VISIT, adapter.get(CHUNK + "_old"), "Removing a flag must record its last value");
  }

  private SQLeadenAdapter open(AdapterFactory factory) throws SQLException {
    return factory.create(Logger.getLogger(getClass().getName()), dir.resolve("data.db"));
  }

  static ChunkFlagger.FlagData flag(String chunkId, long time) throws ReflectiveOperationException {
    Constructor<ChunkFlagger.FlagData> constructor =
        ChunkFlagger.FlagData.class.getDeclaredConstructor(String.class, long.class, boolean.class);
    constructor.setAccessible(true);
    return constructor.newInstance(chunkId, time, true);
  }

  @FunctionalInterface
  interface AdapterFactory {
    SQLeadenAdapter create(Logger logger, Path file) throws SQLException;
  }

}
