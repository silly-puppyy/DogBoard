package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.util.Random

/**
 * The engine's speed on the typing path, as the keyboard calls it: sentences (DOGBOARD_SPEED = a text
 * file, one sentence a line) typed key by key with touch noise; every keystroke decodes the word so
 * far (the decoder continues its search, as in the app), every word end runs autocorrect and the
 * next-word predictions. Prints time per keystroke and per word end (mean, p95, max) and memory
 * allocated per keystroke, after a warm-up. Shipped data (lexicon, bigrams, assets' trigram file).
 */
class EngineSpeedTest : FunSpec({
    val text = System.getenv("DOGBOARD_SPEED")?.let { File(it) }?.takeIf { it.isFile }

    test("typing path speed").config(enabled = text != null, timeout = kotlin.time.Duration.parse("1h")) {
        val lexicon = File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
        val bigrams = File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) }
        val ngrams = File("src/main/assets/dogboard/en.ngrams.bin").inputStream().use { NgramModel.readBinary(it) }!!
        val keys = QwertyGeometry.keysWithSpace(375f, 216f)
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        val decoder = Decoder(lexicon, Decoder.Config(), bigrams = bigrams, context = NgramScorer(ngrams, lexicon, bigrams.sentenceStart))
        val autocorrect = Autocorrect(lexicon)
        // DOGBOARD_SPEED_PERSONAL=n: a personal model taught n other sentences, mixed into every word's
        // score as the keyboard does once someone has typed for a while
        val personal = System.getenv("DOGBOARD_SPEED_PERSONAL")?.toIntOrNull()?.let { n ->
            PersonalModel(null).also { pm ->
                text!!.readLines().takeLast(n).forEach { line ->
                    val ws = line.lowercase().split(' ').map { w -> w.filter { c -> c in 'a'..'z' } }.filter { it.isNotEmpty() }
                    for (i in ws.indices) pm.accept(ws.getOrNull(i - 1), ws[i], ws.getOrNull(i - 2))
                }
            }
        }
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val rng = Random(3)
        val px = GaussianTouch.POINTS_PER_MM
        val sentences = text!!.readLines().map { it.lowercase().split(' ').map { w -> w.filter { c -> c in 'a'..'z' } }.filter { it.isNotEmpty() } }
            .filter { it.size >= 3 }.take(System.getenv("DOGBOARD_SPEED_N")?.toIntOrNull() ?: 400)
        val warm = sentences.size / 5
        val keyNs = ArrayList<Long>(); val keyBytes = ArrayList<Long>(); val endNs = ArrayList<Long>(); val endBytes = ArrayList<Long>()
        for ((si, words) in sentences.withIndex()) {
            var prev: String = Bigrams.START; var prev2: String? = null
            for (w in words) {
                // the keyboard's personal mix for this context (TypingEngine.makeMix)
                val mix: PersonalMix? = personal?.decoderMix(lexicon, prev.takeIf { it != Bigrams.START }, prev2)
                val taps = ArrayList<Tap>()
                for ((i, c) in w.withIndex()) {
                    val k = keys.getValue(c)
                    taps.add(Tap((k.centerX + rng.nextGaussian() * 1.2 * px).toFloat(), (k.centerY + rng.nextGaussian() * 1.6 * px).toFloat(), 1000L * si + 200L * i))
                    val a0 = mx.currentThreadAllocatedBytes; val t0 = System.nanoTime()
                    decoder.decode(taps, keys, params, 10, previous = bigrams.contextId(prev), typed = w.substring(0, i + 1),
                        previous2 = prev2?.let { bigrams.contextId(it) } ?: -1, personal = mix)
                    val dt = System.nanoTime() - t0; val da = mx.currentThreadAllocatedBytes - a0
                    if (si >= warm) { keyNs.add(dt); keyBytes.add(da) }
                }
                val a0 = mx.currentThreadAllocatedBytes; val t0 = System.nanoTime()
                val cands = decoder.decode(taps, keys, params, 10, previous = bigrams.contextId(prev), typed = w, previous2 = prev2?.let { bigrams.contextId(it) } ?: -1, personal = mix)
                autocorrect.correction(w, cands)
                ngrams.followers(prev2?.let { ngrams.idOf(it) } ?: -1, ngrams.idOf(w), 12)
                val dt = System.nanoTime() - t0; val da = mx.currentThreadAllocatedBytes - a0
                if (si >= warm) { endNs.add(dt); endBytes.add(da) }
                prev2 = prev; prev = w
            }
        }
        fun stats(name: String, ns: List<Long>, bytes: List<Long>) {
            val s = ns.sorted()
            println("SPEED %s: n %d, mean %.2f ms, p95 %.2f ms, max %.1f ms, allocated %.0f KB mean".format(
                name, s.size, s.average() / 1e6, s[(s.size * 0.95).toInt()] / 1e6, s.last() / 1e6, bytes.average() / 1024))
        }
        stats("keystroke", keyNs, keyBytes)
        stats("word end", endNs, endBytes)
    }
})
