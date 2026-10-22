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

/**
 * The outcome of pruning the flag database.
 *
 * @param rowsChecked the number of rows examined
 * @param flagsRemoved the number of flags removed because the filter allowed it
 * @param recordsRemoved the number of deletion records removed for age
 * @param recordsDated the number of deletion records that had no deletion time and were given the current time
 * @param complete whether every row was examined, rather than pruning being cancelled
 */
public record PruneResult(long rowsChecked, long flagsRemoved, long recordsRemoved, long recordsDated, boolean complete) {
}
