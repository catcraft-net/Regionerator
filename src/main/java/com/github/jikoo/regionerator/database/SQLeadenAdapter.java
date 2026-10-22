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
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
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
	/** Size in bytes the write-ahead log is truncated to after a checkpoint. */
	private static final long JOURNAL_SIZE_LIMIT = 16L * 1024 * 1024;
	/** Flags copied per lock while bringing a compacted database up to date. */
	private static final int COMPACT_COPY_BATCH_SIZE = 500;
	/** Most flags written during compacting that may be copied in the final step, while the lock is held. */
	private static final int COMPACT_FINAL_COPY_MAX = 1_000;
	/** Most rounds of copying before the final step copies whatever remains. */
	private static final int COMPACT_COPY_ROUNDS = 10;

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
	private final @NotNull Path file;
	/** Guards the connection, which compacting replaces. */
	final @NotNull Object lock = new Object();
	@NotNull Connection database;
	private boolean closed;
	/** Flags written while compacting, which must be copied to the compacted database. Null when not compacting. */
	private @Nullable Set<String> written;
	private volatile @Nullable Statement maintenanceStatement;

	SQLeadenAdapter(@NotNull Logger logger, @NotNull Path file) throws SQLException {
		this.logger = logger;
		this.file = file;
		deleteCompactionFiles();
		this.database = open(file);
	}

	private @NotNull Connection open(@NotNull Path path) throws SQLException {
		Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
		try {
			setUp(connection);
			connection.setAutoCommit(false);
			return connection;
		} catch (SQLException e) {
			connection.close();
			throw e;
		}
	}

	/**
	 * Sets up a newly opened connection's schema. Called with auto-commit enabled.
	 *
	 * @param connection the connection
	 * @throws SQLException if a database error occurs
	 */
	void setUp(@NotNull Connection connection) throws SQLException {
		try (Statement st = connection.createStatement()) {
			// Write-ahead logging lets compacting read a consistent snapshot while flags are still written.
			st.execute("PRAGMA journal_mode=WAL");
			// The log is reused rather than shrunk; return its space after large batches of writes, such as pruning.
			st.execute("PRAGMA journal_size_limit=" + JOURNAL_SIZE_LIMIT);
			st.executeUpdate("CREATE TABLE IF NOT EXISTS `chunkdata`(`chunk_id` TEXT NOT NULL UNIQUE, `time` BIGINT NOT NULL)");
			// When a deletion record was made, so records can be pruned by age. Null for ordinary flags and for records
			// made before this was stored.
			if (!hasColumn(st, "deleted")) {
				st.executeUpdate("ALTER TABLE `chunkdata` ADD COLUMN `deleted` BIGINT");
			}
		}
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
		synchronized (lock) {
			closed = true;
			try {
				database.commit();
				database.close();
			} catch (SQLException e) {
				logger.log(Level.SEVERE, "Exception committing to and closing DB connection", e);
			}
		}
	}

	/**
	 * Records that flags are about to be written. Must be called while holding the lock.
	 *
	 * @param chunkIds the identifiers of the flags
	 */
	void recordWrites(@NotNull Iterable<String> chunkIds) {
		if (written != null) {
			chunkIds.forEach(written::add);
		}
	}

	@Override
	public void update(@NotNull Collection<ChunkFlagger.FlagData> flags) throws SQLException {
		synchronized (lock) {
			recordWrites(flags.stream().map(ChunkFlagger.FlagData::getChunkId).toList());
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
		synchronized (lock) {
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
		synchronized (lock) {
			try (Statement statement = database.createStatement()) {
				long lastRowId = queryLong(statement, "SELECT MAX(rowid) FROM chunkdata");
				database.commit();
				return lastRowId;
			}
		}
	}

	private @NotNull List<Row> readBatch(long afterRowId, long finalRowId) throws SQLException {
		synchronized (lock) {
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
		synchronized (lock) {
			try (Statement statement = database.createStatement();
					// Only remove rows that still hold the values that were examined.
					PreparedStatement delete = database.prepareStatement(
							"DELETE FROM chunkdata WHERE rowid=? AND chunk_id=? AND time=? AND deleted IS ?");
					PreparedStatement date = database.prepareStatement(
							"UPDATE chunkdata SET deleted=? WHERE rowid=? AND chunk_id=? AND deleted IS NULL")) {
				// Checked under the lock: memory is written back under the same lock.
				flagRemovals.removeIf(row -> filter.isHeld(row.chunkId()));
				for (List<Row> rows : List.of(flagRemovals, recordRemovals, undatedRecords)) {
					recordWrites(rows.stream().map(Row::chunkId).toList());
				}
				suspendDeletionRecords(statement);
				int flags = sum(executeDeletes(delete, flagRemovals));
				int records = sum(executeDeletes(delete, recordRemovals));
				for (Row row : undatedRecords) {
					date.setLong(1, now);
					date.setLong(2, row.rowId());
					date.setString(3, row.chunkId());
					date.addBatch();
				}
				int dated = sum(date.executeBatch());
				resumeDeletionRecords(statement);
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
				delete.setNull(4, Types.BIGINT);
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
	 * Stops removed rows being recorded as deleted chunks until {@link #resumeDeletionRecords(Statement)}. Used in the
	 * same transaction as the removals.
	 *
	 * @param statement a statement for the transaction
	 * @throws SQLException if a database error occurs
	 */
	void suspendDeletionRecords(@NotNull Statement statement) throws SQLException {
		// No triggers in this schema.
	}

	/**
	 * Resumes recording removed rows as deleted chunks.
	 *
	 * @param statement a statement for the transaction
	 * @throws SQLException if a database error occurs
	 * @see #suspendDeletionRecords(Statement)
	 */
	void resumeDeletionRecords(@NotNull Statement statement) throws SQLException {
		// No triggers in this schema.
	}

	@Override
	public @Nullable CompactResult compact(@NotNull BooleanSupplier cancelled) throws SQLException, IOException {
		Path compactFile = getCompactionFile("");
		long sizeBefore;
		synchronized (lock) {
			if (closed) {
				throw new SQLException("Database is closed");
			}
			DatabaseMetaData metaData = database.getMetaData();
			if (metaData.getDatabaseMajorVersion() == 3 && metaData.getDatabaseMinorVersion() < 27 || !isWriteAheadLogging()) {
				// VACUUM INTO was added in SQLite 3.27. Without write-ahead logging, copying would block flag writes.
				return null;
			}
			deleteCompactionFiles();
			try (Statement statement = database.createStatement()) {
				// Move logged writes into the database file so its size can be compared.
				statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
			}
			database.commit();
			sizeBefore = getSizeOnDisk();
			written = new HashSet<>();
		}

		boolean replaced = false;
		try {
			// Copy a snapshot without holding the lock. With write-ahead logging, flags are still written meanwhile.
			try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
					PreparedStatement vacuum = source.prepareStatement("VACUUM INTO ?")) {
				maintenanceStatement = vacuum;
				if (cancelled.getAsBoolean()) {
					return new CompactResult(sizeBefore, sizeBefore, false);
				}
				vacuum.setString(1, compactFile.toAbsolutePath().toString());
				vacuum.executeUpdate();
			} finally {
				maintenanceStatement = null;
			}

			try (Connection target = DriverManager.getConnection("jdbc:sqlite:" + compactFile.toAbsolutePath())) {
				target.setAutoCommit(false);
				try (Statement statement = target.createStatement()) {
					suspendDeletionRecords(statement);
				}

				// Copy flags written since the snapshot until few remain, so the final copy holds the lock briefly.
				for (int round = 0; round < COMPACT_COPY_ROUNDS; ++round) {
					if (cancelled.getAsBoolean()) {
						return new CompactResult(sizeBefore, sizeBefore, false);
					}
					Set<String> batch;
					synchronized (lock) {
						if (written.size() <= COMPACT_FINAL_COPY_MAX) {
							break;
						}
						batch = written;
						written = new HashSet<>();
					}
					copyCurrent(batch, target);
				}

				synchronized (lock) {
					if (closed || cancelled.getAsBoolean()) {
						return new CompactResult(sizeBefore, sizeBefore, false);
					}
					copyCurrent(written, target);
					written = null;
					try (Statement statement = target.createStatement()) {
						resumeDeletionRecords(statement);
					}
					target.commit();
					target.close();
					// VACUUM INTO does not sync its output.
					try (FileChannel channel = FileChannel.open(compactFile, StandardOpenOption.WRITE)) {
						channel.force(true);
					}

					try {
						database.commit();
						database.close();
						// A leftover log would be applied to the wrong file.
						for (String suffix : new String[] { "-wal", "-shm", "-journal" }) {
							if (Files.exists(getCompactionFile(suffix)) || Files.exists(file.resolveSibling(file.getFileName() + suffix))) {
								throw new IOException("Database log " + suffix + " remains after closing; not replacing database");
							}
						}
						Files.move(compactFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
						replaced = true;
						syncDirectory();
					} finally {
						if (database.isClosed()) {
							// The compacted database if it replaced the original, otherwise the original.
							database = open(file);
						}
					}
				}
			}
		} finally {
			synchronized (lock) {
				written = null;
			}
			if (!replaced) {
				deleteCompactionFiles();
			}
		}

		return new CompactResult(sizeBefore, getSizeOnDisk(), true);
	}

	private long getSizeOnDisk() throws IOException {
		Path log = file.resolveSibling(file.getFileName() + "-wal");
		return Files.size(file) + (Files.exists(log) ? Files.size(log) : 0);
	}

	private boolean isWriteAheadLogging() throws SQLException {
		boolean wal;
		try (Statement statement = database.createStatement();
				ResultSet rs = statement.executeQuery("PRAGMA journal_mode")) {
			wal = rs.next() && "wal".equalsIgnoreCase(rs.getString(1));
		}
		database.commit();
		return wal;
	}

	/**
	 * Copies flags' current values, and their deletion records, from the database to the compacted copy.
	 */
	private void copyCurrent(@NotNull Set<String> chunkIds, @NotNull Connection target) throws SQLException {
		List<String> keys = new ArrayList<>(chunkIds.size() * 2);
		for (String chunkId : chunkIds) {
			keys.add(chunkId);
			if (!DatabaseAdapter.isDeletionRecord(chunkId)) {
				// Removing a flag may have made a deletion record.
				keys.add(chunkId + DELETION_RECORD_SUFFIX);
			}
		}

		for (int start = 0; start < keys.size(); start += COMPACT_COPY_BATCH_SIZE) {
			List<String> batch = keys.subList(start, Math.min(keys.size(), start + COMPACT_COPY_BATCH_SIZE));
			List<Row> rows = new ArrayList<>(batch.size());
			synchronized (lock) {
				try (PreparedStatement select = database.prepareStatement("SELECT time, deleted FROM chunkdata WHERE chunk_id=?")) {
					for (String key : batch) {
						select.setString(1, key);
						try (ResultSet rs = select.executeQuery()) {
							if (rs.next()) {
								long time = rs.getLong(1);
								long deleted = rs.getLong(2);
								rows.add(new Row(0, key, time, rs.wasNull() ? null : deleted));
							} else {
								rows.add(null);
							}
						}
					}
				}
				database.commit();
			}

			try (PreparedStatement replace = target.prepareStatement("INSERT OR REPLACE INTO chunkdata(chunk_id,time,deleted) VALUES (?,?,?)");
					PreparedStatement delete = target.prepareStatement("DELETE FROM chunkdata WHERE chunk_id=?")) {
				for (int i = 0; i < batch.size(); ++i) {
					Row row = rows.get(i);
					if (row == null) {
						delete.setString(1, batch.get(i));
						delete.addBatch();
					} else {
						replace.setString(1, row.chunkId());
						replace.setLong(2, row.time());
						if (row.deleted() == null) {
							replace.setNull(3, Types.BIGINT);
						} else {
							replace.setLong(3, row.deleted());
						}
						replace.addBatch();
					}
				}
				delete.executeBatch();
				replace.executeBatch();
			}
		}
	}

	@Override
	public void interruptMaintenance() {
		Statement statement = maintenanceStatement;
		if (statement != null) {
			try {
				statement.cancel();
			} catch (SQLException e) {
				logger.log(Level.WARNING, "Unable to interrupt database maintenance", e);
			}
		}
	}

	private @NotNull Path getCompactionFile(@NotNull String suffix) {
		return file.resolveSibling(file.getFileName() + ".compact" + suffix);
	}

	private void deleteCompactionFiles() {
		for (String suffix : new String[] { "", "-journal", "-wal", "-shm" }) {
			try {
				Files.deleteIfExists(getCompactionFile(suffix));
			} catch (IOException e) {
				logger.log(Level.WARNING, "Unable to delete unfinished compacted database", e);
			}
		}
	}

	private void syncDirectory() {
		// Makes the replacement durable where directories can be synced; not possible on every platform.
		try (FileChannel channel = FileChannel.open(file.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
			channel.force(true);
		} catch (IOException ignored) {
		}
	}

	@Override
	public long getFreeBytes() throws SQLException {
		synchronized (lock) {
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
