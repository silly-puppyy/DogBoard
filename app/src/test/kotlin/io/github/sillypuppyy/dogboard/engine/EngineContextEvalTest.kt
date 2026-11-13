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

    test("context from the previous word makes more words come out right").config(enabled = EVAL && tatoeba.exists()) {
        val lexicon = File(LEXICON_PATH).bufferedReader().use { Lexicon.parse(it) }
        val bigrams = File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) }
        val keys = QwertyGeometry.letterKeys(375f, 216f)
        val keyList = keys.entries.toList()
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        val plain = Decoder(lexicon, Decoder.Config(languagePower = Decoder.LANGUAGE_POWER_UNIGRAM))
        val withContext = Decoder(lexicon, bigrams = bigrams)
        val autocorrect = Autocorrect(lexicon)
        val token = Regex("[A-Za-z]+(?:'[A-Za-z]+)*|[.!?]")

        val sentences = tatoeba.bufferedReader().lineSequence()
            .map { it.split('\t') }
            .filter { it.size >= 3 && it[0].endsWith("0") }
            .map { it[2].replace('’', '\'') }
            .filter { s -> token.findAll(s).none { it.value.lowercase() in setOf("tom", "mary") } }
            .take(800)
            .toList()

        for ((sx, sy) in listOf(1.0 to 1.5, 1.5 to 2.0, 2.2 to 3.2)) {
            val rng = Random(3)
            var words = 0
            var raw = 0
            var rightPlain = 0
            var rightContext = 0
            var outOfLexicon = 0
            for (sentence in sentences) {
                var previous = Bigrams.START
                for (m in token.findAll(sentence)) {
                    val t = m.value
                    if (t in setOf(".", "!", "?")) {
                        previous = Bigrams.START
                        continue
                    }
                    val letters = t.lowercase().filter { it in keys }
                    if (letters.isEmpty()) continue
                    val taps = letters.map { c ->
                        val k = keys.getValue(c)
                        Tap(
                            (k.centerX + rng.nextGaussian() * sx * GaussianTouch.POINTS_PER_MM).toFloat(),
                            (k.centerY + rng.nextGaussian() * sy * GaussianTouch.POINTS_PER_MM).toFloat(),
                        )
                    }
                    val literal = String(taps.map { tap -> keyList.maxBy { GaussianTouch.logLikelihood(it.value, tap.x, tap.y, params) }.key }.toCharArray())
                    fun final(decoder: Decoder, context: Int): String {
                        val candidates = decoder.decode(taps, keys, params, previous = context, typed = literal)
                        return autocorrect.correction(literal, candidates) ?: literal
                    }
                    words++
                    if (!lexicon.contains(t)) outOfLexicon++
                    if (literal == letters) raw++
                    if (final(plain, -1).equals(t, ignoreCase = true)) rightPlain++
                    if (final(withContext, bigrams.contextId(previous)).equals(t, ignoreCase = true)) rightContext++
                    previous = t.lowercase()
                }
            }
            println(
                "CONTEXT sigma %.1fx%.1f mm, %d words (%.1f%% not in lexicon): raw %.1f%%, unigram %.1f%%, bigram %.1f%%".format(
                    sx, sy, words, outOfLexicon * 100.0 / words, raw * 100.0 / words, rightPlain * 100.0 / words,
                    rightContext * 100.0 / words,
                )
            )
            if (sx == 1.5) (rightContext.toDouble() / words) shouldBeGreaterThan (rightPlain.toDouble() / words)
        }
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
