package io.github.sillypuppyy.dogboard.feel

/** Which vibration a key press plays. */
enum class HapticStyle {
    /** The phone's own keyboard click (Android's tuned KEYBOARD_PRESS feedback). */
    PHONE,

    /** DogBoard's soft tap ([Haptics] values, an intensity/sharpness envelope). */
    SOFT,
}
