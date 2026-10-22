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
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Adapter for old versions of SQLite.
 */
public class SQLeadenAdapter implements DatabaseAdapter {

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
		}

		database.setAutoCommit(false);
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

}
