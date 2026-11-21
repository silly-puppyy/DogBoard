package io.github.sillypuppyy.dogboard.uitest

import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * The user's "try not tonpause" (2026-11-21: a correctly typed "not" was changed by the look back on the phone) with
 * the experimental neural look back on (Settings > Autocorrect, off by default): the emulator keeps "not" without it.
 */
@RunWith(AndroidJUnit4::class)
class NeuralRetroUiTest {
    private val kb = KeyboardDriver()
    private val prefs by FlorisPreferenceStore

    @Before fun setUp() {
        kb.selectDogBoard()
        kb.resetLearningOnce()
        runBlocking { prefs.correction.neuralRetro.set(true) }
    }
    @After fun tearDown() {
        runBlocking { prefs.correction.neuralRetro.set(false) }
        kb.close()
    }

    @Test fun correctNotKept() {
        for (typed in listOf("try not tonpause x", "i will not tonpause x", "try not to pause x")) {
            val area = kb.open()
            kb.waitForEngine()
            kb.type(area, typed)
            android.os.SystemClock.sleep(1500)
            val got = kb.text()
            android.util.Log.i("DogBoardTest", "neural retro: '$typed' -> '$got'")
            assertTrue("'$typed' -> '$got'", " not " in got)
        }
    }
}
