package io.github.sillypuppyy.dogboard.feel

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Interaction timings for DogBoard's typing feel.
 */
object Timings {
    /** Hold time before the first long-press popup (accents, alternates) appears. */
    val LongPress: Duration = 375.milliseconds

    /** A second tap on shift starting within this time of the first tap's press turns on caps lock. */
    val CapsLockDoubleTap: Duration = 300.milliseconds

    /** Two spaces within this time insert a period (". "). */
    val DoubleSpacePeriod: Duration = 1200.milliseconds

    /** Hold time before delete starts repeating. */
    val DeleteFirstRepeat: Duration = 500.milliseconds

    /** Interval between repeated single-character deletes. */
    val DeleteCharInterval: Duration = 100.milliseconds

    /** After this many repeated deletes, delete switches to whole words. */
    const val DELETE_CHARS_BEFORE_WORDS = 20

    /** Interval between repeated word deletes. */
    val DeleteWordInterval: Duration = 350.milliseconds
}

/**
 * The schedule of a held delete key: which unit each repeat deletes and how long to wait before
 * the next one.
 */
object DeleteRepeat {
    enum class Unit { CHARACTER, WORD }

    data class Step(val unit: Unit, val delayAfter: Duration)

    /**
     * Returns the step for the [repeatIndex]-th repeat (0-based, not counting the initial press,
     * which always deletes one character).
     */
    fun step(repeatIndex: Int): Step {
        // the initial press counts as the first deleted character
        val charsDeletedSoFar = repeatIndex + 1
        return if (charsDeletedSoFar < Timings.DELETE_CHARS_BEFORE_WORDS) {
            Step(Unit.CHARACTER, Timings.DeleteCharInterval)
        } else {
            Step(Unit.WORD, Timings.DeleteWordInterval)
        }
    }
}
