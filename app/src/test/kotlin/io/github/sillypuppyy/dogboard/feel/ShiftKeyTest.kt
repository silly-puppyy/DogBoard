package io.github.sillypuppyy.dogboard.feel

import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState.CAPS_LOCK
import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState.SHIFTED_AUTOMATIC
import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState.SHIFTED_MANUAL
import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState.UNSHIFTED
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ShiftKeyTest : FunSpec({
    test("a tap turns shift on, or off when it was on (also when auto-shifted)") {
        ShiftKey.next(UNSHIFTED, isDoubleTap = false) shouldBe SHIFTED_MANUAL
        ShiftKey.next(SHIFTED_MANUAL, isDoubleTap = false) shouldBe UNSHIFTED
        ShiftKey.next(SHIFTED_AUTOMATIC, isDoubleTap = false) shouldBe UNSHIFTED
    }
    test("a double tap locks caps; one tap releases it") {
        ShiftKey.next(SHIFTED_MANUAL, isDoubleTap = true) shouldBe CAPS_LOCK
        ShiftKey.next(CAPS_LOCK, isDoubleTap = false) shouldBe UNSHIFTED
    }
    test("with caps lock disabled a double tap is just a second toggle") {
        ShiftKey.next(SHIFTED_MANUAL, isDoubleTap = true, capsLockEnabled = false) shouldBe UNSHIFTED
        ShiftKey.next(UNSHIFTED, isDoubleTap = true, capsLockEnabled = false) shouldBe SHIFTED_MANUAL
    }
    test("the window default is iOS's 300 ms") {
        Timings.CapsLockDoubleTap.inWholeMilliseconds shouldBe 300L
    }
})
