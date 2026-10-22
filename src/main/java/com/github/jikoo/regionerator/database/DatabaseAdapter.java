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
import com.github.jikoo.regionerator.Regionerator;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.util.Collection;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.NotNull;

/**
 * Interface defining behavior for interacting with a database.
 * <p>
 * All methods should expect to be called from multiple threads.
 */
public interface DatabaseAdapter {

	/**
	 * Suffix of deletion records. When a chunk's flag is removed, because the chunk was deleted or unflagged by
	 * command, its last value is kept under the chunk's identifier plus this suffix. Only {@code /regionerator check}
	 * reads these records, to show when a deleted chunk was last visited; deletion never does.
	 */
	String DELETION_RECORD_SUFFIX = "_old";

	void close();

	void update(@NotNull Collection<ChunkFlagger.FlagData> flags) throws Exception;

	long get(@NotNull String identifier) throws Exception;

	/**
	 * Removes stored flags that no longer have any effect.
	 *
	 * <p>Ordinary flags are removed when the filter allows it. Deletion records are removed once they were made before
	 * the cutoff; records from before deletion times were stored are given the current time first. Rows are examined
	 * in small batches and the database is only locked while a batch is read or written, so pruning does not hold up
	 * other database use. A row is only removed if it still holds the value that was examined. Rows added after
	 * pruning starts hold new flags and are not examined.
	 *
	 * @param filter decides which ordinary flags can be removed
	 * @param deletionRecordCutoff deletion records made before this time are removed
	 * @param cancelled checked between batches to stop early
	 * @return the outcome
	 * @throws Exception if the database cannot be read or written
	 */
	@NotNull PruneResult prune(@NotNull FlagFilter filter, long deletionRecordCutoff, @NotNull BooleanSupplier cancelled)
			throws Exception;

	/**
	 * Gets the number of bytes in the database file that hold no data. Compacting the database releases them.
	 *
	 * @return the unused bytes
	 * @throws Exception if the database cannot be read
	 */
	long getFreeBytes() throws Exception;

	/**
	 * Gets whether a chunk identifier belongs to a deletion record.
	 *
	 * @param chunkId the chunk identifier
	 * @return true if the identifier is a deletion record
	 * @see #DELETION_RECORD_SUFFIX
	 */
	static boolean isDeletionRecord(@NotNull String chunkId) {
		return chunkId.endsWith(DELETION_RECORD_SUFFIX);
	}

	static @NotNull DatabaseAdapter getAdapter(@NotNull Regionerator plugin) throws Exception {
		Class.forName("org.sqlite.JDBC");

		Connection connection = DriverManager.getConnection("jdbc:sqlite:" + plugin.getDataFolder().getAbsolutePath() + "/data.db");
		DatabaseMetaData metaData = connection.getMetaData();

		if (metaData.getDatabaseMajorVersion() < 3 || metaData.getDatabaseMajorVersion() == 3 && metaData.getDatabaseMinorVersion() < 24) {
			// Terrible SQLite
			return new SQLeadenAdapter(plugin.getLogger(), connection);
		}

		return new SQLiteAdapter(plugin.getLogger(), connection);

	}

}
