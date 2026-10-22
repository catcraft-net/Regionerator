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
 * The outcome of compacting the flag database.
 *
 * @param sizeBefore the database file's size in bytes before compacting
 * @param sizeAfter the database file's size in bytes after compacting
 * @param complete false if compacting was cancelled, leaving the database as it was
 */
public record CompactResult(long sizeBefore, long sizeAfter, boolean complete) {}
