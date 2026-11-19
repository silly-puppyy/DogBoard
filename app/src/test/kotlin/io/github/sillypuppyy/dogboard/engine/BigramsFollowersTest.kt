package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Bigrams.followers (primitive top n, 2026-11-19) keeps the old order exactly, ties included. */
class BigramsFollowersTest : FunSpec({
    // the earlier implementation: a Pair per follower, stable sort after each insert
    fun reference(entries: List<Pair<Int, Int>>, n: Int): List<Pair<Int, Int>> {
        val best = ArrayList<Pair<Int, Int>>(n + 1)
        for (entry in entries.sortedBy { it.first }) {
            if (best.size < n || entry.second > best.last().second) {
                best.add(entry)
                best.sortByDescending { it.second }
                if (best.size > n) best.removeAt(best.size - 1)
            }
        }
        return best
    }

    test("same followers in the same order as the earlier implementation, with many ties") {
        val words = (0 until 60).map { "w" + ('a' + it / 26) + ('a' + it % 26) }
        val lexicon = Lexicon.of(words.withIndex().associate { (i, w) -> w to (1000L - i) })
        val rnd = java.util.Random(7)
        repeat(30) { round ->
            val previous = words[round]
            val pairs = words.shuffled(rnd).take(5 + rnd.nextInt(50)).associateWith { 1 + rnd.nextInt(4) } // counts 1-4: ties
            val text = "#dogboard-bigrams 1\n" + pairs.entries.joinToString("\n") { (w, c) -> "$previous\t$w\t$c" } + "\n"
            val bigrams = Bigrams.parse(text.reader().buffered(), lexicon)
            val prev = bigrams.contextId(previous)
            val entries = pairs.entries.map { (w, c) -> lexicon.idOf(w) to c }
            for (n in listOf(1, 3, 6, 100)) bigrams.followers(prev, n) shouldBe reference(entries, n)
        }
    }
})
