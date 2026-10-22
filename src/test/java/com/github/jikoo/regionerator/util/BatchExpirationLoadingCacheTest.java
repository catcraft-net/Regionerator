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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchExpirationLoadingCacheTest {

  @Test
  void keepsExpiringUnderConcurrentUse() throws InterruptedException {
    Set<String> saved = ConcurrentHashMap.newKeySet();
    BatchExpirationLoadingCache<String, String> cache = new BatchExpirationLoadingCache.Builder<String, String>()
        .setRetention(1L)
        .setCacheMax(50_000)
        .setFrequency(0L)
        .setBatchMax(16)
        .setBatchDelay(0L)
        .build(key -> key, saved::addAll);

    AtomicBoolean running = new AtomicBoolean(true);
    List<Thread> threads = new ArrayList<>();
    for (int t = 0; t < 4; ++t) {
      int offset = t * 1_000_000;
      threads.add(new Thread(() -> {
        int i = 0;
        while (running.get()) {
          String key = String.valueOf(offset + i++ % 5_000);
          cache.put(key, key);
          cache.getIfPresent(key);
        }
      }));
    }
    // Mirrors the periodic save, which adds every key to the expiration queue from another thread.
    threads.add(new Thread(() -> {
      while (running.get()) {
        cache.lazyExpireAll();
      }
    }));

    threads.forEach(Thread::start);
    Thread.sleep(1_000);
    running.set(false);
    for (Thread thread : threads) {
      thread.join();
    }

    // If a batch thread ever died mid-batch, the queue would stay claimed and nothing would drain.
    long deadline = System.currentTimeMillis() + 10_000;
    while ((cache.getCached() > 0 || cache.getQueued() > 0) && System.currentTimeMillis() < deadline) {
      cache.lazyExpireAll();
      Thread.sleep(20);
    }

    assertEquals(0, cache.getQueued(), "Expiration queue must keep draining");
    assertEquals(0, cache.getCached(), "Expired values must leave the cache");
    assertTrue(saved.size() > 0, "Expired values must reach the consumer");
  }

}
