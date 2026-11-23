package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * Trains the per-word language model with both kinds of evidence (user 2026-11-23: "recognise words/characters in
 * context individually? Best of both worlds"): for "this language" against "the other", the word's listed / ln P / letter
 * shape, each of the two words before it individually (listed / ln P), and the last [WINDOW] characters under each
 * language's letter model. One logistic model for every pair, trained on mixed text with switches; held-out pairs report
 * how well it carries over. Writes the weights as JSON for MultilingualEvalTest (DOGBOARD_ML_LANGID2).
 *
 * DOGBOARD_LANGID_TRAIN / DOGBOARD_LANGID_TEST = "dirA:a+dirB:b,..." (each dir: <lang>.lexicon.tsv, .heldout.txt,
 * .charlm.bin), DOGBOARD_LANGID_OUT = the JSON file.
 */
class LangIdTrainTest : FunSpec({
    val train = System.getenv("DOGBOARD_LANGID_TRAIN")
    val test = System.getenv("DOGBOARD_LANGID_TEST")
    val out = System.getenv("DOGBOARD_LANGID_OUT")

    class L(val dir: String, val tag: String) {
        val lexicon = File(dir, "$tag.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
        val chars = File(dir, "$tag.charlm.bin").takeIf { it.isFile }?.inputStream()?.use { CharModel.read(it) }
        val held: List<List<String>> by lazy {
            val word = Regex("\\p{L}+(?:'\\p{L}+)*")
            File(dir, "$tag.heldout.txt").useLines { ls -> ls.map { l -> word.findAll(l.replace('’', '\'')).map { it.value.lowercase() }.toList() }.filter { it.size >= 6 }.take(6000).toList() }
        }
    }

    test("train the language model").config(enabled = train != null && out != null, timeout = kotlin.time.Duration.parse("2h")) {
        fun pairs(spec: String) = spec.split(',').map { p -> p.split('+').map { s -> s.split(':').let { L(it[0], it[1]) } } }
        val trainPairs = pairs(train!!); val testPairs = test?.let { pairs(it) }.orEmpty()

        /** Mixed sentences: (words, label 0 = first language, 1 = second, words since the switch or -1). */
        fun mixed(a: L, b: L, n: Int, seed: Int): List<Triple<List<String>, List<Int>, Int>> {
            val rnd = java.util.Random(seed.toLong())
            return (0 until minOf(n, a.held.size, b.held.size)).map { i ->
                val (x, y, lx, ly) = if (i % 2 == 0) listOf(a.held[i], b.held[i], 0, 1) else listOf(b.held[i], a.held[i], 1, 0)
                @Suppress("UNCHECKED_CAST") x as List<String>; @Suppress("UNCHECKED_CAST") y as List<String>
                val cx = 2 + rnd.nextInt(maxOf(1, x.size / 2 - 1)); val cy = rnd.nextInt(maxOf(1, y.size / 2))
                Triple(x.take(cx) + y.drop(cy), List(cx) { lx as Int } + List(y.size - cy) { ly as Int }, cx)
            }
        }

        fun lnp(l: L, w: String) = l.lexicon.logProb(w)
        fun shape(l: L, w: String) = l.chars?.logProb(w)
        /** Features of x against o for word i of [words]. */
        fun feats(words: List<String>, i: Int, x: L, o: L): DoubleArray {
            val f = DoubleArray(FEATURES)
            val w = words[i]
            val px = lnp(x, w); val po = lnp(o, w)
            f[0] = (if (px != null) 1.0 else 0.0) - (if (po != null) 1.0 else 0.0)
            f[1] = ((px ?: FLOOR) - (po ?: FLOOR)) / 10
            val sx = shape(x, w); val so = shape(o, w)
            f[2] = if (sx != null && so != null) (sx - so) / w.length else 0.0
            for (j in 1..2) {
                val k = i - j
                if (k < 0) continue
                val qx = lnp(x, words[k]); val qo = lnp(o, words[k])
                f[1 + 2 * j] = (if (qx != null) 1.0 else 0.0) - (if (qo != null) 1.0 else 0.0)
                f[2 + 2 * j] = ((qx ?: FLOOR) - (qo ?: FLOOR)) / 10
            }
            // the last WINDOW characters before the word, under each letter model (per letter)
            val text = words.subList(0, i).joinToString(" ").takeLast(WINDOW)
            var d = 0.0; var n = 0
            for (t in text.split(' ').filter { it.isNotEmpty() }) {
                val a = shape(x, t) ?: continue; val b = shape(o, t) ?: continue
                d += a - b; n += t.length
            }
            f[7] = if (n == 0) 0.0 else d / n
            return f
        }

        data class Ex(val f: DoubleArray, val y: Int, val since: Int)
        fun examples(a: L, b: L, n: Int, seed: Int) = mixed(a, b, n, seed).flatMap { (words, labels, cut) ->
            words.indices.flatMap { i ->
                val f = feats(words, i, a, b)
                val since = if (i >= cut) i - cut else -1
                // both orientations: symmetric, no side favoured
                listOf(Ex(f, 1 - labels[i], since), Ex(DoubleArray(FEATURES) { -f[it] }, labels[i], since))
            }
        }

        val data = trainPairs.flatMap { (a, b) -> examples(a, b, 4000, 1) }
        // each feature on the same scale while training (the letter-model features are far smaller than ln P: unscaled,
        // gradient descent barely moved their weights), the weights scaled back afterwards
        val sd = DoubleArray(FEATURES) { k -> kotlin.math.sqrt(data.sumOf { it.f[k] * it.f[k] } / data.size).takeIf { it > 1e-9 } ?: 1.0 }
        val w = DoubleArray(FEATURES)
        repeat(600) {
            val g = DoubleArray(FEATURES)
            for (e in data) {
                val z = e.f.indices.sumOf { w[it] * e.f[it] / sd[it] }
                val p = 1 / (1 + kotlin.math.exp(-z))
                for (k in g.indices) g[k] += (p - e.y) * e.f[k] / sd[k]
            }
            for (k in w.indices) w[k] -= 0.5 * (g[k] / data.size + 1e-4 * w[k])
        }
        for (k in w.indices) w[k] /= sd[k]
        println("LANGID feature scales: " + NAMES.zip(sd.toList()).joinToString { (n, v) -> "%s %.3f".format(n, v) })
        println("LANGID weights: " + NAMES.zip(w.toList()).joinToString { (n, v) -> "%s %+.2f".format(n, v) })
        val report = StringBuilder()
        for ((a, b) in trainPairs + testPairs) {
            val ex = examples(a, b, 2000, 9)
            fun acc(xs: List<Ex>) = 100.0 * xs.count { e -> (e.f.indices.sumOf { w[it] * e.f[it] } > 0) == (e.y == 1) } / xs.size
            val line = "%s x %s%s: all %.1f %%, right after a switch %.1f %%".format(a.tag, b.tag, if (testPairs.any { it[0] === a }) " (held out)" else "",
                acc(ex), acc(ex.filter { it.since in 0..2 }))
            println("LANGID $line"); report.append(line).append('\n')
        }
        File(out!!).writeText("{\"features\": [${NAMES.joinToString { "\"$it\"" }}], \"weights\": [${w.joinToString()}], \"window\": $WINDOW}\n")
    }
}) {
    companion object {
        const val FEATURES = 8
        const val FLOOR = -22.0
        const val WINDOW = 30
        val NAMES = listOf("listed_w0", "lnp_w0", "shape_w0", "listed_w-1", "lnp_w-1", "listed_w-2", "lnp_w-2", "chars_30")
    }
}
