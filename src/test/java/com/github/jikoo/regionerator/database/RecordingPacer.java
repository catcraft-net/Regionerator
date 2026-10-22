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
 * A pacer that records pauses instead of waiting.
 */
public class RecordingPacer implements Pacer {

  /** Number of pauses so far. */
  public int pauses;
  /** Whether maintenance must stop. */
  public boolean stopping;
  /** Starts stopping after this many pauses, if not negative. */
  public int stopAfterPauses = -1;
  /** Whether pausing is interrupted. */
  public boolean interrupted;

  @Override
  public boolean isStopping() {
    return stopping;
  }

  @Override
  public void pause() throws InterruptedException {
    ++pauses;
    if (interrupted) {
      throw new InterruptedException("Interrupted for test");
    }
    if (stopAfterPauses >= 0 && pauses >= stopAfterPauses) {
      stopping = true;
    }
  }

}
