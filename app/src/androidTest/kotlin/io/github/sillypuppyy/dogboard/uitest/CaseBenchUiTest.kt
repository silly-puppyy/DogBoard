package io.github.sillypuppyy.dogboard.uitest

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real typos typed on a keyboard, for comparing keyboards (tools/test/casebench.sh; `-e keyboard gboard` for
 * Gboard): each case types its context words, the word as typed and a space, with real timings, and logs which
 * word the field ends with (files/casebench/results.txt: "case <i> <word>"; the log is cleared per case). The scoring
 * happens on the computer. Cases come from
 * /data/local/tmp/dogboard-cases.tsv (index, context, typed; letters and spaces only), read through the shell.
 * Only runs when the instrumentation gets `-e casebench 1` (not part of the normal UI tests).
 */
@RunWith(AndroidJUnit4::class)
class CaseBenchUiTest {
    @Test fun typeCases() {
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        if (args.getString("casebench") != "1") return
        val kb = KeyboardDriver()
        kb.selectDogBoard()
        kb.resetLearningOnce()
        val cases = kb.shell("cat /data/local/tmp/dogboard-cases.tsv").lineSequence()
            .map { it.split('\t') }.filter { it.size == 3 }.toList()
        val out = java.io.File(kb.instrumentation.targetContext.getExternalFilesDir(null), "casebench").apply { mkdirs() }
            .let { java.io.File(it, "results.txt") }.apply { writeText("") }
        val area = kb.open()
        kb.waitForEngine()
        for ((i, context, typed) in cases) {
            // a fresh field for each case (select all, delete): no context from the case before
            // the log cleared too: reading the field dumps the log, ~40 s per case once the 16 MB buffer filled
            kb.shell("logcat -c")
            kb.shell("input keycombination 113 29"); kb.shell("input keyevent 67")
            SystemClock.sleep(300)
            kb.type(area, (if (context.isBlank()) "" else "$context ") + "$typed ")
            val last = kb.text().trim().substringAfterLast(' ')
            out.appendText("case $i $last\n")
        }
    }
}
