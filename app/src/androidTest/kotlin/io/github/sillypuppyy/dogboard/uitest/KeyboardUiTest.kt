package io.github.sillypuppyy.dogboard.uitest

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DogBoard on a device, end to end (tier 2: emulators locally, real phones on Firebase Test Lab): typing with real
 * timings lands the expected text, field types behave, the panels open without a crash. Cases from
 * tools/test/type_test.py, which checks the same on the emulator over adb. Screenshots: files/uitest/.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardUiTest {
    private val kb = KeyboardDriver()

    @Before fun setUp() {
        kb.selectDogBoard()
        kb.resetLearningOnce()
    }
    @After fun tearDown() = kb.close()

    private fun typed(text: String, field: String? = null): String {
        val area = kb.open(field)
        kb.waitForEngine()
        kb.type(area, text)
        return kb.text()
    }

    @Test fun plainWords() = assertEquals("Hello world", typed("hello world"))

    @Test fun autocorrectsAtTheStart() = assertEquals("The x", typed("teh x"))

    @Test fun missingApostrophe() = assertEquals("I don't know", typed("i dont know"))

    @Test fun correctionBeforePunctuation() = assertEquals("The)", typed("teh{123})"))

    @Test fun numberSuffix() = assertEquals("The 1st x", typed("the {123}1{123}sr x"))

    @Test fun nameFieldCapitalisesWords() = assertEquals("John Smith", typed("john smith", "name"))

    @Test fun capsField() = assertEquals("ABC", typed("abc", "caps"))

    @Test fun panelsOpenAndClose() {
        val area = kb.open()
        kb.screenshot("keys")
        for (p in listOf("clipboard", "emoji", "tools")) {
            kb.panel(p)
            kb.screenshot("panel-$p")
        }
        kb.panel("")
        // the keys still type after the panels
        kb.type(area, "ok")
        assertTrue(kb.text().equals("ok", ignoreCase = true))
        assertTrue("DogBoard is still the keyboard", KeyboardDriver.IME in kb.shell("settings get secure default_input_method"))
    }
}
