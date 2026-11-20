package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.File

private val LEXICON by lazy {
    File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
}
private val KEYS = QwertyGeometry.letterKeys(375f, 216f)
private val PARAMS = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
private val DECODER by lazy { Decoder(LEXICON, Decoder.Config(languagePower = Decoder.LANGUAGE_POWER_UNIGRAM)) }
// with the known misspellings, as the app loads them (they keep "TEH" in caps from the all-caps shield)
private val AUTOCORRECT by lazy {
    Autocorrect(LEXICON).apply {
        knownMisspellings = java.io.File("src/main/assets/dogboard/en.misspellings.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.trim().lowercase() }.toHashSet()
    }
}

/** Taps at the centres of the keys for [letters]. */
private fun centreTaps(letters: String) =
    letters.lowercase().map { c -> KEYS.getValue(c).let { Tap(it.centerX, it.centerY) } }

private fun topWord(typed: String) =
    DECODER.decode(centreTaps(typed), KEYS, PARAMS, typed = typed).first { !it.isCompletion }.word

private val SPLITTING by lazy {
    Decoder(LEXICON, Decoder.Config(languagePower = Decoder.LANGUAGE_POWER_UNIGRAM, splitWords = true))
}

private fun corrected(typed: String, decoder: Decoder = DECODER): String {
    val candidates = decoder.decode(centreTaps(typed), KEYS, PARAMS, typed = typed)
    return AUTOCORRECT.correction(typed, candidates) ?: typed
}

class EngineTest : FunSpec({
    context("Lexicon") {
        test("parses our format and keeps the stored casing") {
            LEXICON.size shouldBeGreaterThan 40_000
            LEXICON.contains("hello") shouldBe true
            LEXICON.storedForm("i'm") shouldBe "I'm"
            LEXICON.contains("qzxv").shouldBe(false)
        }
        test("more frequent words have higher probability") {
            (LEXICON.logProb("the")!! > LEXICON.logProb("married")!!) shouldBe true
            LEXICON.logProb("qzxv").shouldBeNull()
        }
        test("next-letter probabilities follow the words below the prefix") {
            val p = LEXICON.nextLetterLogProbs("th")!!
            (p.getValue('e') > p.getValue('r')) shouldBe true
            ((p['q'] ?: -99.0) < -8.0) shouldBe true // only rare words continue "thq"
            (kotlin.math.exp(p.values.maxOf { it }) <= 1.0) shouldBe true
            LEXICON.nextLetterLogProbs("qzx").shouldBeNull()
        }
        test("a word can be stored in several casings, each with its share") {
            val lex = Lexicon.of(linkedMapOf("Google" to 90L, "google" to 10L, "the" to 900L))
            lex.formsOf("google").map { it } shouldBe listOf("Google", "google")
            val node = lex.find("google")
            val total = kotlin.math.ln(100.0 / 1000.0)
            (lex.logProb(node) - total) shouldBe (0.0 plusOrMinus 1e-6)
            lex.size shouldBe 2 // one word id per spelling
        }
        test("the real lexicon keeps measured casings (may/May, google/Google, Smith)") {
            LEXICON.formsOf("may") shouldBe listOf("may", "May")
            LEXICON.formsOf("google") shouldBe listOf("Google", "google")
            LEXICON.formsOf("smith") shouldBe listOf("Smith")
        }
        test("each node knows its most frequent completion") {
            val node = LEXICON.find("hel")
            LEXICON.word(LEXICON.bestWord(node)) shouldBe "help"
        }
    }

    context("Decoder") {
        test("exact taps decode to the word") {
            topWord("hello") shouldBe "hello"
            topWord("keyboard") shouldBe "keyboard"
        }
        test("a neighbouring key: hrllo -> hello") { topWord("hrllo") shouldBe "hello" }
        test("swapped letters: teh -> the") { topWord("teh") shouldBe "the" }
        test("missed letter: becuse -> because, tomorow -> tomorrow") {
            topWord("becuse") shouldBe "because"
            topWord("tomorow") shouldBe "tomorrow"
        }
        test("extra tap: thhe -> the") { topWord("thhe") shouldBe "the" }
        test("words run together are split, up to three (iOS word restart)") {
            corrected("helloeveryone", SPLITTING) shouldBe "hello everyone"
            corrected("hellowvryone", SPLITTING) shouldBe "hello everyone"
            corrected("goodmorning", SPLITTING) shouldBe "good morning"
            corrected("thankyou", SPLITTING) shouldBe "thank you"
            corrected("seeyousoon", SPLITTING) shouldBe "see you soon"
        }
        test("real words are not split") {
            corrected("together", SPLITTING) shouldBe "together"
            corrected("into", SPLITTING) shouldBe "into"
            corrected("nowhere", SPLITTING) shouldBe "nowhere"
            corrected("therefore", SPLITTING) shouldBe "therefore"
            corrected("abound", SPLITTING) shouldBe "abound"
        }
        test("missing apostrophe: dont -> don't") { topWord("dont") shouldBe "don't" }
        test("short inputs get no completions that need a typing error") {
            DECODER.decode(centreTaps("eh"), KEYS, PARAMS).filter { it.isCompletion }.all { it.edits == 0 } shouldBe true
        }
        test("completions are offered for a prefix") {
            val words = DECODER.decode(centreTaps("keyb"), KEYS, PARAMS).filter { it.isCompletion }.map { it.word }
            words.contains("keyboard") shouldBe true
        }
    }

    context("Autocorrect") {
        test("fixes typos") {
            corrected("teh") shouldBe "the"
            corrected("hrllo") shouldBe "hello"
            corrected("dont") shouldBe "don't"
            corrected("im") shouldBe "I'm"
            corrected("ill") shouldBe "I'll"
        }
        test("keeps correctly typed words, including ones close to a more frequent word") {
            corrected("hello") shouldBe "hello"
            corrected("form") shouldBe "form"
            corrected("well") shouldBe "well"
            corrected("were") shouldBe "were"
        }
        test("fixes the casing of i and carries over a capital") {
            corrected("i") shouldBe "I"
            corrected("Teh") shouldBe "The"
            corrected("TEH") shouldBe "THE"
            // a short word in capitals throughout is an abbreviation: left as typed (all-caps shield, 2026-11-21)
            corrected("NAFO") shouldBe "NAFO"
        }
        test("capitals follow the iOS case rules (google / Google / GOOGLE, YouTube)") {
            corrected("googel") shouldBe "Google" // the lexicon has one casing for now: the company
            corrected("Googel") shouldBe "Google"
            corrected("GOOGEL") shouldBe "GOOGLE"
            corrected("youtubr") shouldBe "YouTube"
            corrected("Youtubr") shouldBe "YouTube"
            corrected("YOUTUBR") shouldBe "YOUTUBE"
        }
        test("iOS rule 2: a one-letter word is never completed into a longer word") {
            corrected("a") shouldBe "a"
        }
        test("iOS rule 5: short stems are not auto-completed") {
            corrected("keyb") shouldBe "keyb"
        }
    }
})
