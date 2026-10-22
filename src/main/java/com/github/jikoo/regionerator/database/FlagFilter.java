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

import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Decides which stored flags can be removed when pruning.
 *
 * <p>For each batch of rows, {@link #canRemove(String, long)} picks flags, {@link #confirm(Collection)} checks them
 * again just before removal, and {@link #isHeld(String)} is checked for each while the database is locked.
 */
@FunctionalInterface
public interface FlagFilter {

	/**
	 * Gets whether a stored flag no longer has any effect and can be removed.
	 *
	 * <p>Called without holding the database lock, so implementations may perform I/O. Never called for deletion
	 * records, which are pruned by age.
	 *
	 * @param chunkId the chunk identifier
	 * @param time the stored flag value
	 * @return true if the flag can be removed
	 */
	boolean canRemove(@NotNull String chunkId, long time);

	/**
	 * Confirms that flags allowed by {@link #canRemove(String, long)} can still be removed. Called once per batch,
	 * without holding the database lock, immediately before the batch's flags are removed.
	 *
	 * @param chunkIds the identifiers allowed since the previous call
	 * @return the identifiers that can still be removed
	 */
	default @NotNull Collection<String> confirm(@NotNull Collection<String> chunkIds) {
		return chunkIds;
	}

	/**
	 * Gets whether a flag is currently held in memory. Held flags are kept: memory is only written back when its
	 * value changes, so removing a stored value that memory also holds could lose it.
	 *
	 * <p>Called while the database is locked, immediately before the flag is removed. Must be fast and must not use
	 * the database.
	 *
	 * @param chunkId the chunk identifier
	 * @return true if the flag must be kept
	 */
	default boolean isHeld(@NotNull String chunkId) {
		return false;
	}

}
