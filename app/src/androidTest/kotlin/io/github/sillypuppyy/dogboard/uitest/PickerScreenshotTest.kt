package io.github.sillypuppyy.dogboard.uitest

import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The system keyboard picker with DogBoard's entry, as a screenshot (files/uitest/picker.png): how the picker draws
 * our subtype icon (user 2026-11-20: a plain circle, then a blank spot, where FUTO shows its icon). The test runs in
 * the keyboard's process, and the current keyboard may open the picker. Only with `-e picker 1`.
 */
@RunWith(AndroidJUnit4::class)
class PickerScreenshotTest {
    @Test fun picker() {
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        if (args.getString("picker") != "1") return
        val kb = KeyboardDriver()
        kb.selectDogBoard()
        // the field opened if the keyboard comes up; the picker only needs DogBoard to be the current keyboard
        runCatching { kb.open() }
        val imm = kb.instrumentation.targetContext.getSystemService(InputMethodManager::class.java)
        kb.instrumentation.runOnMainSync { imm.showInputMethodPicker() }
        SystemClock.sleep(2500)
        kb.screenshot("picker")
        kb.shell("input keyevent 4")
    }
}
