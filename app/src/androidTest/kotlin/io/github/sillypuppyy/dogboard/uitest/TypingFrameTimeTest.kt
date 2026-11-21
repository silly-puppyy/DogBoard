package io.github.sillypuppyy.dogboard.uitest

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How smoothly the keyboard draws while typing (user 2026-11-21: sounds bunch up, typing feels late): types sentences
 * through the real keyboard at the driver's realistic tap timing, then logs the keyboard process's frame statistics
 * (dumpsys gfxinfo: frames, janky share, 50th/90th/99th percentile) as FRAMETIME lines. Meant for slow phones
 * (Test Lab, Galaxy A10); the emulator renders in software and is slow by itself.
 */
@RunWith(AndroidJUnit4::class)
class TypingFrameTimeTest {
    private val kb = KeyboardDriver()

    @Before fun setUp() { kb.selectDogBoard() }
    @After fun tearDown() { kb.close() }

    @Test fun frameTimesWhileTyping() {
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val area = kb.open()
        kb.waitForEngine()
        kb.type(area, "warm up ")
        kb.shell("dumpsys gfxinfo $pkg reset")
        for (s in listOf("i will be there in a few minutes ", "can you send me the file when you get a chance ", "thanks for the help yesterday "))
            kb.type(area, s)
        val stats = kb.shell("dumpsys gfxinfo $pkg").lines()
            .filter { l -> listOf("Total frames rendered", "Janky frames:", "50th percentile", "90th percentile", "99th percentile").any { l.trim().startsWith(it) } }
            .joinToString("; ") { it.trim() }
        android.util.Log.i("DogBoardTest", "FRAMETIME ${android.os.Build.MODEL}: $stats")
    }
}
