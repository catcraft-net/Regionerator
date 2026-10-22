/*
 * Copyright (c) 2015-2026 by Jikoo.
 *
 * Regionerator is licensed under a Creative Commons
 * Attribution-ShareAlike 4.0 International License.
 *
 * You should have received a copy of the license along with this
 * work. If not, see <http://creativecommons.org/licenses/by-sa/4.0/>.
 */

package com.github.jikoo.regionerator;

import com.github.jikoo.regionerator.database.PruneResult;
import com.github.jikoo.regionerator.world.WorldInfo;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Background upkeep of the flag database.
 *
 * <p>Tasks run one at a time on a dedicated thread, so they never hold up the server thread or the flag cache.
 */
public class FlagMaintenance {

	/** Unused space below which compacting is not suggested. */
	private static final long SUGGEST_COMPACT_BYTES = 16L * 1024 * 1024;

	private final @NotNull Regionerator plugin;
	private final @NotNull ExecutorService executor;
	private final @NotNull AtomicBoolean busy = new AtomicBoolean();
	private final @NotNull AtomicBoolean cancelled = new AtomicBoolean();

	FlagMaintenance(@NotNull Regionerator plugin) {
		this.plugin = plugin;
		this.executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "Regionerator Flag Maintenance");
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * Starts removing flags that no longer have any effect. Must be called from the server thread.
	 *
	 * @param sender the sender to report to, or {@code null} for an automatic run
	 * @return false if maintenance is already running
	 */
	public boolean prune(@Nullable CommandSender sender) {
		if (!busy.compareAndSet(false, true)) {
			if (sender != null) {
				sender.sendMessage("Flag database maintenance is already running.");
			}
			return false;
		}

		// World data access is set up on the server thread.
		Map<String, WorldInfo> worlds = new HashMap<>();
		for (World world : plugin.getServer().getWorlds()) {
			if (plugin.config().isEnabled(world.getName())) {
				worlds.put(world.getName(), plugin.getWorldManager().getWorld(world));
			}
		}
		long deletionRecordCutoff = System.currentTimeMillis() - plugin.config().getDeletionRecordRetentionMillis();

		if (sender != null) {
			sender.sendMessage("Pruning flag database in the background.");
		}
		plugin.debug(DebugLevel.LOW, () -> "Pruning flag database.");

		return submit(sender, () -> {
			long start = System.nanoTime();
			ChunkFlagger flagger = plugin.getFlagger();
			FlagPruner pruner = new FlagPruner(worlds, flagger::isHeld, plugin.getLogger());
			PruneResult result = flagger.prune(pruner, deletionRecordCutoff, cancelled::get);
			double seconds = (System.nanoTime() - start) / 1_000_000_000D;

			report(sender, String.format(
					"%s flag database in %.1fs: checked %d rows, removed %d flags that no longer had any effect and %d old deletion records.",
					result.complete() ? "Pruned" : "Stopped pruning",
					seconds,
					result.rowsChecked(),
					result.flagsRemoved(),
					result.recordsRemoved()));
			// Flags that changed or were in use after being confirmed are kept, so these may exceed flags removed.
			plugin.debug(DebugLevel.LOW, () -> String.format(
					"Confirmed %d expired visits and %d flags for chunks that no longer exist.",
					pruner.getExpiredFlags(),
					pruner.getMissingChunkFlags()));
			if (result.recordsDated() > 0) {
				report(sender, String.format(
						"Started the retention period for %d deletion records made before deletion times were stored.",
						result.recordsDated()));
			}
			suggestCompacting(sender);
		});
	}

	private void suggestCompacting(@Nullable CommandSender sender) throws Exception {
		long freeBytes = plugin.getFlagger().getFreeBytes();
		if (freeBytes < SUGGEST_COMPACT_BYTES) {
			return;
		}
		File file = plugin.getFlagger().getDatabaseFile();
		report(sender, String.format(
				"The flag database file is %d MB, of which %d MB is unused. Run /regionerator compact to release it.",
				file.length() / (1024 * 1024), freeBytes / (1024 * 1024)));
	}

	private boolean submit(@Nullable CommandSender sender, @NotNull MaintenanceTask task) {
		try {
			executor.execute(() -> {
				try {
					task.run();
				} catch (InterruptedException e) {
					report(sender, "Flag database maintenance was interrupted.");
				} catch (Exception e) {
					plugin.getLogger().log(Level.SEVERE, "Flag database maintenance failed", e);
					report(sender, "Flag database maintenance failed. Check the console for details.");
				} finally {
					busy.set(false);
				}
			});
			return true;
		} catch (RejectedExecutionException e) {
			busy.set(false);
			return false;
		}
	}

	private void report(@Nullable CommandSender sender, @NotNull String message) {
		plugin.getLogger().info(message);
		if (sender == null || sender instanceof ConsoleCommandSender) {
			return;
		}
		try {
			plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(message));
		} catch (IllegalPluginAccessException e) {
			// Plugin disabling.
		}
	}

	/**
	 * Stops any running maintenance and waits briefly for it to finish.
	 */
	void shutdown() {
		cancelled.set(true);
		executor.shutdownNow();
		try {
			if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
				plugin.getLogger().warning("Flag database maintenance did not stop in time.");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@FunctionalInterface
	private interface MaintenanceTask {
		void run() throws Exception;
	}

}
