package io.github.sillypuppyy.dogboard.uitest

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sillypuppyy.dogboard.engine.CurrentWord
import io.github.sillypuppyy.dogboard.engine.QwertyGeometry
import io.github.sillypuppyy.dogboard.engine.Tap
import io.github.sillypuppyy.dogboard.engine.TypingEngine
import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the device's engine (its neural model included) makes of typed words, without the UI: each word typed at key
 * centres through TypingEngine.candidates, refined by the neural model as a word end is, then corrected. Logs
 * CORRECTIONTRACE lines (tag DogBoardTest; made-up words only). Written for the omitted-letter gap to Gboard
 * ("closin" kept on the emulator, fixed by the JVM engine; 2026-11-22).
 */
@RunWith(AndroidJUnit4::class)
class CorrectionTraceDeviceTest {
    @Test fun traceWordEnds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        io.github.sillypuppyy.dogboard.ime.EngineLoader.ensureLoaded(context)
        val keys = QwertyGeometry.keysWithSpace(375f, 216f)
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        for ((before, typed) in CASES) {
            val word = CurrentWord()
            for ((i, c) in typed.withIndex()) {
                val k = keys.getValue(c.lowercaseChar()); word.addLetter(c, Tap(k.centerX, k.centerY, 200L * i), keys, params)
            }
            val prev = before.split(' ').lastOrNull { it.isNotEmpty() }
            val decoded = TypingEngine.candidates(word, prev)
            val refined = TypingEngine.refineWithNeural(decoded, before)
            fun show(list: List<io.github.sillypuppyy.dogboard.engine.Candidate>?) =
                list?.take(4)?.joinToString(" | ") { "%s %.2f%s".format(it.word, it.score, if (it.isTypedString) " (typed)" else "") } ?: "none"
            val fromDecoded = TypingEngine.correction(word, decoded, commit = true)
            val fromRefined = refined?.let { TypingEngine.correction(word, it, commit = true) }
            android.util.Log.i("DogBoardTest", "CORRECTIONTRACE '$before' $typed: decoder -> $fromDecoded [${show(decoded)}]; " +
                "neural -> $fromRefined [${show(refined)}]")
        }
    }

    private companion object {
        val CASES = listOf("" to "closin", "i was " to "closin", "" to "workin", "" to "somethi", "" to "telvision", "" to "amng")
    }
}
