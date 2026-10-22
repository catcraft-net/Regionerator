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
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.logging.Logger;

/**
 * Adapter for modern SQLite.
 */
public class SQLiteAdapter extends SQLeadenAdapter {

	/**
	 * Records a removed flag's last value, and when it was removed, as a deletion record.
	 *
	 * @see DatabaseAdapter#DELETION_RECORD_SUFFIX
	 */
	private static final String CREATE_DELETION_RECORD_TRIGGER = """
			CREATE TRIGGER chunkdataold
			AFTER DELETE ON chunkdata
			WHEN OLD.time NOT NULL AND substr(OLD.chunk_id, -4) <> '_old'
			BEGIN
			INSERT INTO chunkdata(chunk_id,time,deleted) VALUES (OLD.chunk_id || '_old', OLD.time, CAST(strftime('%s','now') AS INTEGER) * 1000)
			ON CONFLICT(chunk_id) DO UPDATE SET time=excluded.time, deleted=excluded.deleted;
			END""";
	private static final String DROP_DELETION_RECORD_TRIGGER = "DROP TRIGGER IF EXISTS chunkdataold";

	SQLiteAdapter(@NotNull Logger logger, @NotNull Connection database) throws SQLException {
		super(logger, database);

		// Set up triggers, replacing the trigger from earlier versions, which did not record when.
		try (Statement st = database.createStatement()) {
			st.executeUpdate(DROP_DELETION_RECORD_TRIGGER);
			st.executeUpdate(CREATE_DELETION_RECORD_TRIGGER);
			database.commit();
		} catch (SQLException e) {
			logger.severe("An exception setting up database trigger!");
			try {
				DatabaseMetaData metaData = database.getMetaData();
				logger.warning(String.format("SQLite driver: %s %s", metaData.getDriverName(), metaData.getDriverVersion()));
				logger.warning(String.format("SQLite database: %s %s", metaData.getDatabaseProductName(), metaData.getDatabaseProductVersion()));
			} catch (SQLException ignored) {
			}
			throw e;
		}
	}

	@Override
	void beforePruneWrite(@NotNull Statement statement) throws SQLException {
		// Pruned flags were not deleted chunks; don't record them. Restored in the same transaction.
		statement.executeUpdate(DROP_DELETION_RECORD_TRIGGER);
	}

	@Override
	void afterPruneWrite(@NotNull Statement statement) throws SQLException {
		statement.executeUpdate(CREATE_DELETION_RECORD_TRIGGER);
	}

	@Override
	public void update(@NotNull Collection<ChunkFlagger.FlagData> flags) throws SQLException {
		synchronized (database) {
			try (PreparedStatement upsert = database.prepareStatement("INSERT INTO chunkdata(chunk_id,time) VALUES (?,?) ON CONFLICT(chunk_id) DO UPDATE SET time=excluded.time WHERE "
					+ replaceCondition("excluded.time", "chunkdata.time"));
				PreparedStatement delete = database.prepareStatement("DELETE FROM chunkdata WHERE chunk_id=?")) {
				for (ChunkFlagger.FlagData data : flags) {
					if (data.getLastVisit() == Config.FLAG_DEFAULT) {
						delete.setString(1, data.getChunkId());
						delete.addBatch();
					} else {
						upsert.setString(1, data.getChunkId());
						upsert.setLong(2, data.getLastVisit());
						upsert.addBatch();
					}
				}
				delete.executeBatch();
				upsert.executeBatch();
				this.database.commit();
			}
		}
	}

}
