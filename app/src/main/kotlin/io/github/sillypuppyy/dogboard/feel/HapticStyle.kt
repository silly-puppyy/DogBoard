package io.github.sillypuppyy.dogboard.feel

/** Which vibration a key press plays. */
enum class HapticStyle {
    /** The phone's own keyboard click (Android's tuned KEYBOARD_PRESS feedback). */
    PHONE,

    /** DogBoard's soft tap ([Haptics] values, an intensity/sharpness envelope). */
    SOFT,

    /**
     * iPhone-like: the phone's tuned click primitive (short and braked, as a Taptic click feels),
     * scaled by iOS's per-key strengths. Playing iOS's waveform literally (SOFT) felt soft on a
     * Pixel, whose motor resonates far above iOS's 111 Hz key tap (user, 2026-11-17).
     */
    CRISP,
}
