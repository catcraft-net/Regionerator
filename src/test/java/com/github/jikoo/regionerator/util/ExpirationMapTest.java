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

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpirationMapTest {

  @Test
  void addTracksNewValue() {
    ExpirationMap<String> map = new ExpirationMap<>(60_000L, 10, 0L);

    map.add("a");

    assertTrue(map.contains("a"), "Newly added value must be tracked");
    assertEquals(1, map.size());
  }

  @Test
  void repeatedAddKeepsOneEntry() throws InterruptedException {
    ExpirationMap<String> map = new ExpirationMap<>(60_000L, 1, 0L);

    for (int i = 0; i < 5; ++i) {
      map.add("a");
      Thread.sleep(2);
    }

    assertEquals(1, map.size());
    // With a capacity of 1, stale duplicate entries would push the value over capacity.
    Thread.sleep(2);
    assertTrue(map.doExpiration().isEmpty(), "Re-added value must not expire as a duplicate");
    assertTrue(map.contains("a"));
  }

  @Test
  void removeUntracksValue() {
    ExpirationMap<String> map = new ExpirationMap<>(60_000L, 10, 0L);

    map.add("a");
    map.remove("a");

    assertFalse(map.contains("a"));
    assertEquals(0, map.size());
  }

  @Test
  void expiresByTime() throws InterruptedException {
    ExpirationMap<String> map = new ExpirationMap<>(1L, -1, 0L);

    map.add("a");
    Thread.sleep(5);

    assertEquals(Set.of("a"), map.doExpiration());
    assertFalse(map.contains("a"));
  }

  @Test
  void expiresOldestOverCapacity() throws InterruptedException {
    ExpirationMap<String> map = new ExpirationMap<>(60_000L, 2, 0L);

    map.add("a");
    Thread.sleep(2);
    map.add("b");
    Thread.sleep(2);
    map.add("c");
    Thread.sleep(2);
    // Touching "a" makes it the newest, so "b" is now the oldest.
    map.add("a");
    Thread.sleep(2);

    assertEquals(Set.of("b"), map.doExpiration());
    assertTrue(map.contains("a"));
    assertTrue(map.contains("c"));
    assertEquals(2, map.size());
  }

}
