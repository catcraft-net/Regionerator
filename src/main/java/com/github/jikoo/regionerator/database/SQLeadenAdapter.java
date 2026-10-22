/*
 * Copyright (c) 2015-2021 by Jikoo.
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Adapter for old versions of SQLite.
 */
public class SQLeadenAdapter implements DatabaseAdapter {

	/** Rows examined per batch while pruning. */
	private static final int PRUNE_BATCH_SIZE = 1_000;
	/** Pause between pruning batches so other database use is not held up. */
	private static final long PRUNE_PAUSE_MILLIS = 5;

	/**
	 * Builds the SQL condition under which a new flag value replaces a stored one.
	 *
	 * <p>A later timestamp replaces an earlier one. The generated marker is the largest value but only means the chunk
	 * has not been visited yet, so any other value replaces it. A permanent flag is never replaced by the generated
	 * marker; only removing the flag clears it.
	 *
	 * @param newValue the SQL expression for the new value
	 * @param storedValue the SQL expression for the stored value
	 * @return the SQL condition
	 */
	static @NotNull String replaceCondition(@NotNull String newValue, @NotNull String storedValue) {
		return String.format("(%1$s>%2$s AND %2$s<>%4$d) OR (%2$s=%3$d AND %1$s<>%3$d)",
				newValue, storedValue, Config.FLAG_GENERATED, Config.FLAG_ETERNAL);
	}

	final @NotNull Logger logger;
	final @NotNull Connection database;

	SQLeadenAdapter(@NotNull Logger logger, @NotNull Connection database) throws SQLException {
		this.logger = logger;
		this.database = database;

		// Set up database
		try (Statement st = database.createStatement()) {
			st.executeUpdate("CREATE TABLE IF NOT EXISTS `chunkdata`(`chunk_id` TEXT NOT NULL UNIQUE, `time` BIGINT NOT NULL)");
			// When a deletion record was made, so records can be pruned by age. Null for ordinary flags and for records
			// made before this was stored.
			if (!hasColumn(st, "deleted")) {
				st.executeUpdate("ALTER TABLE `chunkdata` ADD COLUMN `deleted` BIGINT");
			}
		}

		database.setAutoCommit(false);
	}

	private static boolean hasColumn(@NotNull Statement statement, @NotNull String column) throws SQLException {
		try (ResultSet rs = statement.executeQuery("PRAGMA table_info(`chunkdata`)")) {
			while (rs.next()) {
				if (column.equals(rs.getString("name"))) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void close() {
		synchronized (database) {
			try {
				database.commit();
				database.close();
			} catch (SQLException e) {
				logger.log(Level.SEVERE, "Exception committing to and closing DB connection", e);
			}
		}
	}

	@Override
	public void update(@NotNull Collection<ChunkFlagger.FlagData> flags) throws SQLException {
		synchronized (database) {
			try (PreparedStatement boyIWishThisWasAnUpsert = database.prepareStatement(
					"INSERT OR REPLACE INTO chunkdata(chunk_id,time) VALUES (?1, COALESCE((SELECT CASE WHEN "
							+ replaceCondition("?2", "time") + " THEN ?2 ELSE time END FROM chunkdata WHERE chunk_id=?1), ?2))");
				PreparedStatement deleteForeverBecauseReplaceEqualsDeleteThenInsertFrownyFace = database.prepareStatement("DELETE FROM chunkdata WHERE chunk_id=?")) {
				for (ChunkFlagger.FlagData data : flags) {
					if (data.getLastVisit() == Config.FLAG_DEFAULT) {
						deleteForeverBecauseReplaceEqualsDeleteThenInsertFrownyFace.setString(1, data.getChunkId());
						deleteForeverBecauseReplaceEqualsDeleteThenInsertFrownyFace.addBatch();
					} else {
						boyIWishThisWasAnUpsert.setString(1, data.getChunkId());
						boyIWishThisWasAnUpsert.setLong(2, data.getLastVisit());
						boyIWishThisWasAnUpsert.addBatch();
					}
				}
				deleteForeverBecauseReplaceEqualsDeleteThenInsertFrownyFace.executeBatch();
				boyIWishThisWasAnUpsert.executeBatch();
				this.database.commit();
			}
		}
	}

	@Override
	public long get(@NotNull String identifier) throws SQLException {
		synchronized (database) {
			if (database.isClosed()) {
				return Config.FLAG_OH_NO;
			}

			try (PreparedStatement st = database.prepareStatement("SELECT time FROM chunkdata WHERE chunk_id=?")) {
				st.setString(1, identifier);
				try (ResultSet rs = st.executeQuery()) {
					if (rs.next()) {
						return rs.getLong(1);
					} else {
						return Config.FLAG_DEFAULT;
					}
				}
			}
		}
	}

	@Override
	public @NotNull PruneResult prune(@NotNull FlagFilter filter, long deletionRecordCutoff, @NotNull BooleanSupplier cancelled)
			throws SQLException, InterruptedException {
		long now = System.currentTimeMillis();
		long rowsChecked = 0;
		long flagsRemoved = 0;
		long recordsRemoved = 0;
		long recordsDated = 0;
		long lastRowId = 0;
		// Rows added or replaced later hold new flags, which are never prunable. Stopping at the current last row also
		// keeps pruning from revisiting rows that INSERT OR REPLACE moves to the end while it runs.
		long finalRowId = getLastRowId();

		while (!cancelled.getAsBoolean()) {
			List<Row> batch = readBatch(lastRowId, finalRowId);
			if (batch.isEmpty()) {
				return new PruneResult(rowsChecked, flagsRemoved, recordsRemoved, recordsDated, true);
			}
			lastRowId = batch.get(batch.size() - 1).rowId();
			rowsChecked += batch.size();

			// Decide without holding the lock; the filter may read region files.
			List<Row> flagRemovals = new ArrayList<>();
			List<Row> recordRemovals = new ArrayList<>();
			List<Row> undatedRecords = new ArrayList<>();
			for (Row row : batch) {
				if (DatabaseAdapter.isDeletionRecord(row.chunkId())) {
					if (row.deleted() == null) {
						undatedRecords.add(row);
					} else if (row.deleted() < deletionRecordCutoff) {
						recordRemovals.add(row);
					}
				} else if (filter.canRemove(row.chunkId(), row.time())) {
					flagRemovals.add(row);
				}
			}

			if (!flagRemovals.isEmpty()) {
				Set<String> confirmed = new HashSet<>(filter.confirm(flagRemovals.stream().map(Row::chunkId).toList()));
				flagRemovals.removeIf(row -> !confirmed.contains(row.chunkId()));
			}

			if (!flagRemovals.isEmpty() || !recordRemovals.isEmpty() || !undatedRecords.isEmpty()) {
				int[] counts = writeBatch(filter, flagRemovals, recordRemovals, undatedRecords, now);
				flagsRemoved += counts[0];
				recordsRemoved += counts[1];
				recordsDated += counts[2];
			}

			Thread.sleep(PRUNE_PAUSE_MILLIS);
		}

		return new PruneResult(rowsChecked, flagsRemoved, recordsRemoved, recordsDated, false);
	}

	private long getLastRowId() throws SQLException {
		synchronized (database) {
			try (Statement statement = database.createStatement()) {
				long lastRowId = queryLong(statement, "SELECT MAX(rowid) FROM chunkdata");
				database.commit();
				return lastRowId;
			}
		}
	}

	private @NotNull List<Row> readBatch(long afterRowId, long finalRowId) throws SQLException {
		synchronized (database) {
			List<Row> batch = new ArrayList<>(PRUNE_BATCH_SIZE);
			try (PreparedStatement st = database.prepareStatement(
					"SELECT rowid, chunk_id, time, deleted FROM chunkdata WHERE rowid>? AND rowid<=? ORDER BY rowid LIMIT ?")) {
				st.setLong(1, afterRowId);
				st.setLong(2, finalRowId);
				st.setInt(3, PRUNE_BATCH_SIZE);
				try (ResultSet rs = st.executeQuery()) {
					while (rs.next()) {
						long rowId = rs.getLong(1);
						String chunkId = rs.getString(2);
						long time = rs.getLong(3);
						long deleted = rs.getLong(4);
						batch.add(new Row(rowId, chunkId, time, rs.wasNull() ? null : deleted));
					}
				}
			}
			// End the read transaction rather than holding it until the next write.
			database.commit();
			return batch;
		}
	}

	private int @NotNull [] writeBatch(
			@NotNull FlagFilter filter,
			@NotNull List<Row> flagRemovals,
			@NotNull List<Row> recordRemovals,
			@NotNull List<Row> undatedRecords,
			long now) throws SQLException {
		synchronized (database) {
			try (Statement statement = database.createStatement();
					// Only remove rows that still hold the values that were examined.
					PreparedStatement delete = database.prepareStatement(
							"DELETE FROM chunkdata WHERE rowid=? AND chunk_id=? AND time=? AND deleted IS ?");
					PreparedStatement date = database.prepareStatement(
							"UPDATE chunkdata SET deleted=? WHERE rowid=? AND chunk_id=? AND deleted IS NULL")) {
				// Checked under the lock: memory is written back under the same lock.
				flagRemovals.removeIf(row -> filter.isHeld(row.chunkId()));
				beforePruneWrite(statement);
				int flags = sum(executeDeletes(delete, flagRemovals));
				int records = sum(executeDeletes(delete, recordRemovals));
				for (Row row : undatedRecords) {
					date.setLong(1, now);
					date.setLong(2, row.rowId());
					date.setString(3, row.chunkId());
					date.addBatch();
				}
				int dated = sum(date.executeBatch());
				afterPruneWrite(statement);
				database.commit();
				return new int[] { flags, records, dated };
			} catch (SQLException e) {
				database.rollback();
				throw e;
			}
		}
	}

	private static int @NotNull [] executeDeletes(@NotNull PreparedStatement delete, @NotNull List<Row> rows)
			throws SQLException {
		for (Row row : rows) {
			delete.setLong(1, row.rowId());
			delete.setString(2, row.chunkId());
			delete.setLong(3, row.time());
			if (row.deleted() == null) {
				delete.setNull(4, java.sql.Types.BIGINT);
			} else {
				delete.setLong(4, row.deleted());
			}
			delete.addBatch();
		}
		return delete.executeBatch();
	}

	private static int sum(int @NotNull [] counts) {
		int total = 0;
		for (int count : counts) {
			if (count > 0) {
				total += count;
			}
		}
		return total;
	}

	/**
	 * Called in the same transaction before pruning removes rows, while holding the database lock.
	 *
	 * @param statement a statement for the transaction
	 * @throws SQLException if a database error occurs
	 */
	void beforePruneWrite(@NotNull Statement statement) throws SQLException {
		// No triggers in this schema.
	}

	/**
	 * Called in the same transaction after pruning removes rows, while holding the database lock.
	 *
	 * @param statement a statement for the transaction
	 * @throws SQLException if a database error occurs
	 */
	void afterPruneWrite(@NotNull Statement statement) throws SQLException {
		// No triggers in this schema.
	}

	@Override
	public long getFreeBytes() throws SQLException {
		synchronized (database) {
			try (Statement statement = database.createStatement()) {
				long freePages = queryLong(statement, "PRAGMA freelist_count");
				long pageSize = queryLong(statement, "PRAGMA page_size");
				database.commit();
				return freePages * pageSize;
			}
		}
	}

	private static long queryLong(@NotNull Statement statement, @NotNull String sql) throws SQLException {
		try (ResultSet rs = statement.executeQuery(sql)) {
			return rs.next() ? rs.getLong(1) : 0;
		}
	}

	private record Row(long rowId, @NotNull String chunkId, long time, @Nullable Long deleted) {}

}
