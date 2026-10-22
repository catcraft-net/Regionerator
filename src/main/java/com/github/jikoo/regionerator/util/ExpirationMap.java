/*
 * Copyright (c) 2015-2021 by Jikoo.
 *
 * Regionerator is licensed under a Creative Commons
 * Attribution-ShareAlike 4.0 International License.
 *
 * You should have received a copy of the license along with this
 * work. If not, see <http://creativecommons.org/licenses/by-sa/4.0/>.
 */

package com.github.jikoo.regionerator.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.NotNull;

/**
 * Wrapper for mapping keys to timestamps for expiration.
 *
 * <p>All access is synchronized. The timestamp buckets and the reverse lookup must be updated
 * together, otherwise a value accessed by two threads at once can be left in a stale bucket.
 */
public class ExpirationMap<V> {

	private final TreeMap<Long, Set<V>> multiMap = new TreeMap<>();
	private final Map<V, Long> inverse = new HashMap<>();
	private final long durationMillis;
	private final int maxSize;
	private final long expirationFrequency;
	private long lastExpiration = 0;

	public ExpirationMap(long durationMillis) {
		this(durationMillis, -1, 10_000L);
	}

	/**
	 * Construct a new {@code ExpirationMap}.
	 *
	 * @param durationMillis the time in milliseconds before a value expires
	 * @param maxSize the maximum number of values to retain, or a negative number for no limit
	 * @param expirationFrequency the minimum time in milliseconds between expiration checks
	 */
	public ExpirationMap(long durationMillis, int maxSize, long expirationFrequency) {
		this.durationMillis = durationMillis;
		this.maxSize = maxSize;
		this.expirationFrequency = expirationFrequency;
	}

	/**
	 * Check and collect all values which have expired.
	 *
	 * <p>Values expire when their timestamp passes or, oldest first, when more than the maximum
	 * number of values are stored.
	 *
	 * @return a {@link Set} of expired values
	 */
	public synchronized @NotNull Set<V> doExpiration() {
		long now = System.currentTimeMillis();

		if (lastExpiration >= now - expirationFrequency) {
			return Collections.emptySet();
		}

		lastExpiration = now;

		Iterator<Map.Entry<Long, Set<V>>> iterator = multiMap.entrySet().iterator();
		Set<V> values = new HashSet<>();

		while (iterator.hasNext()) {
			Map.Entry<Long, Set<V>> next = iterator.next();

			if (next.getKey() > now && (maxSize < 0 || inverse.size() <= maxSize)) {
				// Buckets are ordered by expiration; the remainder are neither expired nor over capacity.
				break;
			}

			values.addAll(next.getValue());

			for (V key : next.getValue()) {
				inverse.remove(key);
			}

			iterator.remove();
		}

		return values;
	}

	/**
	 * Adds or updates a value's expiration.
	 *
	 * @param value the value to add
	 */
	public synchronized void add(V value) {
		long timestamp = System.currentTimeMillis() + durationMillis;
		Long replacedTimestamp = inverse.put(value, timestamp);

		if (replacedTimestamp != null) {
			if (replacedTimestamp == timestamp) {
				// Already in the correct bucket.
				return;
			}
			// Key was already set to expire. Remove old expiration entry.
			removeFromBucket(replacedTimestamp, value);
		}

		// Add new expiration entry.
		multiMap.computeIfAbsent(timestamp, key -> new HashSet<>()).add(value);
	}

	/**
	 * Removes a mapped value.
	 *
	 * @param value the value to remove
	 */
	public synchronized void remove(V value) {
		Long removedTimestamp = inverse.remove(value);
		if (removedTimestamp != null) {
			removeFromBucket(removedTimestamp, value);
		}
	}

	private void removeFromBucket(long timestamp, V value) {
		Set<V> bucket = multiMap.get(timestamp);
		if (bucket != null && bucket.remove(value) && bucket.isEmpty()) {
			multiMap.remove(timestamp);
		}
	}

	/**
	 * Checks if the specified value is currently mapped.
	 *
	 * @param value the value to check for
	 * @return true if the value is present
	 */
	public synchronized boolean contains(V value) {
		return inverse.containsKey(value);
	}

	/**
	 * Gets the number of values currently mapped.
	 *
	 * @return the number of values
	 */
	public synchronized int size() {
		return inverse.size();
	}

	/**
	 * Clears all mapped values.
	 */
	public synchronized void clear() {
		multiMap.clear();
		inverse.clear();
	}

}
