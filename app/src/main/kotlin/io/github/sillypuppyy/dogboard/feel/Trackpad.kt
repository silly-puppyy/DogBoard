package io.github.sillypuppyy.dogboard.feel

/** Space-bar trackpad: hold space, then slide to move the cursor (iOS one-finger rule). */
object Trackpad {
    /** Hold time on space before it becomes a trackpad. */
    const val HOLD_MS = 375L

    /** Movement allowed during the hold; moving farther first cancels it (the tap stays a space). */
    const val ALLOWABLE_MOVEMENT_DP = 16f

    /** Finger movement per one-character cursor step (not from iOS; tune by feel). */
    const val STEP_DP = 8f
}
