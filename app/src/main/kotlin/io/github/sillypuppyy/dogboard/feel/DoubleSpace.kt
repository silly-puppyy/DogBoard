package io.github.sillypuppyy.dogboard.feel

/**
 * Double-space period: a second space typed soon after the first turns "word " into "word. ".
 */
object DoubleSpace {
    private val CLOSERS = setOf(')', ']', '}', '"', '\'', '”', '’', '»')

    /**
     * Returns true when the space about to be typed should replace the previous space with ". ".
     *
     * [textBefore] is the text before the cursor, [msSinceLastSpace] the time since the previous
     * space key press, or null when anything else was typed or deleted since then.
     */
    fun shouldInsertPeriod(textBefore: CharSequence, msSinceLastSpace: Long?): Boolean {
        if (msSinceLastSpace == null || msSinceLastSpace > FeelSettings.doubleSpaceMs) return false
        val n = textBefore.length
        if (n < 2 || textBefore[n - 1] != ' ') return false
        val c = textBefore[n - 2]
        return c.isLetterOrDigit() || c in CLOSERS
    }
}
