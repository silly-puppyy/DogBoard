package io.github.sillypuppyy.dogboard.feel

import kotlin.random.Random

/**
 * What a key press should feel like, independent of how the device produces it.
 *
 * @property intensity strength, 0..1
 * @property sharpness 0 = soft, low thump; 1 = crisp, high tick
 */
data class HapticTap(val intensity: Float, val sharpness: Float)

/** Feedback classes keys fall into. */
enum class KeyFeedbackClass {
    TEXT,            // letters, digits, punctuation
    SEPARATOR,       // space and return
    DELETE,          // a single delete press
    DELETE_REPEAT,   // delete auto-repeating by character
    DELETE_RAPID,    // delete auto-repeating by word
    OTHER,           // shift, layer switches, emoji key and other function keys
    CANDIDATE,       // tapping a suggestion (sound only)
}

object Haptics {
    /** Base tap per feedback class; null means no haptic. */
    fun baseTap(feedbackClass: KeyFeedbackClass): HapticTap? = when (feedbackClass) {
        KeyFeedbackClass.TEXT -> HapticTap(0.51f, 0.31f)
        KeyFeedbackClass.SEPARATOR -> HapticTap(0.43f, 0.64f)
        KeyFeedbackClass.DELETE -> HapticTap(0.45f, 0.313f)
        KeyFeedbackClass.DELETE_REPEAT -> HapticTap(0.35f, 0.313f)
        KeyFeedbackClass.DELETE_RAPID -> HapticTap(0.40f, 0.313f)
        KeyFeedbackClass.OTHER -> HapticTap(0.49f, 0.229f)
        KeyFeedbackClass.CANDIDATE -> null
    }

    /** Presses closer together than this count as fast typing and get a little random variation. */
    const val FAST_TYPING_INTERVAL_MS = 700L

    /** Maximum relative intensity variation while typing fast (±5%). */
    const val FAST_TYPING_JITTER = 0.05f

    /**
     * The tap to play for a press of [feedbackClass], [msSinceLastPress] after the previous press
     * (null for the first press).
     */
    fun tapFor(feedbackClass: KeyFeedbackClass, msSinceLastPress: Long?, random: Random = Random): HapticTap? {
        val base = baseTap(feedbackClass) ?: return null
        val isFast = msSinceLastPress != null && msSinceLastPress < FAST_TYPING_INTERVAL_MS
        if (!isFast) return base
        val factor = 1f + (random.nextFloat() * 2f - 1f) * FAST_TYPING_JITTER
        return base.copy(intensity = (base.intensity * factor).coerceIn(0f, 1f))
    }

    /** Vibration frequency the reference tuning uses for a given sharpness. */
    fun frequencyHz(sharpness: Float): Double = 80.0 * Math.pow(2.875, sharpness.toDouble())
}
