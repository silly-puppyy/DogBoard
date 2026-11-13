package io.github.sillypuppyy.dogboard.engine

import java.io.BufferedReader
import kotlin.math.ln

/**
 * Word list with frequencies, stored as a trie over lowercase characters. Each word keeps its
 * own casing ("I", "I'm"), and each node knows the most frequent word below it, for completions.
 *
 * File format, see tools/data/lexicon_from_florisboard.py:
 *     #dogboard-lexicon 1
 *     word<TAB>count
 */
class Lexicon private constructor(val root: Node, private val words: Array<Node>) {
    val size: Int get() = words.size

    /** The word node with id [id] (ids are 0 until [size]). */
    fun node(id: Int): Node = words[id]

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
            return Lexicon(root, words.toTypedArray())
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
            for (i in 0 until node.childCount) {
                val b = computeBest(node.childAt(i)) ?: continue
                if (best == null || b.logProb > best.logProb) best = b
            }
            node.bestWord = best
            return best
        }
    }
}
