package io.github.sillypuppyy.dogboard.uitest

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sillypuppyy.dogboard.engine.CurrentWord
import io.github.sillypuppyy.dogboard.engine.QwertyGeometry
import io.github.sillypuppyy.dogboard.engine.Tap
import io.github.sillypuppyy.dogboard.engine.TypingEngine
import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * The decoder's speed on the device itself (ART), without the UI: a keystroke decode took ~1 ms on the JVM but 20-50 ms
 * on a Pixel 10 and an emulator (2026-11-21). Types [SENTENCES] key by key through TypingEngine.candidates, as each
 * keystroke does, and logs DEVICESPEED lines; profile it with simpleperf (the UI tests' log polling swamped it).
 */
@RunWith(AndroidJUnit4::class)
class DecodeSpeedDeviceTest {
    @Test fun keystrokeDecodeSpeed() = measure(learned = false)

    /** As on a phone in use: the personal model has learned the common words first (a fresh one knows none). */
    @Test fun keystrokeDecodeSpeedLearned() = measure(learned = true)

    private fun measure(learned: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        io.github.sillypuppyy.dogboard.ime.EngineLoader.ensureLoaded(context)
        if (learned) repeat(20) { for (s in SENTENCES) { var p: String? = null; var p2: String? = null; for (w in s.split(' ')) { TypingEngine.accepted(p, w, p2); p2 = p; p = w } } }
        val keys = QwertyGeometry.keysWithSpace(375f, 216f)
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        val rng = Random(3); val px = GaussianTouch.POINTS_PER_MM
        val times = ArrayList<Long>()
        for (pass in 0 until 3) for ((si, s) in SENTENCES.withIndex()) {
            var prev: String? = null; var prev2: String? = null
            for (w in s.split(' ')) {
                val word = CurrentWord()
                for ((i, c) in w.withIndex()) {
                    val k = keys.getValue(c)
                    word.addLetter(c, Tap((k.centerX + rng.nextDouble(-1.0, 1.0) * 1.5 * px).toFloat(), (k.centerY + rng.nextDouble(-1.0, 1.0) * 2.0 * px).toFloat(), 1000L * si + 200L * i), keys, params)
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    TypingEngine.candidates(word, prev, previous2 = prev2)
                    if (pass > 0) times += (SystemClock.elapsedRealtimeNanos() - t0) / 1000
                }
                prev2 = prev; prev = w
            }
        }
        times.sort()
        val mean = times.average() / 1000
        android.util.Log.i("DogBoardTest", "DEVICESPEED ${if (learned) "learned" else "fresh"} keystrokes ${times.size}: mean %.2f ms, median %.2f, p90 %.2f, max %.2f (beam ${TypingEngine.beam})".format(
            mean, times[times.size / 2] / 1000.0, times[times.size * 9 / 10] / 1000.0, times.last() / 1000.0))
    }

    private companion object {
        val SENTENCES = listOf(
            "i will be there in a few minutes", "can you send me the file when you get a chance", "thanks for the help yesterday",
            "the meeting moved to thursday afternoon", "we should try the new place near the station", "let me know what you think about it",
            "i am running a little late this morning", "do you want to get lunch together tomorrow", "the weather looks good for the weekend",
            "please call me back when you are free", "i think we need more time to finish the project", "that sounds like a great plan to me",
            "where did you leave the keys last night", "my phone battery is almost empty again", "the train was delayed by twenty minutes",
            "remember to buy milk and bread on the way home", "she said the results will come out next week", "how was your trip to the mountains",
            "i could not find the address on the map", "we are almost done with the first part", "happy birthday hope you have a lovely day",
            "the kids are already asleep upstairs", "could you check the numbers one more time", "i forgot my password for the account",
            "the package should arrive before friday", "they want to meet again after the holidays", "it is too cold to go outside today",
            "send my best wishes to your family", "the movie starts at eight so do not be late", "i will write the report tonight",
        )
    }
}
