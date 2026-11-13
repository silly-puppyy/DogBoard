package io.github.sillypuppyy.dogboard.feel

/** Space-bar trackpad: drag sideways on space to move the cursor. */
object Trackpad {
    /** Sideways movement on space before it turns into a trackpad (about 2 mm). */
    const val START_DP = 12f

    /** Finger movement per one-character cursor step. */
    const val STEP_DP = 8f
}
