package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.nio.file.Files
import java.util.Random

/**
 * The keyboard's own engine calls, timed and their memory counted one by one (DOGBOARD_SPEED = a
 * sentence file; DOGBOARD_SPEED_PERSONALFILE = a personal model file to start from): per keystroke
 * keyPrior (touch-down), candidates + correction + caseFor + emojiFor (the suggestion job); per word
 * end accepted + recordTouches + predictions. Full engine as the app loads it (no neural model).
 */
class EngineCallsSpeedTest : FunSpec({
    val text = System.getenv("DOGBOARD_SPEED")?.let { File(it) }?.takeIf { it.isFile }

    test("engine calls: time and memory").config(enabled = text != null, timeout = kotlin.time.Duration.parse("1h")) {
        val dir = Files.createTempDirectory("dogboard-speed").toFile()
        System.getenv("DOGBOARD_SPEED_PERSONALFILE")?.let { File(it).copyTo(File(dir, "personal.tsv")) }
        val a = "src/main/assets/dogboard/"
        // DOGBOARD_SPEED_SNAPSHOT=1: the lexicon and pairs mapped from their snapshot, as the app from its 2nd start
        // (loaded twice: the first load writes the snapshot)
        val snapshot = System.getenv("DOGBOARD_SPEED_SNAPSHOT") == "1"
        repeat(if (snapshot) 2 else 1) {
            TypingEngine.unloadForTest()
            TypingEngine.load(
                openLexicon = { File(a + "en.lexicon.tsv").bufferedReader() },
                openBigrams = { File(a + "en.bigrams.tsv").bufferedReader() },
                personalModel = File(dir, "personal.tsv"),
                openNoSuggest = { File(a + "en.nosuggest.txt").bufferedReader() },
                openEmojiIndex = { File(a + "en.emoji.tsv").bufferedReader() },
                openEmojiTones = { File(a + "en.emoji-tones.tsv").bufferedReader() },
                emojiUsageFile = File(dir, "emoji_usage.tsv"),
                openMisspellings = { File(a + "en.misspellings.txt").bufferedReader() },
                snapshotDir = if (snapshot) File(dir, "snap").also { it.mkdirs() } else null,
                snapshotKey = "speed",
            )
        }
        if (snapshot) println("    ENGINECALLS lexicon from the snapshot (mapped)")
        TypingEngine.attachNgrams(File(a + "en.ngrams.bin").inputStream().use { NgramModel.readBinary(it) })
        TypingEngine.attachCharModel(File(a + "en.charlm.bin").inputStream().use { CharModel.read(it) })
        val keys = QwertyGeometry.keysWithSpace(375f, 216f)
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val time = HashMap<String, Long>(); val mem = HashMap<String, Long>(); val calls = HashMap<String, Int>()
        var measuring = false
        fun <T> m(name: String, block: () -> T): T {
            val a0 = mx.currentThreadAllocatedBytes; val t0 = System.nanoTime()
            val r = block()
            if (measuring) {
                time.merge(name, System.nanoTime() - t0, Long::plus); mem.merge(name, mx.currentThreadAllocatedBytes - a0, Long::plus); calls.merge(name, 1, Int::plus)
            }
            return r
        }
        val rng = Random(3); val px = GaussianTouch.POINTS_PER_MM
        val sentences = text!!.readLines().map { it.lowercase().split(' ').map { w -> w.filter { c -> c in 'a'..'z' } }.filter { it.isNotEmpty() } }.filter { it.size >= 3 }.take(300)
        var keystrokes = 0; var words = 0
        for ((si, ws) in sentences.withIndex()) {
            measuring = si >= 60
            var before = ""
            var prev: String? = null; var prev2: String? = null
            for (w in ws) {
                val word = CurrentWord()
                for ((i, c) in w.withIndex()) {
                    m("keyPrior") { TypingEngine.keyPrior(word.text, prev) }
                    val k = keys.getValue(c)
                    word.addLetter(c, Tap((k.centerX + rng.nextGaussian() * 1.2 * px).toFloat(), (k.centerY + rng.nextGaussian() * 1.6 * px).toFloat(), 1000L * si + 200L * i), keys, params)
                    val cands = m("candidates") { TypingEngine.candidates(word, prev, previous2 = prev2) }
                    m("correction") { TypingEngine.correction(word, cands) }
                    m("caseFor") { TypingEngine.caseFor(word) }
                    m("emojiFor") { TypingEngine.emojiFor(before + word.text, null, EmojiSuggestionMode.values().last()) }
                    if (measuring) keystrokes++
                }
                m("accepted") { TypingEngine.accepted(prev, w, prev2) }
                m("recordTouches") { TypingEngine.recordTouches(w, word.tapsOrNull() ?: emptyList(), keys) }
                before += "$w "
                prev2 = prev; prev = w
                m("predictions") { TypingEngine.predictions(prev, prev2) }
                if (measuring) words++
            }
        }
        println("ENGINECALLS %d keystrokes, %d words".format(keystrokes, words))
        for (name in calls.keys.sortedByDescending { mem[it] }) {
            val per = if (name in setOf("accepted", "recordTouches", "predictions")) words else keystrokes
            println("ENGINECALLS %-14s %7.3f ms  %8.1f KB  per %s".format(name, time[name]!! / 1e6 / per, mem[name]!! / 1024.0 / per, if (per == words) "word" else "keystroke"))
        }
        TypingEngine.unloadForTest()
        dir.deleteRecursively()
    }
})
