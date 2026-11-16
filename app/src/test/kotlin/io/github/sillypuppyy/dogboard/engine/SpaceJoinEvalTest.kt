package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.util.Random

/**
 * Words split by a stray space, rejoined across it (SpaceJoin): real first attempts from the Aalto
 * study (DOGBOARD_SPLITS: session, index, context, first part, second part, intended, kind; kind
 * 'letter' = a letter tap landed on the space bar, tapped there at the top of the space bar under the
 * missing letter; 'extra' = an extra space, tapped mid space bar), and correctly typed word pairs that
 * must stay apart (consecutive correct words from DOGBOARD_REAL_TYPOS). Trigram decoder as shipped.
 */
class SpaceJoinEvalTest : FunSpec({
    val splits = System.getenv("DOGBOARD_SPLITS")?.let { File(it) }?.takeIf { it.isFile }
    val pairs = System.getenv("DOGBOARD_REAL_TYPOS")?.let { File(it) }?.takeIf { it.isFile }
    val ngramFile = System.getenv("DOGBOARD_NGRAMS")?.let { File(it) }?.takeIf { it.isFile }

    test("rejoining split words").config(enabled = splits != null && pairs != null && ngramFile != null, timeout = kotlin.time.Duration.parse("2h")) {
        val lexicon = File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
        val bigrams = File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) }
        val ngrams = ngramFile!!.inputStream().use { NgramModel.readBinary(it) }!!
        val keys = QwertyGeometry.keysWithSpace(375f, 216f)
        val rowH = 216f / 4
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        val px = GaussianTouch.POINTS_PER_MM
        // as shipped: the unknown-word letter model and the known misspellings
        val charModel = File("src/main/assets/dogboard/en.charlm.bin").inputStream().use { CharModel.read(it) }
        val misspellings = File("src/main/assets/dogboard/en.misspellings.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.toHashSet()
        fun decoder(split: Boolean = true) = Decoder(lexicon, Decoder.Config(splitWords = split), bigrams = bigrams, context = NgramScorer(ngrams, lexicon, bigrams.sentenceStart))
            .also { it.unknownWords = charModel; it.knownMisspellings = misspellings }
        val ratio = System.getenv("DOGBOARD_JOIN_RATIO")?.toDouble()?.let { kotlin.math.ln(it) } ?: SpaceJoin.RATIO
        fun ctxIds(ctx: String): Pair<String, String?> {
            val raw = ctx.split(' ').filter { it.isNotEmpty() }
            val clean = { w: String -> w.trim('.', ',', '!', '?', ';', ':', '"', '(', ')').lowercase() }
            val ends = { w: String -> w.last() in ".!?" }
            val prev = raw.lastOrNull()?.takeUnless(ends)?.let(clean) ?: Bigrams.START
            val prev2 = raw.getOrNull(raw.size - 2)?.takeIf { prev != Bigrams.START && !ends(it) }?.let(clean)
            return prev to prev2
        }
        // one case: returns the joined word, or null when the two stay apart
        fun run(rng: Random, ctx: String, a: String, b: String, spaceX: Float, spaceY: Float): String? {
            val d = decoder()
            fun taps(w: String, t0: Long) = w.lowercase().mapIndexed { i, c ->
                val k = keys.getValue(c)
                Tap((k.centerX + rng.nextGaussian() * 1.5 * px).toFloat(), (k.centerY + rng.nextGaussian() * 2.0 * px).toFloat(), t0 + 250L * i)
            }
            val (prev, prev2) = ctxIds(ctx)
            val ta = taps(a, 0); val space = Tap(spaceX, spaceY, 250L * a.length); val tb = taps(b, 250L * (a.length + 1))
            val ca = d.decode(ta, keys, params, 4, includeCompletions = false, previous = bigrams.contextId(prev), previous2 = prev2?.let { bigrams.contextId(it) } ?: -1, typed = a.lowercase())
            val first = ca.firstOrNull() ?: return null
            val cb = decoder().decode(tb, keys, params, 4, includeCompletions = false, previous = bigrams.contextId(first.word.lowercase()), previous2 = bigrams.contextId(prev), typed = b.lowercase())
            val second = cb.firstOrNull() ?: return null
            val join = SpaceJoin.best(decoder(split = false), ta, space, tb, keys, params, bigrams.contextId(prev), prev2?.let { bigrams.contextId(it) } ?: -1) ?: return null
            return join.word.takeIf { join.score - (first.score + second.score) >= ratio }
        }
        val rng = Random(11)
        val spaceKey = keys.getValue(' ')
        var fixed = 0; var wrong = 0; var left = 0; var n = 0
        val misses = ArrayList<String>()
        for (line in splits!!.readLines()) {
            val r = line.split('\t'); if (r.size < 7) continue
            val ctx = r[2]; val a = r[3]; val b = r[4]; val want = r[5]; val kind = r[6]
            val spaceX: Float; val spaceY: Float
            if (kind == "letter") {
                val missing = want.lowercase()[a.length]
                spaceX = (keys[missing]?.centerX ?: spaceKey.centerX).coerceIn(spaceKey.centerX - spaceKey.halfWidth, spaceKey.centerX + spaceKey.halfWidth)
                spaceY = 3 * rowH + 0.2f * rowH
            } else { spaceX = spaceKey.centerX + (rng.nextFloat() - 0.5f) * spaceKey.halfWidth; spaceY = 3.5f * rowH }
            val got = run(rng, ctx, a, b, spaceX, spaceY)
            n++
            when { got == null -> left++; got.equals(want, ignoreCase = true) -> fixed++; else -> { wrong++; misses.add("$a $b -> $got (meant $want)") } }
        }
        // negatives: consecutive correctly typed words
        val rows = pairs!!.bufferedReader().useLines { s -> s.map { it.split('\t') }.filter { it.size == 5 }.take(400_000).toList() }
        val negatives = rows.zipWithNext().filter { (x, y) -> x[0] == y[0] && y[1].toInt() == x[1].toInt() + 1 &&
            x[3] == x[4] && y[3] == y[4] && x[3].all { it.isLetter() } && y[3].all { it.isLetter() } && x[3].length > 0 && y[3].length > 0 }
            .shuffled(Random(5)).take(System.getenv("DOGBOARD_JOIN_NEGATIVES")?.toInt() ?: 2000)
        var broken = 0
        val brokenEx = ArrayList<String>()
        for ((x, y) in negatives) {
            val got = run(rng, x[2], x[3], y[3], spaceKey.centerX + (rng.nextFloat() - 0.5f) * spaceKey.halfWidth, 3.5f * rowH + (rng.nextGaussian() * 2.0 * px).toFloat())
            if (got != null) { broken++; if (brokenEx.size < 12) brokenEx.add("${x[3]} ${y[3]} -> $got") }
        }
        println("SPACEJOIN ratio %.2f: %d split words: rejoined right %.1f%%, left split %.1f%%, joined wrong %.1f%% | %d correct pairs: wrongly joined %.2f%%".format(
            kotlin.math.exp(ratio), n, 100.0 * fixed / n, 100.0 * left / n, 100.0 * wrong / n, negatives.size, 100.0 * broken / negatives.size))
        println("SPACEJOIN misses: " + misses.take(10).joinToString("; "))
        println("SPACEJOIN wrongly joined: " + brokenEx.joinToString("; "))
    }
})
