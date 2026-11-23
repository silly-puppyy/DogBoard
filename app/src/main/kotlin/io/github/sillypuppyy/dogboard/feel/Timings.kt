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
 * The timings in effect: the [Timings] defaults unless the user changed them in Advanced ›
 * Fine-tuning (ImeController keeps these in step with the preferences).
 */
object FeelSettings {
    @Volatile var doubleSpaceMs: Long = Timings.DoubleSpacePeriod.inWholeMilliseconds
    @Volatile var deleteFirstMs: Long = Timings.DeleteFirstRepeat.inWholeMilliseconds
    @Volatile var deleteCharMs: Long = Timings.DeleteCharInterval.inWholeMilliseconds
    /** 0: never switch to whole words. */
    @Volatile var deleteWordsAfter: Int = Timings.DELETE_CHARS_BEFORE_WORDS
    @Volatile var deleteWordMs: Long = Timings.DeleteWordInterval.inWholeMilliseconds
    /** Settings › Gestures: holding space turns it into a trackpad. */
    @Volatile var trackpadEnabled: Boolean = true
    /** Holding the space bar opens the language list (spaceHold "language"); the trackpad is then off. */
    @Volatile var spaceHoldLanguages: Boolean = false
    /** Settings › Gestures: a slide left from delete selects whole words to delete (Gboard). */
    @Volatile var deleteSlide: Boolean = true
    /** Settings › Gestures: a quick sideways swipe on space moves the cursor without the hold (Gboard). */
    @Volatile var spaceQuickDrag: Boolean = true

    /** Swipe typing (Gestures page; on by default, as iOS and Gboard). */
    @Volatile var swipeEnabled: Boolean = true
    /** The chosen layout has a swipe recogniser (only English QWERTY so far: Dvorak, Colemak and the others type by taps). */
    @Volatile var swipeLayout: Boolean = true

    /** Draw the swipe's trail (Gboard does, iOS doesn't; user 2026-11-17: draw it). */
    @Volatile var swipeTrail: Boolean = true
    @Volatile var trackpadHoldMs: Long = Trackpad.HOLD_MS
    @Volatile var trackpadStepDp: Float = Trackpad.STEP_DP
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
        val wordsAfter = FeelSettings.deleteWordsAfter
        return if (wordsAfter == 0 || charsDeletedSoFar < wordsAfter) {
            Step(Unit.CHARACTER, FeelSettings.deleteCharMs.milliseconds)
        } else {
            Step(Unit.WORD, FeelSettings.deleteWordMs.milliseconds)
        }
    }
}
