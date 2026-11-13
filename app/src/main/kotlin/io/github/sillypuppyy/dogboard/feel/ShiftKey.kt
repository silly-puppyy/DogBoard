package io.github.sillypuppyy.dogboard.feel

import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard3.touch.ShiftKeyBehavior

/**
 * What a tap on shift does (iOS; settings-model.md §1):
 * a tap turns shift on for one letter, or off when it was on (also when the keyboard turned it on
 * by itself); a second tap whose press comes within the double-tap window of the first press
 * locks caps, if caps lock is enabled ("Enable Caps Lock"). When it isn't, the second tap is just
 * another toggle. Caps lock is released by one tap.
 */
object ShiftKey {
    fun next(
        old: InputShiftState,
        isDoubleTap: Boolean,
        behavior: ShiftKeyBehavior = ShiftKeyBehavior.CAPSLOCK_BY_DOUBLE_TAP,
        capsLockEnabled: Boolean = true,
    ): InputShiftState = when (behavior) {
        ShiftKeyBehavior.CAPSLOCK_BY_DOUBLE_TAP -> when {
            isDoubleTap && capsLockEnabled -> InputShiftState.CAPS_LOCK
            old == InputShiftState.UNSHIFTED -> InputShiftState.SHIFTED_MANUAL
            else -> InputShiftState.UNSHIFTED
        }
        ShiftKeyBehavior.CAPSLOCK_BY_CYCLE -> when (old) {
            InputShiftState.UNSHIFTED -> InputShiftState.SHIFTED_MANUAL
            InputShiftState.SHIFTED_MANUAL -> if (capsLockEnabled) InputShiftState.CAPS_LOCK else InputShiftState.UNSHIFTED
            InputShiftState.SHIFTED_AUTOMATIC -> InputShiftState.UNSHIFTED
            InputShiftState.CAPS_LOCK -> InputShiftState.UNSHIFTED
        }
    }
}
