package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import java.io.File
import java.util.Random

/**
 * Typing real sentences: Tatoeba sentences left out of the bigram data (ids ending in 0) are
 * typed word by word with tap error and autocorrected, with and without the previous word as
 * context. Needs the private Tatoeba export (DOGBOARD_TATOEBA, default under keyboard/data/);
 * skipped without it.
 */
/**
 * Lexicon under test: DOGBOARD_LEXICON, or the path written in build/eval-lexicon-path.txt (the
 * Gradle daemon does not see a new shell's environment), else the shipped one.
 */
private val LEXICON_PATH = System.getenv("DOGBOARD_LEXICON")
    ?: File("build/eval-lexicon-path.txt").takeIf { it.exists() }?.readText()?.trim()
    ?: "src/main/assets/dogboard/en.lexicon.tsv"

/** The slow accuracy evaluations run only on request: DOGBOARD_EVAL=1 ./gradlew :app:testDebugUnitTest */
internal val EVAL = System.getenv("DOGBOARD_EVAL") == "1"

class EngineContextEvalTest : FunSpec({
    val tatoeba = File(
        System.getenv("DOGBOARD_TATOEBA")
            ?: "${System.getProperty("user.home")}/Documents/Code/AI/keyboard/data/tatoeba/eng_sentences.tsv"
    )

    test("context from the previous word makes more words come out right").config(enabled = EVAL && tatoeba.exists(), timeout = kotlin.time.Duration.parse("2h")) {
        val lexicon = File(LEXICON_PATH).bufferedReader().use { Lexicon.parse(it) }
        val bigrams = File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) }
        val keys = QwertyGeometry.letterKeys(375f, 216f)
        val keyList = keys.entries.toList()
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        // optional: a trigram model binary (DOGBOARD_NGRAMS=path, tools/data/arpa_to_dogboard.py)
        val ngrams = System.getenv("DOGBOARD_NGRAMS")?.let { File(it) }?.takeIf { it.isFile }?.inputStream()?.use { NgramModel.readBinary(it) }
        val autocorrect = Autocorrect(lexicon)
        val token = Regex("[A-Za-z]+(?:'[A-Za-z]+)*|[.!?]")
        // optional: the unknown-word letter model (DOGBOARD_CHARLM=path, tools/data/build_charlm.py) and its veto
        val charModel = System.getenv("DOGBOARD_CHARLM")?.let { File(it) }?.takeIf { it.isFile }?.bufferedReader()?.use { CharModel.parse(it) }
        // decoders keep per-word state, so each worker thread has its own (DOGBOARD_EVAL_THREADS, default 3)
        class Decoders {
            val plain = Decoder(lexicon, Decoder.Config(languagePower = Decoder.LANGUAGE_POWER_UNIGRAM))
            val withContext = Decoder(lexicon, bigrams = bigrams)
            // DOGBOARD_EVAL_FARTAP=cost: the trigram decoder reads taps beyond the pruning radius as any key at that cost
            val withTrigram = ngrams?.let { Decoder(lexicon, Decoder.Config(farTap = System.getenv("DOGBOARD_EVAL_FARTAP")?.toDoubleOrNull()), bigrams = bigrams, context = NgramScorer(it, lexicon, bigrams.sentenceStart)) }
            init { for (d in listOfNotNull(plain, withContext, withTrigram)) d.unknownWords = charModel }
        }
        val decoders = ThreadLocal.withInitial { Decoders() }
        val trigramOnly = System.getenv("DOGBOARD_EVAL_TRIGRAM_ONLY") == "1" && ngrams != null
        val pool = java.util.concurrent.ForkJoinPool(System.getenv("DOGBOARD_EVAL_THREADS")?.toIntOrNull() ?: 3)

        // DOGBOARD_EVAL_TEXT=file: one sentence per line (held-out sets from tools/data/build_eval_sets.py)
        val evalText = System.getenv("DOGBOARD_EVAL_TEXT")?.let { File(it) }?.takeIf { it.isFile }
        val source = evalText?.bufferedReader()?.lineSequence()?.map { it.replace('’', '\'') }
            ?: tatoeba.bufferedReader().lineSequence()
                .map { it.split('\t') }
                .filter { it.size >= 3 && it[0].endsWith("0") }
                .map { it[2].replace('’', '\'') }
        val sentences = source
            .filter { s -> token.findAll(s).none { it.value.lowercase() in setOf("tom", "mary") } }
            .take(System.getenv("DOGBOARD_EVAL_MAX")?.toIntOrNull() ?: 800) // DOGBOARD_EVAL_MAX: more sentences, more power
            .toList()

        // optional: every trigram miss at 1.5x2.0 mm (DOGBOARD_EVAL_ERRORS=file): expected, raw letters, result, previous word
        val errorsOut = System.getenv("DOGBOARD_EVAL_ERRORS")?.let { File(it).printWriter() }
        // DOGBOARD_EVAL_SIGMAS="3.0x4.4,5.0x7.0": other noise levels (mm across x down); the misses
        // are dumped at DOGBOARD_EVAL_ERRORS_AT (default 1.5x2.0)
        val sigmas = System.getenv("DOGBOARD_EVAL_SIGMAS")?.split(',')?.map { it.split('x').let { (a, b) -> a.toDouble() to b.toDouble() } }
            ?: listOf(1.0 to 1.5, 1.5 to 2.0, 2.2 to 3.2)
        // DOGBOARD_EVAL_OUTLIERS="0.02,0.05": also run with that share of taps replaced by a far-off tap
        // drawn from DOGBOARD_EVAL_TAIL (IMK taps >1.5 key pitches off, "dx<TAB>dy" in pitches)
        val tail = System.getenv("DOGBOARD_EVAL_TAIL")?.let { File(it) }?.takeIf { it.isFile }?.readLines()
            ?.filter { !it.startsWith("#") && it.isNotBlank() }?.map { l -> l.split('\t').let { it[0].toDouble() to it[1].toDouble() } }
        val outlierRates = listOf(0.0) + (System.getenv("DOGBOARD_EVAL_OUTLIERS")?.split(',')?.map { it.toDouble() }.orEmpty().takeIf { tail != null }.orEmpty())
        val pitch = keys.getValue('s').centerX - keys.getValue('a').centerX
        val dumpAt = System.getenv("DOGBOARD_EVAL_ERRORS_AT")?.split('x')?.let { (a, b) -> a.toDouble() to b.toDouble() } ?: (1.5 to 2.0)
        class Tally {
            var words = 0; var raw = 0; var rightPlain = 0; var rightContext = 0; var rightTrigram = 0; var outOfLexicon = 0
            val nanos = LongArray(3) // plain, bigram, trigram
            val errors = ArrayList<String>()
            fun add(o: Tally) {
                words += o.words; raw += o.raw; rightPlain += o.rightPlain; rightContext += o.rightContext; rightTrigram += o.rightTrigram
                outOfLexicon += o.outOfLexicon
                errors += o.errors
                for (i in nanos.indices) nanos[i] += o.nanos[i]
            }
        }
        for ((sx, sy) in sigmas) for (outliers in outlierRates) {
            // one seed per sentence, so the taps don't depend on the thread count
            fun sentenceTally(index: Int, sentence: String): Tally {
                val d = decoders.get()
                val plain = d.plain; val withContext = d.withContext; val withTrigram = d.withTrigram
                val rng = Random(3L * 1_000_003 + index)
                val tailRng = Random(5L * 1_000_003 + index)
                val r = Tally()
                var previous = Bigrams.START
                var previous2: String? = null
                for (m in token.findAll(sentence)) {
                    val t = m.value
                    if (t in setOf(".", "!", "?")) {
                        previous = Bigrams.START
                        previous2 = null
                        continue
                    }
                    val letters = t.lowercase().filter { it in keys }
                    if (letters.isEmpty()) continue
                    val taps = letters.map { c ->
                        val k = keys.getValue(c)
                        val gx = rng.nextGaussian()
                        val gy = rng.nextGaussian()
                        if (outliers > 0 && tailRng.nextDouble() < outliers) {
                            val (dx, dy) = tail!![tailRng.nextInt(tail.size)]
                            return@map Tap((k.centerX + dx * pitch).toFloat(), (k.centerY + dy * pitch).toFloat())
                        }
                        Tap(
                            (k.centerX + gx * sx * GaussianTouch.POINTS_PER_MM).toFloat(),
                            (k.centerY + gy * sy * GaussianTouch.POINTS_PER_MM).toFloat(),
                        )
                    }
                    val literal = String(taps.map { tap -> keyList.maxBy { GaussianTouch.logLikelihood(it.value, tap.x, tap.y, params) }.key }.toCharArray())
                    fun final(decoder: Decoder, context: Int, context2: Int = -1): String {
                        val candidates = decoder.decode(taps, keys, params, previous = context, typed = literal, previous2 = context2)
                        if (charModel != null && typedStringVeto(candidates)) return literal
                        return autocorrect.correction(literal, candidates) ?: literal
                    }
                    r.words++
                    if (!lexicon.contains(t)) r.outOfLexicon++
                    if (literal == letters) r.raw++
                    fun <T> timed(slot: Int, f: () -> T): T { val t0 = System.nanoTime(); return f().also { r.nanos[slot] += System.nanoTime() - t0 } }
                    // DOGBOARD_EVAL_TRIGRAM_ONLY=1: skip the unigram and bigram decoders (3x faster; their columns read 0)
                    if (!trigramOnly) {
                        if (timed(0) { final(plain, -1) }.equals(t, ignoreCase = true)) r.rightPlain++
                        if (timed(1) { final(withContext, bigrams.contextId(previous)) }.equals(t, ignoreCase = true)) r.rightContext++
                    }
                    if (withTrigram != null) {
                        val got = timed(2) { final(withTrigram, bigrams.contextId(previous), previous2?.let { bigrams.contextId(it) } ?: -1) }
                        if (got.equals(t, ignoreCase = true)) r.rightTrigram++
                        else if (sx == dumpAt.first && sy == dumpAt.second && outliers == 0.0) r.errors += "$t\t$literal\t$got\t$previous"
                    }
                    previous2 = previous
                    previous = t.lowercase()
                }
                return r
            }
            val parts = pool.submit(java.util.concurrent.Callable {
                sentences.indices.toList().parallelStream().map { sentenceTally(it, sentences[it]) }.toList()
            }).get()
            val all = Tally().also { a -> parts.forEach { a.add(it) } }
            all.errors.forEach { errorsOut?.println(it) }
            val words = all.words
            println("TIMING ms per word: " + listOf("plain", "bigram", "trigram").zip(all.nanos.toList()).joinToString { (n, v) -> "%s %.1f".format(n, v / 1e6 / words) })
            println(
                "CONTEXT sigma %.1fx%.1f mm%s, %d words (%.1f%% not in lexicon): raw %.1f%%, unigram %.1f%%, bigram %.1f%%, trigram %s".format(
                    sx, sy, if (outliers > 0) " + %.0f%% far taps".format(outliers * 100) else "", words, all.outOfLexicon * 100.0 / words, all.raw * 100.0 / words, all.rightPlain * 100.0 / words,
                    all.rightContext * 100.0 / words, if (ngrams != null) "%.1f%%".format(all.rightTrigram * 100.0 / words) else "-",
                )
            )
            if (sx == 1.5 && sy == 2.0 && outliers == 0.0 && !trigramOnly) (all.rightContext.toDouble() / words) shouldBeGreaterThan (all.rightPlain.toDouble() / words)
        }
        pool.shutdown()
        errorsOut?.close()
    }

    test("key prior: raw letters with no prior, the word prior, and the word + previous-word prior").config(enabled = EVAL && tatoeba.exists()) {
        val lexicon = File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
        val bigrams = File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) }
        val keys = QwertyGeometry.letterKeys(375f, 216f)
        val letters = keys.keys.toList()
        val targets = letters.map { keys.getValue(it) }
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        val token = Regex("[A-Za-z]+(?:'[A-Za-z]+)*|[.!?]")
        val sentences = tatoeba.bufferedReader().lineSequence()
            .map { it.split('\t') }
            .filter { it.size >= 3 && it[0].endsWith("0") }
            .map { it[2].replace('’', '\'') }
            .filter { s -> token.findAll(s).none { it.value.lowercase() in setOf("tom", "mary") } }
            .take(800).toList()
        val rng = Random(4)
        var words = 0
        val right = IntArray(3)
        for (sentence in sentences) {
            var previous = Bigrams.START
            for (m in token.findAll(sentence)) {
                val t = m.value
                if (t in setOf(".", "!", "?")) { previous = Bigrams.START; continue }
                val w = t.lowercase().filter { it in keys }
                if (w.isEmpty()) continue
                val taps = w.map { c ->
                    val k = keys.getValue(c)
                    Tap((k.centerX + rng.nextGaussian() * 1.5 * 6.3).toFloat(), (k.centerY + rng.nextGaussian() * 2.0 * 6.3).toFloat())
                }
                for (mode in 0..2) {
                    val sb = StringBuilder()
                    for (tap in taps) {
                        val prior = when (mode) {
                            0 -> null
                            1 -> TypingEngine.keyPrior(lexicon, null, sb, -1)
                            else -> TypingEngine.keyPrior(lexicon, bigrams, sb, bigrams.contextId(previous))
                        }
                        val i = GaussianTouch.pick(targets, tap.x, tap.y, params) { idx -> prior?.invoke(letters[idx]) ?: 0.0 } ?: 0
                        sb.append(letters[i])
                    }
                    if (sb.toString() == w) right[mode]++
                }
                words++
                previous = t.lowercase()
            }
        }
        println(
            "KEYPRIOR 1.5x2.0 mm, %d words: raw letters right: no prior %.1f%%, word prior %.1f%%, + previous word %.1f%%".format(
                words, right[0] * 100.0 / words, right[1] * 100.0 / words, right[2] * 100.0 / words,
            )
        )
    }
})
