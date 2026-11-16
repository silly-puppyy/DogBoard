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
        val lexicon = File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
        val bigrams = File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) }
        val ngrams = ngramFile!!.inputStream().use { NgramModel.readBinary(it) }!!
        // DOGBOARD_SPACE_KEY=1: the space bar among the keys a tap may have meant (word breaks at a tap)
        val keys = if (System.getenv("DOGBOARD_SPACE_KEY") == "1") QwertyGeometry.keysWithSpace(375f, 216f) else QwertyGeometry.letterKeys(375f, 216f)
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        // DOGBOARD_END_OMISSION_STEM=n: Autocorrect.Level.minStemEndOmission (off by default, as iOS)
        // DOGBOARD_LEVEL=GENTLE|STANDARD|STRONG (default STANDARD), DOGBOARD_LANGUAGE_POWER overrides its power
        val base = AutocorrectLevel.valueOf(System.getenv("DOGBOARD_LEVEL") ?: "STANDARD").level
        val autocorrect = Autocorrect(lexicon, base.copy(languagePower = System.getenv("DOGBOARD_LANGUAGE_POWER")?.toDoubleOrNull() ?: base.languagePower,minStemEndOmission = (System.getenv("DOGBOARD_END_OMISSION_STEM")?.toIntOrNull() ?: Autocorrect.Level.STANDARD.minStemEndOmission)))
        val sigma = System.getenv("DOGBOARD_REAL_TYPOS_SIGMA")?.split('x')?.let { (a, b) -> a.toDouble() to b.toDouble() } ?: (0.0 to 0.0)
        // DOGBOARD_END_INSERTION=cost: the skipped-key cost after the last tap (Decoder.Config.endInsertion)
        val endInsertion = (System.getenv("DOGBOARD_END_INSERTION")?.toDoubleOrNull() ?: Decoder.END_INSERTION)
        // DOGBOARD_CHARLM=1: the unknown-word letter model attached, as the app ships (assets en.charlm.bin)
        // DOGBOARD_MISSPELLINGS=1: known misspellings never scored by the letter model (assets en.misspellings.txt)
        val misspellings = File("src/main/assets/dogboard/en.misspellings.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.toHashSet()
        val charModel = if (System.getenv("DOGBOARD_CHARLM") == "1") File("src/main/assets/dogboard/en.charlm.bin").inputStream().use { CharModel.read(it) } else null
        val decoders = ThreadLocal.withInitial {
            Decoder(lexicon, Decoder.Config(endInsertion = endInsertion, spaceTap = System.getenv("DOGBOARD_SPACE_TAP")?.toDouble() ?: Decoder.SPACE_TAP), bigrams = bigrams, context = NgramScorer(ngrams, lexicon, bigrams.sentenceStart))
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
        pairs!!.bufferedReader().useLines { lines ->
            for (line in lines) {
                val row = line.split('\t')
                if (row.size != 5 || row[3].isEmpty() || !row[3].all { c -> c.lowercaseChar() in keys }) continue
                val typo = !row[3].equals(row[4], ignoreCase = true)
                val (bucket, seen) = if (typo) typos to ++seenTypos else correct to ++seenCorrect
                if (bucket.size < n) bucket.add(row)
                else (rng.nextDouble() * seen).toLong().let { if (it < n) bucket[it.toInt()] = row }
            }
        }
        val sample = typos + correct

        // DOGBOARD_DUMP_CANDIDATES=file: each case's top decoder candidates (word, score, geometry,
        // language score), for rescoring experiments with the neural model (tmp/llm)
        data class Outcome(val typo: Boolean, val result: String, val row: List<String>, val dump: String = "")
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
                val taps = typed.map { c ->
                    val k = keys.getValue(c)
                    Tap((k.centerX + tapRng.nextGaussian() * sigma.first * GaussianTouch.POINTS_PER_MM).toFloat(),
                        (k.centerY + tapRng.nextGaussian() * sigma.second * GaussianTouch.POINTS_PER_MM).toFloat())
                }
                val decoded = decoders.get().decode(taps, keys, params, previous = bigrams.contextId(prev), typed = typed, previous2 = prev2?.let { bigrams.contextId(it) } ?: -1)
                val candidates = neuralScores?.let { m ->
                    withNeural(decoded, { w -> m["${row[2]}\t$typed\t${w.replace(",", "")}"] }, neuralWeight, Decoder.LANGUAGE_POWER_CONTEXT)
                } ?: decoded
                val got = autocorrect.correction(typed, candidates) ?: typed
                val typo = !typed.equals(intended, ignoreCase = true)
                val result = when {
                    got.equals(intended, ignoreCase = true) -> if (typo) "fixed" else "kept"
                    got.equals(typed, ignoreCase = true) -> "left"
                    else -> if (typo) "wrong" else "broken"
                }
                val dump = if (dumpCandidates == null) "" else candidates.take(8).joinToString("|") {
                    "%s,%.3f,%.3f,%.3f".format(it.word.replace(",", ""), it.score, it.geometry, it.languageModel)
                }
                Outcome(typo, result, row, dump)
            }.toList()
        }).get()
        pool.shutdown()

        fun pct(kind: Boolean, r: String) = outcomes.count { it.typo == kind && it.result == r } * 100.0 / outcomes.count { it.typo == kind }
        println("REALTYPO ${if (charModel != null) "charlm " else ""}${if (System.getenv("DOGBOARD_MISSPELLINGS") == "1") "misspellings " else ""}${if (System.getenv("DOGBOARD_SPACE_KEY") == "1") "space-key ${System.getenv("DOGBOARD_SPACE_TAP") ?: Decoder.SPACE_TAP} " else ""}${if (neuralScores != null) "neural $neuralWeight " else ""}level ${System.getenv("DOGBOARD_LEVEL") ?: "STANDARD"} power ${System.getenv("DOGBOARD_LANGUAGE_POWER") ?: "level default"}, end-insertion %s, end-omission stem %s, sigma %.1fx%.1f mm, %d typos: fixed %.1f%%, left %.1f%%, wrong word %.1f%% | %d correct words: kept %.1f%%, broken %.1f%%".format(
            endInsertion ?: "default", System.getenv("DOGBOARD_END_OMISSION_STEM") ?: "off", sigma.first, sigma.second, outcomes.count { it.typo }, pct(true, "fixed"), pct(true, "left"), pct(true, "wrong"),
            outcomes.count { !it.typo }, pct(false, "kept"), pct(false, "broken")))
        dumpCandidates?.let { path ->
            File(path).printWriter().use { w ->
                for (o in outcomes) w.println("${o.row[2]}\t${o.row[3]}\t${o.row[4]}\t${o.result}\t${o.dump}")
            }
        }
        System.getenv("DOGBOARD_EVAL_ERRORS")?.let { path ->
            File(path).printWriter().use { w ->
                for (o in outcomes) if (o.result != "fixed" && o.result != "kept") w.println("${o.result}\t${o.row[3]}\t${o.row[4]}\t${o.row[2].takeLast(40)}")
            }
        }
    }
})
