package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * Prints what the full engine makes of typed strings (key-centre taps), one "previous words<TAB>typed" per line of
 * DOGBOARD_PROBE_CASES. A probe for user reports: DOGBOARD_PROBE=1 DOGBOARD_PROBE_CASES=... DOGBOARD_NGRAMS=... gradle test.
 */
class TypedProbeTest : FunSpec({
    test("typed strings through the full engine").config(enabled = System.getenv("DOGBOARD_PROBE") == "1") {
        val cases = File(System.getenv("DOGBOARD_PROBE_CASES") ?: return@config).readLines().filter { '\t' in it }
        val width = 411f
        val keys = QwertyGeometry.keysWithSpace(width, 216f)
        val params = GaussianTouch.params(medianKeyHalfWidth = width / 20.0, typingInterval = 0.15)
        TypingEngine.unloadForTest()
        // DOGBOARD_PACK_DIR + DOGBOARD_PACK_LANG: another language's pack (as RealTypoEvalTest), its rules' own letters
        val dir = System.getenv("DOGBOARD_PACK_DIR")?.trimEnd('/')?.plus('/') ?: "src/main/assets/dogboard/"
        val lang = System.getenv("DOGBOARD_PACK_LANG") ?: "en"
        fun reader(name: String) = File("$dir$lang.$name").takeIf { it.isFile }?.bufferedReader() ?: java.io.BufferedReader(java.io.StringReader(""))
        val rules = File("${dir}rules.json").takeIf { it.isFile }?.let { LanguageRules.parse(it.readText()) }
        Lexicon.distinctLetters = rules?.distinctLetters?.toSet() ?: emptySet()
        TypingEngine.load(
            openLexicon = { reader("lexicon.tsv") },
            openBigrams = { reader("bigrams.tsv") },
            openNoSuggest = { reader("nosuggest.txt") },
            openMisspellings = { reader("misspellings.txt") },
            rules = rules ?: LanguageRules(),
        )
        System.getenv("DOGBOARD_NGRAMS")?.let { File(it) }?.takeIf { it.isFile }?.inputStream()?.use { NgramModel.readBinary(it) }
            ?.let { TypingEngine.attachNgrams(it) }
        // the letter model as the app has it (DOGBOARD_CHARLM=0: without, to see what it changes)
        if (System.getenv("DOGBOARD_CHARLM") != "0")
            File("$dir$lang.charlm.bin").takeIf { it.isFile }?.inputStream()?.use { CharModel.read(it) }?.let { TypingEngine.attachCharModel(it) }
        // DOGBOARD_PROBE_FORMS=word: what the lexicon and the n-gram model hold for a word's spellings
        System.getenv("DOGBOARD_PROBE_FORMS")?.let { w ->
            val lex = TypingEngine.lexiconForTest()!!
            val node = lex.find(w)
            println("PROBE-FORMS $w node=$node hasCasings=${lex.hasCasings(node)} forms=${lex.forms(node)}")
            System.getenv("DOGBOARD_NGRAMS")?.let { f -> NgramModel.readBinary(File(f).inputStream())?.let { m ->
                println("PROBE-FORMS ngram ids: " + lex.forms(node).joinToString { (f2, _) -> "$f2=${m.idOf(f2.lowercase())}" })
                val nous = m.idOf("nous"); val avons = m.idOf("avons")
                println("PROBE-FORMS model nous=$nous avons=$avons P(mange|nous avons)=${m.logProbOf(nous, avons, m.idOf("mange"))} P(mangé|nous avons)=${m.logProbOf(nous, avons, m.idOf("mangé"))} P(mangé|-1,avons)=${m.logProbOf(-1, avons, m.idOf("mangé"))}")
                println("PROBE-FORMS lexicon avons node=${lex.find("avons")} wordId=${lex.wordId(lex.find("avons"))} word=${lex.word(lex.find("avons"))}")
            } }
        }
        try {
            // DOGBOARD_PROBE_NOISE=n: n tries per case with taps scattered 1.5 x 2 mm (6.3 dp a mm), outcomes counted
            val tries = System.getenv("DOGBOARD_PROBE_NOISE")?.toIntOrNull()
            val rnd = java.util.Random(1)
            if (tries != null) for (line in cases) {
                val (before, typed) = line.split('\t')
                val words = before.split(' ').filter { it.isNotEmpty() }
                val outcomes = HashMap<String, Int>()
                repeat(tries) {
                    val word = CurrentWord().apply {
                        for (c in typed) keys[c.lowercaseChar()]?.let {
                            addLetter(c, Tap((it.centerX + rnd.nextGaussian() * 1.5 * 6.3).toFloat(), (it.centerY + rnd.nextGaussian() * 2.0 * 6.3).toFloat()), keys, params)
                        }
                    }
                    val candidates = TypingEngine.candidates(word, words.lastOrNull() ?: ".", previous2 = words.getOrNull(words.size - 2))
                    val result = TypingEngine.correction(word, candidates, commit = true) ?: word.text
                    outcomes.merge(result, 1, Int::plus)
                }
                println("PROBE-NOISE %-12s %-10s %s".format(before.takeLast(12), typed, outcomes.entries.sortedByDescending { it.value }.take(8).joinToString(" ") { "${it.key}:${it.value}" }))
            }
            if (tries == null) for (line in cases) {
                val (before, typed) = line.split('\t')
                val word = CurrentWord().apply {
                    for (c in typed) keys[c.lowercaseChar()]?.let { addLetter(c, Tap(it.centerX, it.centerY), keys, params) }
                }
                val words = before.split(' ').filter { it.isNotEmpty() }
                val candidates = TypingEngine.candidates(word, words.lastOrNull() ?: ".", previous2 = words.getOrNull(words.size - 2))
                val result = TypingEngine.correction(word, candidates, commit = true) ?: word.text
                println("PROBE-TYPED %-12s %-24s -> %-24s %s".format(before, typed, result,
                    candidates.take(4).joinToString(" | ") { "%s %.2f".format(it.word, it.score) }))
            }
        } finally {
            TypingEngine.unloadForTest()
        }
    }
})
