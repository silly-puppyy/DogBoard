package io.github.sillypuppyy.dogboard.engine

import java.io.BufferedReader
import kotlin.math.exp
import kotlin.math.ln

/**
 * Word list with frequencies, stored as a trie over lowercase characters in flat arrays (about
 * 30 bytes per node, so a 300k-word list fits in about 20 MB). A node is an Int index; [ROOT] is
 * the empty prefix and [NONE] means "no node". Each word keeps its own casing ("I", "Paris"), and
 * each node knows the most frequent word below it, for completions.
 *
 * File format, see tools/data/build_lexicon.py:
 *     #dogboard-lexicon 1
 *     word<TAB>count
 */
class Lexicon private constructor(capacity: Int) {
    private var key = CharArray(capacity)
    private var firstChild = IntArray(capacity)
    private var nextSibling = IntArray(capacity)
    private var parent = IntArray(capacity)
    private var depth = ShortArray(capacity)
    private var logProb = FloatArray(capacity)
    private var mass = FloatArray(capacity)
    private var best = IntArray(capacity)
    private var wordId = IntArray(capacity)
    private var nodeCount = 0

    /** Node of each word, by word id. */
    private var wordNode = IntArray(capacity / 2 + 16)
    private var wordCount = 0

    /** Surface forms that differ from the lowercase path ("Paris", "I'm"), by node. */
    private val surface = HashMap<Int, String>()

    /**
     * Nodes stored in more than one casing ("google" and "Google"): each casing with its own
     * ln P; the node's [logProb] is their total and [surface] the most likely casing.
     */
    private val casings = HashMap<Int, List<Pair<String, Float>>>()

    // which nodes have an entry in [surface] / [casings]: most don't, and a map lookup boxes the
    // node (the decoder asks for every word state on every keystroke)
    private val hasSurface = java.util.BitSet()
    private val hasCasings = java.util.BitSet()

    init {
        newNode(NONE, '\u0000')
    }

    val size: Int get() = wordCount
    val nodes: Int get() = nodeCount

    /** Changes whenever a word is added or raised (searches over the trie start again). */
    var version = 0
        private set

    fun firstChild(node: Int): Int = firstChild[node]
    fun nextSibling(node: Int): Int = nextSibling[node]
    fun key(node: Int): Char = key[node]
    fun depth(node: Int): Int = depth[node].toInt()
    fun isWord(node: Int): Boolean = wordId[node] != NONE

    /** ln P(word) of the word ending at [node], or NaN. */
    fun logProb(node: Int): Double = if (wordId[node] == NONE) Double.NaN else logProb[node].toDouble()

    /** Total probability of the words at or below [node]. */
    fun mass(node: Int): Double = mass[node].toDouble()

    /** Node of the most frequent word at or below [node], or [NONE]. */
    fun bestWord(node: Int): Int = best[node]

    /** Id of the word ending at [node] (0 until [size]), or [NONE]. */
    fun wordId(node: Int): Int = wordId[node]

    /** Node of the word with id [id]. */
    fun nodeOfWord(id: Int): Int = wordNode[id]

    // each word's string once made (by word id): the decoder asks for the same few hundred words on
    // every keystroke, and rebuilding them was the typing path's largest garbage. Readers share the
    // engine's lock, so two may fill an entry at once: harmless, strings are immutable
    @Volatile private var wordCache = arrayOfNulls<String>(0)

    /** The word ending at [node] in its stored casing, or null. */
    fun word(node: Int): String? {
        val id = wordId[node]
        if (id == NONE) return null
        val cache = wordCache
        if (id < cache.size) cache[id]?.let { return it }
        return buildWord(node).also { w ->
            val c = if (id < cache.size) cache else cache.copyOf(maxOf(id + 1, wordCount, cache.size * 2)).also { wordCache = it }
            c[id] = w
        }
    }

    private fun buildWord(node: Int): String {
        if (hasSurface.get(node)) surface[node]?.let { return it }
        val chars = CharArray(depth[node].toInt())
        var n = node
        for (i in chars.indices.reversed()) {
            chars[i] = key[n]
            n = parent[n]
        }
        return String(chars)
    }

    /** The word at [node] has several casings ([forms] lists them); otherwise its one form is [word]. */
    fun hasCasings(node: Int): Boolean = hasCasings.get(node)

    /** Every casing of the word at [node] with its own ln P, most likely first. */
    fun forms(node: Int): List<Pair<String, Double>> {
        if (wordId[node] == NONE) return emptyList()
        if (hasCasings.get(node)) casings[node]?.let { list -> return list.map { (w, lp) -> w to lp.toDouble() } }
        return listOf(word(node)!! to logProb[node].toDouble())
    }

    /** The most likely casing of the word at [node] (forms(node).first() without the list), or null. */
    fun firstForm(node: Int): String? = when {
        wordId[node] == NONE -> null
        hasCasings.get(node) -> casings[node]?.firstOrNull()?.first ?: word(node)
        else -> word(node)
    }

    /** Every stored casing of [word] (case-insensitive lookup). */
    fun formsOf(word: String): List<String> = find(word).takeIf { it != NONE }?.let { n -> forms(n).map { it.first } }.orEmpty()

    /** Calls [action] with the letter and node of each child of [node]. */
    inline fun forEachChild(node: Int, action: (key: Char, child: Int) -> Unit) {
        var k = firstChild(node)
        while (k != NONE) {
            action(key(k), k)
            k = nextSibling(k)
        }
    }

    /** The child of [node] for letter [c] (lowercase), or [NONE]. */
    fun child(node: Int, c: Char): Int {
        var k = firstChild[node]
        while (k != NONE) {
            if (key[k] == c) return k
            k = nextSibling[k]
        }
        return NONE
    }

    /** ln P(word) for an exact (case-insensitive) match, or null if [word] is not in the lexicon. */
    fun logProb(word: String): Double? = find(word).takeIf { it != NONE && isWord(it) }?.let { logProb(it) }

    /** The stored form of [word] ("i'm" -> "I'm"), or null. */
    fun storedForm(word: String): String? = find(word).takeIf { it != NONE }?.let { word(it) }

    fun contains(word: String): Boolean = find(word).let { it != NONE && isWord(it) }

    /** Id of [word] (case-insensitive), or -1. */
    fun idOf(word: String): Int = find(word).let { if (it == NONE) NONE else wordId[it] }

    /** The node for [prefix] (case-insensitive), or [NONE]. */
    fun find(prefix: CharSequence): Int {
        var node = ROOT
        for (c in prefix) {
            node = child(node, fold(c))
            if (node == NONE) return NONE
        }
        return node
    }

    /**
     * ln P(next letter | [prefix]) for each letter that continues a known word, from the word
     * probabilities below the prefix; null if no known word starts with [prefix].
     */
    fun nextLetterLogProbs(prefix: CharSequence): Map<Char, Double>? {
        val node = find(prefix)
        if (node == NONE || mass[node] <= 0f) return null
        val total = mass[node].toDouble()
        return buildMap {
            var k = firstChild[node]
            while (k != NONE) {
                if (mass[k] > 0f) put(key[k], ln(mass[k] / total))
                k = nextSibling[k]
            }
        }
    }

    /**
     * Adds [word] at probability [logProb], or raises an existing word to it; returns its id.
     * Used for words the user taught the keyboard. Not thread-safe: callers must lock.
     */
    fun addWord(word: String, logProb: Double): Int {
        version++
        var node = ROOT
        for (c in word) {
            val lc = fold(c)
            node = child(node, lc).takeIf { it != NONE } ?: newNode(node, lc)
        }
        val added = when {
            wordId[node] == NONE -> {
                setWord(node, word, logProb)
                exp(logProb)
            }
            logProb > this.logProb[node] -> {
                val old = exp(this.logProb[node].toDouble())
                this.logProb[node] = logProb.toFloat()
                exp(logProb) - old
            }
            else -> 0.0
        }
        var n = node
        while (n != NONE) {
            mass[n] += added.toFloat()
            val b = best[n]
            if (b == NONE || this.logProb[node] > this.logProb[b]) best[n] = node
            n = parent[n]
        }
        return wordId[node]
    }

    /**
     * Writes the word data as it is now (call right after parsing, before learned words are
     * added) for [readSnapshot]: the arrays as they are, so loading is a bulk copy, not a parse.
     */
    fun writeSnapshot(out: java.io.OutputStream) {
        val d = java.io.DataOutputStream(java.io.BufferedOutputStream(out, 1 shl 16))
        val n = nodeCount
        d.writeInt(SNAPSHOT_MAGIC); d.writeInt(n); d.writeInt(wordCount)
        for (i in 0 until n) d.writeChar(key[i].code)
        for (a in listOf(firstChild, nextSibling, parent, best, wordId)) for (i in 0 until n) d.writeInt(a[i])
        for (i in 0 until n) d.writeShort(depth[i].toInt())
        for (a in listOf(logProb, mass)) for (i in 0 until n) d.writeFloat(a[i])
        for (i in 0 until wordCount) d.writeInt(wordNode[i])
        d.writeInt(surface.size)
        for ((node, form) in surface) { d.writeInt(node); d.writeUTF(form) }
        d.writeInt(casings.size)
        for ((node, forms) in casings) {
            d.writeInt(node); d.writeInt(forms.size)
            for ((form, lp) in forms) { d.writeUTF(form); d.writeFloat(lp) }
        }
        d.flush()
    }

    private fun setWord(node: Int, word: String, lp: Double) {
        if (wordCount == wordNode.size) wordNode = wordNode.copyOf(wordNode.size * 3 / 2 + 16)
        wordId[node] = wordCount
        wordNode[wordCount++] = node
        logProb[node] = lp.toFloat()
        if (word != word.lowercase() || word.any { fold(it) != it.lowercaseChar() }) { surface[node] = word; hasSurface.set(node) }
    }

    private fun newNode(parentNode: Int, c: Char): Int {
        if (nodeCount == key.size) grow()
        val n = nodeCount++
        key[n] = c
        firstChild[n] = NONE
        parent[n] = parentNode
        depth[n] = if (parentNode == NONE) 0 else (depth[parentNode] + 1).toShort()
        wordId[n] = NONE
        best[n] = NONE
        logProb[n] = Float.NaN
        mass[n] = 0f
        if (parentNode != NONE) {
            nextSibling[n] = firstChild[parentNode]
            firstChild[parentNode] = n
        } else {
            nextSibling[n] = NONE
        }
        return n
    }

    private fun grow() = resize(key.size * 3 / 2 + 64)

    private fun resize(cap: Int) {
        key = key.copyOf(cap); firstChild = firstChild.copyOf(cap); nextSibling = nextSibling.copyOf(cap)
        parent = parent.copyOf(cap); depth = depth.copyOf(cap); logProb = logProb.copyOf(cap)
        mass = mass.copyOf(cap); best = best.copyOf(cap); wordId = wordId.copyOf(cap)
    }

    /** Fills [mass] and [best] bottom-up after loading (children always have larger indices). */
    private fun computeSubtrees() {
        for (n in nodeCount - 1 downTo 0) {
            var m = if (wordId[n] != NONE) exp(logProb[n].toDouble()).toFloat() else 0f
            var b = if (wordId[n] != NONE) n else NONE
            var k = firstChild[n]
            while (k != NONE) {
                m += mass[k]
                val kb = best[k]
                if (kb != NONE && (b == NONE || logProb[kb] > logProb[b])) b = kb
                k = nextSibling[k]
            }
            mass[n] = m
            best[n] = b
        }
    }

    companion object {
        private const val SNAPSHOT_MAGIC = 0x44474c32 // "DGL2": trie letters fold accents

        /** Reads what [writeSnapshot] wrote, or null if [bytes] isn't such a snapshot. */
        fun readSnapshot(bytes: ByteArray): Lexicon? = readSnapshot(java.nio.ByteBuffer.wrap(bytes))

        /**
         * As [readSnapshot] from a buffer, such as the snapshot file mapped into memory: no copy of
         * the whole file on the heap first (that doubled the lexicon's memory while it loaded).
         */
        fun readSnapshot(b: java.nio.ByteBuffer): Lexicon? = runCatching {
            if (b.int != SNAPSHOT_MAGIC) return null
            val n = b.int
            val words = b.int
            val lex = Lexicon(n + 1024) // room for learned words
            b.asCharBuffer().get(lex.key, 0, n); b.position(b.position() + 2 * n)
            for (a in listOf(lex.firstChild, lex.nextSibling, lex.parent, lex.best, lex.wordId)) {
                b.asIntBuffer().get(a, 0, n); b.position(b.position() + 4 * n)
            }
            b.asShortBuffer().get(lex.depth, 0, n); b.position(b.position() + 2 * n)
            for (a in listOf(lex.logProb, lex.mass)) {
                b.asFloatBuffer().get(a, 0, n); b.position(b.position() + 4 * n)
            }
            lex.wordNode = IntArray(words + 1024)
            b.asIntBuffer().get(lex.wordNode, 0, words); b.position(b.position() + 4 * words)
            lex.nodeCount = n
            lex.wordCount = words
            val d = java.io.DataInputStream(b.asInputStream())
            repeat(d.readInt()) { val node = d.readInt(); lex.surface[node] = d.readUTF(); lex.hasSurface.set(node) }
            repeat(d.readInt()) {
                val node = d.readInt()
                lex.casings[node] = List(d.readInt()) { d.readUTF() to d.readFloat() }; lex.hasCasings.set(node)
            }
            lex
        }.getOrNull()

        const val FORMAT_HEADER = "#dogboard-lexicon 1"
        const val ROOT = 0
        const val NONE = -1

        /**
         * The trie letter for [c]: lowercase without accents, so "café" is typed on the e key
         * and keeps its accent as the stored form (iOS matches accent variants the same way).
         */
        fun fold(c: Char): Char {
            val lc = c.lowercaseChar()
            if (lc.code < 0xC0) return lc
            return java.text.Normalizer.normalize(lc.toString(), java.text.Normalizer.Form.NFD)[0]
        }

        fun of(counts: Map<String, Long>): Lexicon {
            val total = counts.values.sum().toDouble()
            val lexicon = Lexicon(counts.size * 3 + 16)
            val byNode = LinkedHashMap<Int, MutableList<Pair<String, Double>>>()
            for ((word, count) in counts) {
                if (word.isEmpty() || count <= 0) continue
                var node = ROOT
                for (c in word) {
                    val lc = fold(c)
                    node = lexicon.child(node, lc).takeIf { it != NONE } ?: lexicon.newNode(node, lc)
                }
                byNode.getOrPut(node) { ArrayList(1) }.add(word to ln(count / total))
            }
            // word ids follow the file (frequency) order of each node's first casing
            for ((node, forms) in byNode) {
                forms.sortByDescending { it.second }
                val best = forms[0]
                val lp = if (forms.size == 1) best.second else ln(forms.sumOf { kotlin.math.exp(it.second) })
                lexicon.setWord(node, best.first, lp)
                if (forms.size > 1) { lexicon.casings[node] = forms.map { (w, l) -> w to l.toFloat() }; lexicon.hasCasings.set(node) }
            }
            lexicon.computeSubtrees()
            lexicon.resize(lexicon.nodeCount + 1024) // room for learned words
            return lexicon
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
    }
}

/** The buffer's remaining bytes as a stream (reads advance the buffer). */
internal fun java.nio.ByteBuffer.asInputStream(): java.io.InputStream = object : java.io.InputStream() {
    override fun read(): Int = if (hasRemaining()) get().toInt() and 0xff else -1
    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!hasRemaining()) return -1
        val n = minOf(len, remaining())
        get(dst, off, n)
        return n
    }
}
