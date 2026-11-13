package io.github.sillypuppyy.dogboard.engine

import java.io.BufferedReader
import kotlin.math.exp
import kotlin.math.ln

/**
 * Word list with frequencies, stored as a trie over lowercase characters. Each word keeps its
 * own casing ("I", "I'm"), and each node knows the most frequent word below it, for completions.
 *
 * File format, see tools/data/lexicon_from_florisboard.py:
 *     #dogboard-lexicon 1
 *     word<TAB>count
 */
class Lexicon private constructor(val root: Node, private val words: ArrayList<Node>) {
    val size: Int get() = words.size

    /** The word node with id [id] (ids are 0 until [size]). */
    fun node(id: Int): Node = words[id]

    /**
     * Adds [word] at probability [logProb], or raises an existing word to it; returns its id.
     * Used for words the user taught the keyboard. Not thread-safe: callers must lock.
     */
    fun addWord(word: String, logProb: Double): Int {
        val path = ArrayList<Node>(word.length + 1)
        var node = root
        path.add(node)
        for (c in word) {
            node = node.childOrCreate(c.lowercaseChar())
            path.add(node)
        }
        val added = when {
            !node.isWord -> {
                node.id = words.size
                words.add(node)
                node.word = word
                node.logProb = logProb
                exp(logProb)
            }
            logProb > node.logProb -> exp(logProb) - exp(node.logProb).also { node.logProb = logProb }
            else -> 0.0
        }
        for (n in path) n.mass += added
        for (n in path) {
            val best = n.bestWord
            if (best == null || node.logProb > best.logProb) n.bestWord = node
        }
        return node.id
    }

    /**
     * ln P(next letter | [prefix]) for each letter that continues a known word, from the word
     * probabilities below the prefix; null if no known word starts with [prefix].
     */
    fun nextLetterLogProbs(prefix: CharSequence): Map<Char, Double>? {
        var node = root
        for (c in prefix) node = node.child(c.lowercaseChar()) ?: return null
        if (node.mass <= 0.0) return null
        return buildMap {
            for (i in 0 until node.childCount) {
                val child = node.childAt(i)
                if (child.mass > 0.0) put(node.keyAt(i), ln(child.mass / node.mass))
            }
        }
    }

    /** Id of [word] (case-insensitive), or -1. */
    fun idOf(word: String): Int = find(word)?.takeIf { it.isWord }?.id ?: -1

    class Node(val depth: Int) {
        private var keys = CharArray(0)
        private var nodes = arrayOfNulls<Node>(0)

        /** The word ending here, in its stored casing, or null. */
        var word: String? = null
            internal set

        /** ln P(word), or NaN if no word ends here. */
        var logProb: Double = Double.NaN
            internal set

        /** Most frequent word at or below this node. */
        var bestWord: Node? = null
            internal set

        /** Total probability of the words at or below this node. */
        var mass: Double = 0.0
            internal set

        /** Index of the word ending here, or -1. */
        var id: Int = -1
            internal set

        val isWord: Boolean get() = word != null
        val childCount: Int get() = keys.size
        fun keyAt(i: Int): Char = keys[i]
        fun childAt(i: Int): Node = nodes[i]!!

        fun child(c: Char): Node? {
            for (i in keys.indices) if (keys[i] == c) return nodes[i]
            return null
        }

        internal fun childOrCreate(c: Char): Node {
            child(c)?.let { return it }
            val node = Node(depth + 1)
            keys += c
            nodes = nodes.copyOf(nodes.size + 1).also { it[it.size - 1] = node }
            return node
        }
    }

    /** ln P(word) for an exact (case-insensitive) match, or null if [word] is not in the lexicon. */
    fun logProb(word: String): Double? = find(word)?.logProb?.takeUnless { it.isNaN() }

    /** The stored form of [word] ("i'm" -> "I'm"), or null. */
    fun storedForm(word: String): String? = find(word)?.word

    fun contains(word: String): Boolean = find(word)?.isWord == true

    private fun find(word: String): Node? {
        var node = root
        for (c in word) node = node.child(c.lowercaseChar()) ?: return null
        return node
    }

    companion object {
        const val FORMAT_HEADER = "#dogboard-lexicon 1"

        fun of(counts: Map<String, Long>): Lexicon {
            val total = counts.values.sum().toDouble()
            val root = Node(0)
            val words = ArrayList<Node>()
            for ((word, count) in counts) {
                if (word.isEmpty() || count <= 0) continue
                var node = root
                for (c in word) node = node.childOrCreate(c.lowercaseChar())
                val lp = ln(count / total)
                if (node.word == null || lp > node.logProb) {
                    if (node.word == null) {
                        node.id = words.size
                        words.add(node)
                    }
                    node.word = word
                    node.logProb = lp
                }
            }
            computeBest(root)
            return Lexicon(root, words)
        }

        fun parse(reader: BufferedReader): Lexicon {
            val header = reader.readLine()
            require(header == FORMAT_HEADER) { "not a DogBoard lexicon (header: $header)" }
            val counts = LinkedHashMap<String, Long>()
            reader.forEachLine { line ->
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val tab = line.indexOf('\t')
                require(tab > 0) { "bad lexicon line: $line" }
                counts[line.substring(0, tab)] = line.substring(tab + 1).trim().toLong()
            }
            return of(counts)
        }

        private fun computeBest(node: Node): Node? {
            var best: Node? = if (node.isWord) node else null
            var mass = if (node.isWord) exp(node.logProb) else 0.0
            for (i in 0 until node.childCount) {
                val child = node.childAt(i)
                val b = computeBest(child)
                mass += child.mass
                if (b == null) continue
                if (best == null || b.logProb > best.logProb) best = b
            }
            node.bestWord = best
            node.mass = mass
            return best
        }
    }
}
