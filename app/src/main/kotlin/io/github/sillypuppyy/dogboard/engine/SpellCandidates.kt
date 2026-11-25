package io.github.sillypuppyy.dogboard.engine

/**
 * Words spelled like a typed word the lexicon lacks, whichever keys were hit: within two letter edits (insert, delete,
 * substitute, swap neighbours), fewest edits first, then the most frequent. The touch decoder reads taps near keys and
 * never reaches a word whose letters are out of order ("rethorical": the h moved two places; it chose "methodical").
 * Gboard offers such words as chips when the cursor returns into an unknown word (emulator, 2026-11-25: rhetorical /
 * historical / electrical). On the real phone typos the engine left or got wrong as non-words (Aalto, 427 cases),
 * the meant word is first 64 % of the time and among three 84 % (tools/data/spell_candidates_eval.py).
 *
 * A walk of the lexicon's trie with the edit table's rows (optimal string alignment), cut where every cell exceeds
 * the bound: no index in memory.
 */
object SpellCandidates {
    const val MAX_EDITS = 2

    fun of(lexicon: Lexicon, typed: String, max: Int = 3): List<String> {
        // the trie's letters: lowercase, accents folded, ligatures spelled out (as Lexicon.find reads a prefix)
        val t = buildString { for (c in typed) Lexicon.ligature(c)?.let { append(it) } ?: append(Lexicon.fold(c)) }
        val n = t.length
        if (n < 2 || n > 40) return emptyList()
        val found = ArrayList<Triple<Int, Double, Int>>() // edits, log probability, node
        fun walk(node: Int, prev: IntArray, prevPrev: IntArray?, prevKey: Char) {
            var k = lexicon.firstChild(node)
            while (k != Lexicon.NONE) {
                val c = lexicon.key(k)
                val row = IntArray(n + 1)
                row[0] = prev[0] + 1
                var least = row[0]
                for (j in 1..n) {
                    var v = minOf(prev[j] + 1, row[j - 1] + 1, prev[j - 1] + if (t[j - 1] == c) 0 else 1)
                    if (prevPrev != null && j > 1 && t[j - 1] == prevKey && t[j - 2] == c) v = minOf(v, prevPrev[j - 2] + 1)
                    row[j] = v
                    if (v < least) least = v
                }
                if (row[n] <= MAX_EDITS && lexicon.isWord(k)) found.add(Triple(row[n], lexicon.logProb(k), k))
                if (least <= MAX_EDITS) walk(k, row, prev, c)
                k = lexicon.nextSibling(k)
            }
        }
        walk(Lexicon.ROOT, IntArray(n + 1) { it }, null, ' ')
        return found.asSequence()
            .filter { it.first > 0 }
            .sortedWith(compareBy<Triple<Int, Double, Int>> { it.first }.thenByDescending { it.second })
            .mapNotNull { lexicon.firstForm(it.third) }
            .distinct()
            .take(max)
            .toList()
    }
}
