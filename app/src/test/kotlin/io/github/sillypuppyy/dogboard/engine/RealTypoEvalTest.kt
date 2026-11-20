package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.util.Random

/**
 * Autocorrect on real typos: words people typed on phones (their first attempt, before any fix),
 * with the word they meant and the true words before it (DOGBOARD_REAL_TYPOS: a private TSV of
 * session, index, context, typed, intended; not in the repo). The study logged letters, not touch
 * points, so each typed letter is a tap at its key's centre. Typo pairs: fixed / left as typed /
 * changed to another wrong word; correctly typed words: kept / broken. Trigram decoder as shipped
 * (DOGBOARD_NGRAMS). Runs only when DOGBOARD_REAL_TYPOS is set (the whole suite runs: Kotest ignores
 * --tests; DOGBOARD_EVAL stays off so the 2 h Tatoeba eval does not); DOGBOARD_EVAL_ERRORS=file dumps the misses.
 */
class RealTypoEvalTest : FunSpec({
    val pairs = System.getenv("DOGBOARD_REAL_TYPOS")?.let { File(it) }?.takeIf { it.isFile }
    val ngramFile = System.getenv("DOGBOARD_NGRAMS")?.let { File(it) }?.takeIf { it.isFile }

    test("real phone typos: fixed, left, broken").config(enabled = pairs != null && ngramFile != null, timeout = kotlin.time.Duration.parse("2h")) {
        // DOGBOARD_APP_PATH=1: decide as the keyboard does, through TypingEngine.correction (its filters: the
        // typed-string veto, no-suggest words, learned typos, ...) with the engine loaded from the app's assets
        // (abbreviations, no-suggest list, misspellings, letter model); the decoders use that engine's lexicon
        val appPath = System.getenv("DOGBOARD_APP_PATH") == "1"
        if (appPath) {
            TypingEngine.unloadForTest()
            val a = "src/main/assets/dogboard/"
            TypingEngine.load(
                openLexicon = { File(a + "en.lexicon.tsv").bufferedReader() },
                openAbbreviations = { File(a + "en.abbreviations.txt").bufferedReader() },
                openNoSuggest = { File(a + "en.nosuggest.txt").bufferedReader() },
                openMisspellings = { File(a + "en.misspellings.txt").bufferedReader() },
            )
            TypingEngine.attachCharModel(File(a + "en.charlm.bin").inputStream().use { CharModel.read(it) })
        }
        val lexicon = if (appPath) TypingEngine.lexiconForTest()!! else File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
        val bigrams = File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) }
        val ngrams = ngramFile!!.inputStream().use { NgramModel.readBinary(it) }!!
        // DOGBOARD_SPACE_KEY=1: the space bar among the keys a tap may have meant (word breaks at a tap)
        val keys = if (System.getenv("DOGBOARD_SPACE_KEY") == "1") QwertyGeometry.keysWithSpace(375f, 216f) else QwertyGeometry.letterKeys(375f, 216f)
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        // DOGBOARD_END_OMISSION_STEM=n: Autocorrect.Level.minStemEndOmission (off by default, as iOS)
        // DOGBOARD_LEVEL=GENTLE|STANDARD|STRONG (default STANDARD), DOGBOARD_LANGUAGE_POWER overrides its power
        val base = AutocorrectLevel.valueOf(System.getenv("DOGBOARD_LEVEL") ?: "STANDARD").level
        val autocorrect = if (appPath) TypingEngine.autocorrectForTest()!! else Autocorrect(lexicon, base.copy(languagePower = System.getenv("DOGBOARD_LANGUAGE_POWER")?.toDoubleOrNull() ?: base.languagePower,minStemEndOmission = (System.getenv("DOGBOARD_END_OMISSION_STEM")?.toIntOrNull() ?: Autocorrect.Level.STANDARD.minStemEndOmission)))
        // DOGBOARD_NAME_RANK=n: Autocorrect.nameAsTypedRank (a lowercase-typed rare name protects itself only within rank n)
        System.getenv("DOGBOARD_NAME_RANK")?.toIntOrNull()?.let { autocorrect.nameAsTypedRank = it }
        // DOGBOARD_CONTEXT_END_OMISSION=0: Autocorrect.contextEndOmission off (2026-11-19 on: fixed 49.5 -> 49.6 %, broken 0.4 % both)
        autocorrect.contextEndOmission = System.getenv("DOGBOARD_CONTEXT_END_OMISSION") != "0"
        // the app loads the word-pair model into the engine; the app path here loads it apart (above)
        if (appPath) autocorrect.pairSupport = Autocorrect.pairSupport(lexicon, bigrams)
        autocorrect.nonWordMargin = System.getenv("DOGBOARD_NONWORD_MARGIN") == "1"
        // DOGBOARD_EXACT_SPLITS=0: Autocorrect.exactSplitsEnabled off (a typed non-word stays ahead of its exact split)
        autocorrect.exactSplitsEnabled = System.getenv("DOGBOARD_EXACT_SPLITS") != "0"
        // DOGBOARD_RULE3=x: Rule 3's bar as a probability ratio (iOS 0.1), "off" = no bar
        System.getenv("DOGBOARD_RULE3")?.let { autocorrect.minContextSupport = if (it == "off") Double.NEGATIVE_INFINITY else kotlin.math.ln(it.toDouble()) }
        val handMm = System.getenv("DOGBOARD_REAL_TYPOS_HAND")?.toDoubleOrNull()
        val sigma = System.getenv("DOGBOARD_REAL_TYPOS_SIGMA")?.split('x')?.let { (a, b) -> a.toDouble() to b.toDouble() } ?: (0.0 to 0.0)
        // DOGBOARD_END_INSERTION=cost: the skipped-key cost after the last tap (Decoder.Config.endInsertion)
        val endInsertion = (System.getenv("DOGBOARD_END_INSERTION")?.toDoubleOrNull() ?: Decoder.END_INSERTION)
        // DOGBOARD_CHARLM=1: the unknown-word letter model attached, as the app ships (assets en.charlm.bin)
        // DOGBOARD_MISSPELLINGS=1: known misspellings never scored by the letter model (assets en.misspellings.txt)
        val misspellings = File("src/main/assets/dogboard/en.misspellings.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.toHashSet()
        val charModel = if (System.getenv("DOGBOARD_CHARLM") == "1") File("src/main/assets/dogboard/en.charlm.bin").inputStream().use { CharModel.read(it) } else null
        val decoders = ThreadLocal.withInitial {
            Decoder(lexicon, Decoder.Config(endInsertion = endInsertion, spaceTap = System.getenv("DOGBOARD_SPACE_TAP")?.toDouble() ?: Decoder.SPACE_TAP, missedSpace = System.getenv("DOGBOARD_MISSED_SPACE") != "0"), bigrams = bigrams, context = NgramScorer(ngrams, lexicon, bigrams.sentenceStart))
                .also { it.unknownWords = charModel; if (System.getenv("DOGBOARD_MISSPELLINGS") == "1") it.knownMisspellings = misspellings }
        }

        // DOGBOARD_PROBE_WORDS="befor,hav": top candidates and the decision for those typed words (no context)
        System.getenv("DOGBOARD_PROBE_WORDS")?.split(',')?.forEach { typed ->
            val taps = typed.map { c -> keys.getValue(c).let { Tap(it.centerX, it.centerY) } }
            val cands = decoders.get().decode(taps, keys, params, previous = bigrams.contextId(Bigrams.START), typed = typed)
            println("PROBE $typed -> ${autocorrect.correction(typed, cands) ?: "(left)"}: " + cands.take(6).joinToString { c ->
                "%s %.2f (geo %.2f lm %.2f%s%s)".format(c.word, c.score, c.geometry, c.languageModel, if (c.isCompletion) " compl" else "", if (c.isTypedString) " typed" else "")
            })
        }
        // a fixed sample of each kind (DOGBOARD_REAL_TYPOS_N per kind, default 3000)
        val n = System.getenv("DOGBOARD_REAL_TYPOS_N")?.toIntOrNull() ?: 3000
        // streamed with reservoir sampling: the file has over a million rows, too many to hold split
        val rng = Random(7)
        val typos = ArrayList<List<String>>(n); val correct = ArrayList<List<String>>(n)
        var seenTypos = 0L; var seenCorrect = 0L
        // DOGBOARD_APOSTROPHE_SET=n: n more cases, apostrophe words typed without it ("dont" for "don't", "its" for
        // "it's"), made from correctly typed ones: the data has no such typos (every apostrophe word was typed with
        // it), and phones' typists often skip the key. Tagged "apos": only in the per-kind lines, not the totals
        val aposN = System.getenv("DOGBOARD_APOSTROPHE_SET")?.toIntOrNull() ?: 0
        // its own random draws, so the main sample stays the same with or without it
        val apos = ArrayList<List<String>>(aposN); var seenApos = 0L; val aposSeen = HashSet<String>(); val aposRng = Random(11)
        pairs!!.bufferedReader().useLines { lines ->
            for (line in lines) {
                val row = line.split('\t')
                // the word fields hold letters only: apostrophe words are taken from the sentence before the row
                // (its context column), each with the words before it, once per sentence and position
                if (aposN > 0 && row.size == 5) {
                    val words = row[2].split(' ').filter { it.isNotEmpty() }
                    for ((j, w0) in words.withIndex()) {
                        val w = w0.trim('.', ',', '!', '?', ';', ':', '"', '(', ')')
                        if (w.none { it == '\'' || it == '’' } || !aposSeen.add("${row[0]}\t$j")) continue
                        val bare = w.filter { it != '\'' && it != '’' }
                        if (bare.isEmpty() || !bare.all { c -> c.lowercaseChar() in keys }) continue
                        val r = listOf(row[0], j.toString(), words.take(j).joinToString(" "), bare, w, "apos")
                        if (apos.size < aposN) apos.add(r) else (aposRng.nextDouble() * ++seenApos).toLong().let { if (it < aposN) apos[it.toInt()] = r }
                    }
                }
                if (row.size != 5 || row[3].isEmpty() || !row[3].all { c -> c.lowercaseChar() in keys }) continue
                val typo = !row[3].equals(row[4], ignoreCase = true)
                val (bucket, seen) = if (typo) typos to ++seenTypos else correct to ++seenCorrect
                if (bucket.size < n) bucket.add(row)
                else (rng.nextDouble() * seen).toLong().let { if (it < n) bucket[it.toInt()] = row }
            }
        }
        val sample = typos + correct + apos
        // DOGBOARD_RETRO=1: retrocorrection as the keyboard runs it when the next word is finished (the
        // next word as typed, from the same sentence); DOGBOARD_RETRO_DUMP=file writes the two-word
        // readings for the neural model (tmp/llm retro_scores.py), DOGBOARD_RETRO_NEURAL=file uses its
        // scores (ln P(previous + next | text before)) blended by DOGBOARD_RETRO_WEIGHT (default 0.5)
        val retro = System.getenv("DOGBOARD_RETRO") == "1"
        val nextTyped = HashMap<String, String>()
        if (retro) {
            val want = sample.map { "${it[0]}\t${it[1].toInt() + 1}" }.toHashSet()
            pairs.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val row = line.split('\t')
                    if (row.size == 5 && "${row[0]}\t${row[1]}" in want && row[3].isNotEmpty() && row[3].all { c -> c.lowercaseChar() in keys && c != ' ' }) nextTyped["${row[0]}\t${row[1]}"] = row[3].lowercase()
                }
            }
        }
        val retroDump = System.getenv("DOGBOARD_RETRO_DUMP")?.let { java.util.Collections.synchronizedList(ArrayList<String>()) }
        val retroNeural = System.getenv("DOGBOARD_RETRO_NEURAL")?.let { path ->
            File(path).useLines { lines -> lines.associate { l -> l.split('\t').let { (c, w, p) -> "$c\t$w" to p.toDouble() } } }
        }
        val retroWeight = System.getenv("DOGBOARD_RETRO_WEIGHT")?.toDouble() ?: 0.5
        val retroRatio = kotlin.math.ln(1.65)
        var retroChanged = java.util.concurrent.atomic.AtomicInteger()

        // DOGBOARD_DUMP_CANDIDATES=file: each case's top decoder candidates (word, score, geometry,
        // language score), for rescoring experiments with the neural model (tmp/llm)
        data class Outcome(val typo: Boolean, val result: String, val row: List<String>, val dump: String = "", val got: String = "")
        val dumpCandidates = System.getenv("DOGBOARD_DUMP_CANDIDATES")
        // DOGBOARD_NEURAL_SCORES=file (context, typed, word, ln P from the neural model; tmp/llm
        // neural_scores.py over a dump): candidates refined with the neural model as iOS does, weight
        // DOGBOARD_NEURAL_WEIGHT (default 0.75)
        val neuralScores = System.getenv("DOGBOARD_NEURAL_SCORES")?.let { path ->
            File(path).useLines { lines -> lines.associate { l -> l.split('\t').let { (c, t, w, p) -> "$c\t$t\t$w" to p.toDouble() } } }
        }
        val neuralWeight = System.getenv("DOGBOARD_NEURAL_WEIGHT")?.toDouble() ?: 0.75
        val pool = java.util.concurrent.ForkJoinPool(System.getenv("DOGBOARD_EVAL_THREADS")?.toIntOrNull() ?: 3)
        val outcomes = pool.submit(java.util.concurrent.Callable {
            sample.parallelStream().map { row ->
                val typed = row[3].lowercase(); val intended = row[4]
                // the true words before it; a sentence end restarts the context, as on the keyboard
                val raw = row[2].split(' ').filter { it.isNotEmpty() }
                val ends = { w: String -> w.last() in ".!?" }
                val clean = { w: String -> w.trim('.', ',', '!', '?', ';', ':', '"', '(', ')').lowercase() }
                val prev = raw.lastOrNull()?.takeUnless(ends)?.let(clean) ?: Bigrams.START
                val prev2 = raw.getOrNull(raw.size - 2)?.takeIf { prev != Bigrams.START && !ends(it) }?.let(clean)
                // taps at the key centres read as certain letters, which makes autocorrect shy;
                // DOGBOARD_REAL_TYPOS_SIGMA=1.5x2.0 (mm) adds the touch noise the other evals use
                val tapRng = Random(row[0].hashCode() * 31L + row[1].hashCode())
                // DOGBOARD_REAL_TYPOS_HAND=s (mm): a steady miss per sentence, drawn once from N(0, s) in x and y
                // (a hand that lands off the keys the same way, as iOS's touch history models), on top of the noise
                val hand = handMm?.let { s -> Random(row[0].hashCode().toLong()).let { r -> r.nextGaussian() * s to r.nextGaussian() * s } } ?: (0.0 to 0.0)
                val taps = typed.map { c ->
                    val k = keys.getValue(c)
                    Tap((k.centerX + (hand.first + tapRng.nextGaussian() * sigma.first) * GaussianTouch.POINTS_PER_MM).toFloat(),
                        (k.centerY + (hand.second + tapRng.nextGaussian() * sigma.second) * GaussianTouch.POINTS_PER_MM).toFloat())
                }
                val decoded = decoders.get().decode(taps, keys, params, previous = bigrams.contextId(prev), typed = typed, previous2 = prev2?.let { bigrams.contextId(it) } ?: -1)
                val candidates = neuralScores?.let { m ->
                    withNeural(decoded, { w -> m["${row[2]}\t$typed\t${w.replace(",", "")}"] }, neuralWeight, Decoder.LANGUAGE_POWER_CONTEXT)
                } ?: decoded
                var got = if (appPath) {
                    val word = CurrentWord().apply { typed.forEachIndexed { i, c -> addLetter(c, taps[i], keys, params) } }
                    TypingEngine.correction(word, candidates, level = AutocorrectLevel.valueOf(System.getenv("DOGBOARD_LEVEL") ?: "STANDARD")) ?: typed
                } else autocorrect.correction(typed, candidates) ?: typed
                val next = if (retro) nextTyped["${row[0]}\t${row[1].toInt() + 1}"] else null
                if (next != null) {
                    // as TypingEngine.retrocorrection: the previous word's top single words (decoder scores), each
                    // followed by the best reading of the next word in its context; a change must win by 1.65x
                    val d = decoders.get()
                    val prevCands = decoded.filter { !it.isCompletion && it.words == 1 }.take(4)
                    val committed = prevCands.firstOrNull { it.word.equals(got, ignoreCase = true) }
                    if (committed != null && prevCands.size > 1) {
                        val nTaps = next.map { c -> keys.getValue(c).let { k -> Tap((k.centerX + tapRng.nextGaussian() * sigma.first * GaussianTouch.POINTS_PER_MM).toFloat(), (k.centerY + tapRng.nextGaussian() * sigma.second * GaussianTouch.POINTS_PER_MM).toFloat()) } }
                        val cur = d.decode(nTaps, keys, params, maxResults = 4, includeCompletions = false, previous = bigrams.contextId(got.lowercase()), typed = next)
                            .filter { it.words == 1 && !it.isTypedString }.take(2)
                        fun joint(pc: Candidate): Double = cur.maxOfOrNull { c ->
                            val r = d.rescored(c, bigrams.contextId(pc.word.lowercase()), bigrams.contextId(prev))
                            val n = retroNeural?.get("${row[2]}\t${pc.word} ${c.word}")
                            if (n == null) pc.score + r.score
                            else pc.geometry + r.geometry + Decoder.LANGUAGE_POWER_CONTEXT * ((1 - retroWeight) * (pc.languageModel + r.languageModel) + retroWeight * n)
                        } ?: Double.NEGATIVE_INFINITY
                        retroDump?.let { out -> for (pc in prevCands) for (c in cur) out.add("${row[2]}\t${pc.word} ${c.word}") }
                        val base = joint(committed)
                        val best = prevCands.filter { it !== committed && !it.isTypedString }.map { it to joint(it) }.maxByOrNull { it.second }
                        if (best != null && best.second - base >= retroRatio) { got = best.first.word; retroChanged.incrementAndGet() }
                    }
                }
                val typo = !typed.equals(intended, ignoreCase = true)
                val result = when {
                    got.equals(intended, ignoreCase = true) -> if (typo) "fixed" else "kept"
                    got.equals(typed, ignoreCase = true) -> "left"
                    else -> if (typo) "wrong" else "broken"
                }
                val dump = if (dumpCandidates == null) "" else candidates.take(8).joinToString("|") {
                    "%s,%.3f,%.3f,%.3f".format(it.word.replace(",", ""), it.score, it.geometry, it.languageModel)
                }
                Outcome(typo, result, row, dump, got)
            }.toList()
        }).get()
        pool.shutdown()

        val main = outcomes.filter { it.row.size == 5 } // the totals leave out the synthetic apostrophe cases
        fun pct(kind: Boolean, r: String) = main.count { it.typo == kind && it.result == r } * 100.0 / main.count { it.typo == kind }
        println("REALTYPO ${handMm?.let { "hand $it mm " } ?: ""}${if (System.getenv("DOGBOARD_MISSED_SPACE") == "0") "no-missed-space " else ""}${if (charModel != null) "charlm " else ""}${if (System.getenv("DOGBOARD_MISSPELLINGS") == "1") "misspellings " else ""}${if (System.getenv("DOGBOARD_SPACE_KEY") == "1") "space-key ${System.getenv("DOGBOARD_SPACE_TAP") ?: Decoder.SPACE_TAP} " else ""}${if (neuralScores != null) "neural $neuralWeight " else ""}level ${System.getenv("DOGBOARD_LEVEL") ?: "STANDARD"} power ${System.getenv("DOGBOARD_LANGUAGE_POWER") ?: "level default"}, end-insertion %s, end-omission stem %s, sigma %.1fx%.1f mm, %d typos: fixed %.1f%%, left %.1f%%, wrong word %.1f%% | %d correct words: kept %.1f%%, broken %.1f%%".format(
            endInsertion ?: "default", System.getenv("DOGBOARD_END_OMISSION_STEM") ?: "off", sigma.first, sigma.second, main.count { it.typo }, pct(true, "fixed"), pct(true, "left"), pct(true, "wrong"),
            main.count { !it.typo }, pct(false, "kept"), pct(false, "broken")))
        if (retro) println("RETRO %d words had a next word, %d changed%s".format(outcomes.count { nextTyped.containsKey("${it.row[0]}\t${it.row[1].toInt() + 1}") }, retroChanged.get(),
            if (retroNeural != null) ", neural weight $retroWeight" else ""))
        retroDump?.let { File(System.getenv("DOGBOARD_RETRO_DUMP")).writeText(it.toSet().joinToString("\n", postfix = "\n")) }
        // per kind of word (benchmark review 2026-11-20: one total hides where a change helps or hurts): typos
        // fixed / left / wrong and correct words broken, for short words, capitalised words after others, words with an
        // apostrophe, words meant as two, and the rest
        fun kind(o: Outcome): String {
            val w = o.row[4]
            val midSentence = o.row[2].isNotBlank() && o.row[2].trimEnd().last() !in ".!?"
            return when {
                ' ' in w -> "split"
                '\'' in w || '’' in w -> "apostrophe"
                // capitalised after other words: mostly names, but sentence starts too where the data's context
                // has no closing mark ("Thanks", "If"), so not a clean names set
                w.first().isUpperCase() && midSentence -> "capital"
                w.length <= 3 -> "short"
                else -> "other"
            }
        }
        for ((k, os) in outcomes.groupBy(::kind).toSortedMap()) {
            val t = os.filter { it.typo }; val c = os.filter { !it.typo }
            fun p(l: List<Outcome>, r: String) = if (l.isEmpty()) 0.0 else l.count { it.result == r } * 100.0 / l.size
            println("REALTYPO-KIND %-10s %4d typos: fixed %5.1f%%, left %5.1f%%, wrong %5.1f%% | %4d correct: broken %.1f%%".format(
                k, t.size, p(t, "fixed"), p(t, "left"), p(t, "wrong"), c.size, p(c, "broken")))
        }
        // real-word slips: the typo is itself a word ("form" for "from"), only context can tell
        val realWord = outcomes.filter { it.typo && lexicon.contains(it.row[3].lowercase()) }
        if (realWord.isNotEmpty()) println("REALWORD %d real-word slips: fixed %.1f%%, left %.1f%%, wrong word %.1f%%".format(realWord.size,
            realWord.count { it.result == "fixed" } * 100.0 / realWord.size, realWord.count { it.result == "left" } * 100.0 / realWord.size,
            realWord.count { it.result == "wrong" } * 100.0 / realWord.size))
        dumpCandidates?.let { path ->
            File(path).printWriter().use { w ->
                for (o in outcomes) w.println("${o.row[2]}\t${o.row[3]}\t${o.row[4]}\t${o.result}\t${o.dump}")
            }
        }
        System.getenv("DOGBOARD_EVAL_ERRORS")?.let { path ->
            File(path).printWriter().use { w ->
                for (o in outcomes) if (o.result != "fixed" && o.result != "kept") w.println("${o.result}\t${o.row[3]}\t${o.row[4]}\t${o.got}\t${o.row[2].takeLast(40)}")
            }
        }
    }
})
