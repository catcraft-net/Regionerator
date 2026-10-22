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
 * Paces long-running maintenance so it does not hold up the server, and decides when it must stop.
 */
public interface Pacer {

	/**
	 * Gets whether maintenance must stop, for example because the plugin is disabling.
	 *
	 * @return true if maintenance must stop
	 */
	boolean isStopping();

	/**
	 * Waits between steps of maintenance so the server can recover.
	 *
	 * @throws InterruptedException if interrupted while waiting
	 */
	void pause() throws InterruptedException;

}
