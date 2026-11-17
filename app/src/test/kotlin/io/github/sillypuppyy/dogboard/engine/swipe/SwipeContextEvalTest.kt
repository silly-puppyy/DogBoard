package io.github.sillypuppyy.dogboard.engine.swipe

import io.github.sillypuppyy.dogboard.engine.Bigrams
import io.github.sillypuppyy.dogboard.engine.Lexicon
import io.github.sillypuppyy.dogboard.engine.NgramModel
import io.github.sillypuppyy.dogboard.engine.NgramScorer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Swipe decoding with sentence context (iOS adds its LM in the beam: lmAlpha): FUTO test swipes with
 * the two words before them (tools/swipe/dump_eval.py), our lexicon and trigram. The weight is
 * chosen on the first half and reported on the second.
 *
 *     DOGBOARD_SWIPE_EVAL=swipe-eval.jsonl ./gradlew :app:testDebugUnitTest --tests '*SwipeContextEval*'
 */
class SwipeContextEvalTest {
    @Test
    fun context() {
        val path = System.getenv("DOGBOARD_SWIPE_EVAL")
        assumeTrue(path != null && File(path).isFile, "DOGBOARD_SWIPE_EVAL not set")
        val lexicon = File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
        val bigrams = File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) }
        val ngrams = File("src/main/assets/dogboard/en.ngrams.bin").inputStream().use { NgramModel.readBinary(it) }!!
        val scorer = NgramScorer(ngrams, lexicon, bigrams.sentenceStart)
        class Item(val word: String, val prev2: Int, val prev: Int, val cands: List<SwipeBeam.Candidate>)
        val items = File(path!!).readLines().map { line ->
            val o = Json.parseToJsonElement(line).jsonObject
            val prev = o["prev"]!!.jsonArray.map { it.jsonPrimitive.content }
            fun id(w: String) = lexicon.find(w).let { if (it == Lexicon.NONE || !lexicon.isWord(it)) -1 else lexicon.wordId(it) }
            val start = o["start"]!!.jsonPrimitive.boolean
            val p1 = if (start) bigrams.sentenceStart else prev.lastOrNull()?.let(::id) ?: -1
            val p2 = if (start) -1 else if (prev.size >= 2) id(prev[0]) else if (prev.size == 1) bigrams.sentenceStart else -1
            val logp = o["logp"]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
            Item(o["word"]!!.jsonPrimitive.content, p2, p1, SwipeBeam.decode(logp, logp.size / 27, lexicon, top = 10))
        }
        val half = items.size / 2
        // score = path (beam score without the prior) + w * ln P(word | two before) [+ u * ln P(word)]
        fun accuracy(list: List<Item>, w: Double, u: Double): Pair<Double, Double> {
            var t1 = 0; var t3 = 0
            for (it in list) {
                val ranked = it.cands.sortedByDescending { c ->
                    val uni = lexicon.logProb(c.node)
                    val path = c.score - uni
                    val ctx = if (w == 0.0) 0.0 else scorer.logProb(it.prev2, it.prev, lexicon.wordId(c.node), uni)
                    path + w * ctx + u * uni
                }.map { c -> lexicon.word(c.node)!!.lowercase() }
                if (ranked.firstOrNull() == it.word) t1++
                if (it.word in ranked.take(3)) t3++
            }
            return t1.toDouble() / list.size to t3.toDouble() / list.size
        }
        val tune = items.take(half); val test = items.drop(half)
        val (b1, b3) = accuracy(test, 0.0, 1.0)
        println("SWIPECTX unigram only (test half, n ${test.size}): top-1 ${"%.3f".format(b1)} top-3 ${"%.3f".format(b3)}")
        var best = Triple(0.0, 0.0, -1.0)
        for (w in listOf(0.3, 0.5, 0.7, 1.0, 1.3)) for (u in listOf(0.0, 0.3)) {
            val (a1, _) = accuracy(tune, w, u)
            if (a1 > best.third) best = Triple(w, u, a1)
        }
        val (c1, c3) = accuracy(test, best.first, best.second)
        println("SWIPECTX trigram weight ${best.first} + unigram ${best.second} (chosen on the first half): top-1 ${"%.3f".format(c1)} top-3 ${"%.3f".format(c3)}")
        val inTop10 = test.count { t -> t.cands.any { lexicon.word(it.node)!!.lowercase() == t.word } }.toDouble() / test.size
        println("SWIPECTX right word anywhere in the beam's 10: ${"%.3f".format(inTop10)}")
    }
}
