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
class Lexicon private constructor(capacity: Int, withRoot: Boolean = true) {
    // The node fields, by node. Hot fields (read for every search state) live in heap arrays. The cold ones (parent,
    // mass, word nodes: ~7 MB for English) are read from the mapped snapshot for nodes below [base] (off the Java
    // heap, for 128 MB-heap phones, 2026-11-20) and from [aParent] / [aMass] at index node - base above it (the
    // learned words'); a mapped node's mass changed by a learned word is kept in [ov]. Mapping the hot fields as
    // well saved 21 MB more but cost the decoder 40 %.
    private var aKey = CharArray(capacity)
    private var aFirst = IntArray(capacity)
    private var aNext = IntArray(capacity)
    private var aParent = IntArray(capacity)
    private var aDepth = ShortArray(capacity)
    private var aLogP = FloatArray(capacity)
    private var aMass = FloatArray(capacity)
    private var aBest = IntArray(capacity)
    private var aWordId = IntArray(capacity)
    private var base = 0
    private val ov = Overrides()
    private var nodeCount = 0

    /** Node of each word, by word id: [baseWords] mapped, the rest here (index id - baseWords). */
    private var aWordNode = IntArray(capacity / 2 + 16)
    private var baseWords = 0
    private var wordCount = 0

    /**
     * The snapshot's node fields in its mapped file (little-endian), read with absolute gets on one byte buffer at
     * each field's offset (typed views cost a third more per decode, 2026-11-20).
     */
    private class Mapped(val b: java.nio.ByteBuffer, n: Int) {
        val key = 0
        val first = 2 * n
        val next = first + 4 * n
        val parent = next + 4 * n
        val best = parent + 4 * n
        val wordId = best + 4 * n
        val depth = wordId + 4 * n
        val logP = depth + 2 * n
        val mass = logP + 4 * n
        val wordNode = mass + 4 * n
    }

    /** The mass of mapped nodes changed by learned words (their ancestors), by node (sorted; few). */
    private class Overrides {
        var nodes = IntArray(0); var size = 0
        var mass = FloatArray(0)
        val has = java.util.BitSet()
        fun indexOf(n: Int) = java.util.Arrays.binarySearch(nodes, 0, size, n)
        /** The slot for [n], made from [init] on first use. */
        fun slot(n: Int, init: (Int) -> Unit): Int {
            val i = indexOf(n)
            if (i >= 0) return i
            val at = -i - 1
            if (size == nodes.size) { val c = size * 2 + 16; nodes = nodes.copyOf(c); mass = mass.copyOf(c) }
            System.arraycopy(nodes, at, nodes, at + 1, size - at)
            System.arraycopy(mass, at, mass, at + 1, size - at)
            nodes[at] = n; size++
            has.set(n)
            init(at)
            return at
        }
    }

    // the mapped buffer and the cold fields' offsets in it
    private var mb: java.nio.ByteBuffer = java.nio.ByteBuffer.allocate(0)
    private var oParent = 0; private var oMass = 0; private var oWordNode = 0

    // hot fields (read for every search state): heap arrays by node
    private fun gKey(n: Int): Char = aKey[n]
    private fun gNext(n: Int): Int = aNext[n]
    private fun gDepth(n: Int): Short = aDepth[n]
    private fun gFirst(n: Int): Int = aFirst[n]
    private fun gLogP(n: Int): Float = aLogP[n]
    private fun gBest(n: Int): Int = aBest[n]
    private fun gWordId(n: Int): Int = aWordId[n]
    private fun sFirst(n: Int, v: Int) { aFirst[n] = v }
    private fun sLogP(n: Int, v: Float) { aLogP[n] = v }
    private fun sBest(n: Int, v: Int) { aBest[n] = v }
    private fun sWordId(n: Int, v: Int) { aWordId[n] = v }

    // cold fields (building a word, the key prior, setup): mapped below [base], arrays (index n - base) above
    private fun gParent(n: Int): Int = if (n >= base) aParent[n - base] else mb.getInt(oParent + 4 * n)
    private fun gWordNode(id: Int): Int = if (id >= baseWords) aWordNode[id - baseWords] else mb.getInt(oWordNode + 4 * id)
    private fun gMass(n: Int): Float =
        if (n >= base) aMass[n - base] else if (ov.size != 0 && ov.has.get(n)) ov.mass[ov.indexOf(n)] else mb.getFloat(oMass + 4 * n)
    private fun sMass(n: Int, v: Float) { if (n >= base) aMass[n - base] = v else { val i = ovSlot(n); ov.mass[i] = v } }

    /** The override slot of mapped node [n] (made from its mapped mass on first use). Call it before reading an [ov]
     * array: it may grow them. */
    private fun ovSlot(n: Int): Int = ov.slot(n) { i -> ov.mass[i] = mb.getFloat(oMass + 4 * n) }

    /**
     * Surface forms that differ from the lowercase path ("Paris", "I'm"), by node: words added after loading. The
     * loaded ones are packed by [pack] into [surfNodes] (sorted) / [surfStart] / [surfChars]: 89k forms in a map
     * cost ~100 bytes each (entry, boxed key, String), about 9 MB of the heap (2026-11-20, low-RAM phones).
     */
    private val surface = HashMap<Int, String>()
    private var surfNodes = IntArray(0)
    private var surfStart = IntArray(1)
    private var surfChars = CharArray(0)

    /**
     * Nodes stored in more than one casing ("google" and "Google"): each casing with its own
     * ln P; the node's [logProb] is their total and [surface] the most likely casing. Changed after loading here;
     * the loaded ones packed by [pack] into [castNodes] (sorted) / [castStart] / [castLp], the forms' text in
     * [castChars] from [castCharStart] (one char array: a String a form cost Russian ~9 MB).
     */
    private val casings = HashMap<Int, List<Pair<String, Float>>>()
    private var castNodes = IntArray(0)
    private var castStart = IntArray(1)
    private var castChars = CharArray(0)
    private var castCharStart = IntArray(1)
    private var castLp = FloatArray(0)

    // which nodes have an entry in [surface] / [casings]: most don't, and a map lookup boxes the
    // node (the decoder asks for every word state on every keystroke)
    private val hasSurface = java.util.BitSet()
    private val hasCasings = java.util.BitSet()

    init {
        if (withRoot) newNode(NONE, '\u0000')
    }

    val size: Int get() = wordCount
    val nodes: Int get() = nodeCount

    /** Changes whenever a word is added or raised (searches over the trie start again). */
    var version = 0
        private set

    fun firstChild(node: Int): Int = gFirst(node)
    fun nextSibling(node: Int): Int = gNext(node)
    fun key(node: Int): Char = gKey(node)
    fun depth(node: Int): Int = gDepth(node).toInt()
    fun isWord(node: Int): Boolean = gWordId(node) != NONE

    /** ln P(word) of the word ending at [node], or NaN. */
    fun logProb(node: Int): Double = if (gWordId(node) == NONE) Double.NaN else gLogP(node).toDouble()

    /** Total probability of the words at or below [node]. */
    fun mass(node: Int): Double = gMass(node).toDouble()

    /** Node of the most frequent word at or below [node], or [NONE]. */
    fun bestWord(node: Int): Int = gBest(node)

    /** Id of the word ending at [node] (0 until [size]), or [NONE]. */
    fun wordId(node: Int): Int = gWordId(node)

    /** Node of the word with id [id]. */
    fun nodeOfWord(id: Int): Int = gWordNode(id)

    // each word's string once made (by word id): the decoder asks for the same few hundred words on
    // every keystroke, and rebuilding them was the typing path's largest garbage. Readers share the
    // engine's lock, so two may fill an entry at once: harmless, strings are immutable
    @Volatile private var wordCache = arrayOfNulls<String>(0)

    /** The word ending at [node] in its stored casing, or null. */
    fun word(node: Int): String? {
        val id = gWordId(node)
        if (id == NONE) return null
        val cache = wordCache
        if (id < cache.size) cache[id]?.let { return it }
        return buildWord(node).also { w ->
            val c = if (id < cache.size) cache else cache.copyOf(maxOf(id + 1, wordCount, cache.size * 2)).also { wordCache = it }
            c[id] = w
        }
    }

    private fun buildWord(node: Int): String {
        if (hasSurface.get(node)) surfaceOf(node)?.let { return it }
        val chars = CharArray(gDepth(node).toInt())
        var n = node
        for (i in chars.indices.reversed()) {
            chars[i] = gKey(n)
            n = gParent(n)
        }
        return String(chars)
    }

    /** The word at [node] has several casings ([forms] lists them); otherwise its one form is [word]. */
    fun hasCasings(node: Int): Boolean = hasCasings.get(node)

    /** Every casing of the word at [node] with its own ln P, most likely first. */
    fun forms(node: Int): List<Pair<String, Double>> {
        if (gWordId(node) == NONE) return emptyList()
        if (hasCasings.get(node)) casingsOf(node)?.let { list -> return list.map { (w, lp) -> w to lp.toDouble() } }
        return listOf(word(node)!! to gLogP(node).toDouble())
    }

    /** The surface form stored for [node]: added after loading, else packed, else null. */
    private fun surfaceOf(node: Int): String? {
        surface[node]?.let { return it }
        val i = java.util.Arrays.binarySearch(surfNodes, node)
        return if (i < 0) null else String(surfChars, surfStart[i], surfStart[i + 1] - surfStart[i])
    }

    /** The casings stored for [node]: changed after loading, else packed, else null. */
    private fun casingsOf(node: Int): List<Pair<String, Float>>? {
        casings[node]?.let { return it }
        val i = java.util.Arrays.binarySearch(castNodes, node)
        return if (i < 0) null else List(castStart[i + 1] - castStart[i]) { k ->
            val f = castStart[i] + k
            String(castChars, castCharStart[f], castCharStart[f + 1] - castCharStart[f]) to castLp[f]
        }
    }

    /** Moves the loaded surface forms and casings from the maps into compact arrays (the maps then hold later changes). */
    private fun pack() {
        val sn = surface.keys.sorted()
        surfNodes = sn.toIntArray()
        surfStart = IntArray(sn.size + 1)
        val sb = StringBuilder()
        for ((i, n) in sn.withIndex()) { surfStart[i] = sb.length; sb.append(surface[n]) }
        surfStart[sn.size] = sb.length
        surfChars = CharArray(sb.length).also { sb.toString().toCharArray(it, 0, 0, sb.length) }
        surface.clear()
        val cn = casings.keys.sorted()
        castNodes = cn.toIntArray()
        castStart = IntArray(cn.size + 1)
        val cb = StringBuilder(); val starts = ArrayList<Int>(); val lps = ArrayList<Float>()
        for ((i, n) in cn.withIndex()) {
            castStart[i] = lps.size
            for ((w, l) in casings[n]!!) { starts.add(cb.length); cb.append(w); lps.add(l) }
        }
        castStart[cn.size] = lps.size
        starts.add(cb.length)
        castCharStart = starts.toIntArray(); castLp = lps.toFloatArray()
        castChars = CharArray(cb.length).also { cb.toString().toCharArray(it, 0, 0, cb.length) }
        casings.clear()
    }

    /** Every (node, surface form), packed and added. */
    private fun allSurfaces(): List<Pair<Int, String>> =
        (surfNodes.indices.map { i -> surfNodes[i] to String(surfChars, surfStart[i], surfStart[i + 1] - surfStart[i]) }
            .filter { it.first !in surface } + surface.entries.map { it.key to it.value })

    /** Every (node, casings), packed and changed. */
    private fun allCasings(): List<Pair<Int, List<Pair<String, Float>>>> =
        castNodes.filter { it !in casings }.map { it to casingsOf(it)!! } + casings.entries.map { it.key to it.value }

    /**
     * Adds [form] as a casing of a word the lexicon already has in other casings, with the share of the most likely
     * one and listed first, the others halved (the user's casing ranks first: "hom" kept beside the name "Hom", the
     * typed casing stands). False when the word is unknown or [form] is already one of its casings.
     */
    fun addCasing(form: String): Boolean {
        val node = find(form).takeIf { it != NONE && isWord(it) } ?: return false
        val current = forms(node)
        if (current.any { it.first == form }) return false
        version++
        casings[node] = listOf(form to current.maxOf { it.second }.toFloat()) + current.map { (w, l) -> w to (l - ln(2.0)).toFloat() }
        hasCasings.set(node)
        return true
    }

    /** The most likely casing of the word at [node] (forms(node).first() without the list), or null. */
    fun firstForm(node: Int): String? = when {
        gWordId(node) == NONE -> null
        hasCasings.get(node) -> casingsOf(node)?.firstOrNull()?.first ?: word(node)
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
        var k = gFirst(node)
        while (k != NONE) {
            if (gKey(k) == c) return k
            k = gNext(k)
        }
        return NONE
    }

    /** ln P(word) for an exact (case-insensitive) match, or null if [word] is not in the lexicon. */
    fun logProb(word: String): Double? = find(word).takeIf { it != NONE && isWord(it) }?.let { logProb(it) }

    /** The stored form of [word] ("i'm" -> "I'm"), or null. */
    fun storedForm(word: String): String? = find(word).takeIf { it != NONE }?.let { word(it) }

    fun contains(word: String): Boolean = find(word).let { it != NONE && isWord(it) }

    /** Id of [word] (case-insensitive), or -1. */
    fun idOf(word: String): Int = find(word).let { if (it == NONE) NONE else gWordId(it) }

    /** The node for [prefix] (case-insensitive), or [NONE]. */
    fun find(prefix: CharSequence): Int {
        var node = ROOT
        for (c in prefix) {
            val lig = ligature(c)
            if (lig != null) {
                for (l in lig) { node = child(node, l); if (node == NONE) return NONE }
                continue
            }
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
        if (node == NONE || gMass(node) <= 0f) return null
        val total = gMass(node).toDouble()
        return buildMap {
            var k = gFirst(node)
            while (k != NONE) {
                if (gMass(k) > 0f) put(gKey(k), ln(gMass(k) / total))
                k = gNext(k)
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
            for (lc in ligature(c) ?: fold(c).toString()) node = child(node, lc).takeIf { it != NONE } ?: newNode(node, lc)
        }
        val added = when {
            gWordId(node) == NONE -> {
                setWord(node, word, logProb)
                exp(logProb)
            }
            logProb > gLogP(node) -> {
                val old = exp(gLogP(node).toDouble())
                sLogP(node, logProb.toFloat())
                exp(logProb) - old
            }
            else -> 0.0
        }
        var n = node
        while (n != NONE) {
            sMass(n, gMass(n) + added.toFloat())
            val b = gBest(n)
            if (b == NONE || gLogP(node) > gLogP(b)) sBest(n, node)
            n = gParent(n)
        }
        return gWordId(node)
    }

    /**
     * Writes the word data as it is now (call right after parsing, before learned words are
     * added) for [readSnapshot]: the arrays as they are, so loading is a bulk copy, not a parse.
     */
    fun writeSnapshot(out: java.io.OutputStream) {
        val d = java.io.DataOutputStream(java.io.BufferedOutputStream(out, 1 shl 16))
        val n = nodeCount
        d.writeInt(SNAPSHOT_MAGIC); d.writeInt(n); d.writeInt(wordCount)
        // the node fields little-endian (the byte order of phones and PCs): the app reads them in place from the
        // mapped file, and big-endian views cost a byte swap per read (decoder 1.06 -> 1.51 ms a keystroke)
        val nb = java.nio.ByteBuffer.allocate(n * 32 + wordCount * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) nb.putChar(gKey(i))
        for (g in listOf(::gFirst, ::gNext, ::gParent, ::gBest, ::gWordId)) for (i in 0 until n) nb.putInt(g(i))
        for (i in 0 until n) nb.putShort(gDepth(i))
        for (g in listOf(::gLogP, ::gMass)) for (i in 0 until n) nb.putFloat(g(i))
        for (i in 0 until wordCount) nb.putInt(gWordNode(i))
        d.write(nb.array(), 0, nb.position())
        val surfaces = allSurfaces()
        d.writeInt(surfaces.size)
        for ((node, form) in surfaces) { d.writeInt(node); d.writeUTF(form) }
        val allCasings = allCasings()
        d.writeInt(allCasings.size)
        for ((node, forms) in allCasings) {
            d.writeInt(node); d.writeInt(forms.size)
            for ((form, lp) in forms) { d.writeUTF(form); d.writeFloat(lp) }
        }
        d.flush()
    }

    private fun setWord(node: Int, word: String, lp: Double) {
        if (wordCount - baseWords == aWordNode.size) aWordNode = aWordNode.copyOf(aWordNode.size * 3 / 2 + 16)
        sWordId(node, wordCount)
        aWordNode[wordCount++ - baseWords] = node
        sLogP(node, lp.toFloat())
        if (word != word.lowercase() || word.any { fold(it) != it.lowercaseChar() || ligature(it) != null }) { surface[node] = word; hasSurface.set(node) }
    }

    private fun newNode(parentNode: Int, c: Char): Int {
        if (nodeCount == aKey.size) grow()
        if (nodeCount - base == aParent.size) growCold()
        val n = nodeCount++
        aKey[n] = c
        aFirst[n] = NONE
        aParent[n - base] = parentNode // cold fields of a new node: after the mapped ones
        aDepth[n] = if (parentNode == NONE) 0 else (gDepth(parentNode) + 1).toShort()
        aWordId[n] = NONE
        aBest[n] = NONE
        aLogP[n] = Float.NaN
        aMass[n - base] = 0f
        if (parentNode != NONE) {
            aNext[n] = gFirst(parentNode)
            sFirst(parentNode, n)
        } else {
            aNext[n] = NONE
        }
        return n
    }

    private fun grow() = resize(aKey.size * 3 / 2 + 64)

    /** The hot fields' arrays to [cap] nodes (and the cold ones too while nothing is mapped). */
    private fun resize(cap: Int) {
        aKey = aKey.copyOf(cap); aFirst = aFirst.copyOf(cap); aNext = aNext.copyOf(cap)
        aDepth = aDepth.copyOf(cap); aLogP = aLogP.copyOf(cap); aBest = aBest.copyOf(cap); aWordId = aWordId.copyOf(cap)
        if (base == 0) { aParent = aParent.copyOf(cap); aMass = aMass.copyOf(cap) }
    }

    private fun growCold() { val c = aParent.size * 3 / 2 + 64; aParent = aParent.copyOf(c); aMass = aMass.copyOf(c) }

    /** Fills [mass] and [best] bottom-up after loading (children always have larger indices). */
    private fun computeSubtrees() {
        for (n in nodeCount - 1 downTo 0) {
            var m = if (gWordId(n) != NONE) exp(gLogP(n).toDouble()).toFloat() else 0f
            var b = if (gWordId(n) != NONE) n else NONE
            var k = gFirst(n)
            while (k != NONE) {
                m += gMass(k)
                val kb = gBest(k)
                if (kb != NONE && (b == NONE || gLogP(kb) > gLogP(b))) b = kb
                k = gNext(k)
            }
            sMass(n, m)
            sBest(n, b)
        }
    }

    companion object {
        private const val SNAPSHOT_MAGIC = 0x44474c33 // "DGL3": node fields little-endian, read in place (DGL2: big-endian)

        /** Reads what [writeSnapshot] wrote, or null if [bytes] isn't such a snapshot. */
        fun readSnapshot(bytes: ByteArray): Lexicon? = readSnapshot(java.nio.ByteBuffer.wrap(bytes))

        /**
         * As [readSnapshot] from a buffer, such as the snapshot file mapped into memory: no copy of
         * the whole file on the heap first (that doubled the lexicon's memory while it loaded).
         */
        fun readSnapshot(b: java.nio.ByteBuffer): Lexicon? = runCatching {
            b.order(java.nio.ByteOrder.BIG_ENDIAN)
            if (b.int != SNAPSHOT_MAGIC) return null
            val n = b.int
            val words = b.int
            // the hot fields (read for every search state) copied into heap arrays; the cold ones (parent, mass, word
            // nodes: 7 MB) left in the buffer (the app maps the snapshot file: off the Java heap). All mapped cost the
            // decoder 40 % (1.06 -> 1.46 ms a keystroke, 2026-11-20)
            val mm = Mapped(b, n)
            val fields = (b.slice() as java.nio.ByteBuffer).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.position(b.position() + mm.wordNode + 4 * words)
            val lex = Lexicon(n + 1024, withRoot = false) // room for learned words
            fields.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN).let { f ->
                f.position(mm.key); f.asCharBuffer().get(lex.aKey, 0, n)
                f.position(mm.first); f.asIntBuffer().get(lex.aFirst, 0, n)
                f.position(mm.next); f.asIntBuffer().get(lex.aNext, 0, n)
                f.position(mm.best); f.asIntBuffer().get(lex.aBest, 0, n)
                f.position(mm.wordId); f.asIntBuffer().get(lex.aWordId, 0, n)
                f.position(mm.depth); f.asShortBuffer().get(lex.aDepth, 0, n)
                f.position(mm.logP); f.asFloatBuffer().get(lex.aLogP, 0, n)
            }
            lex.aParent = IntArray(1024); lex.aMass = FloatArray(1024) // the learned words' cold fields
            lex.aWordNode = IntArray(256) // learned words' nodes only (sized for every word it held 4 MB in Russian)
            lex.mb = fields
            lex.oParent = mm.parent; lex.oMass = mm.mass; lex.oWordNode = mm.wordNode
            lex.base = n
            lex.nodeCount = n
            lex.baseWords = words
            lex.wordCount = words
            val d = java.io.DataInputStream(b.asInputStream())
            repeat(d.readInt()) { val node = d.readInt(); lex.surface[node] = d.readUTF(); lex.hasSurface.set(node) }
            repeat(d.readInt()) {
                val node = d.readInt()
                lex.casings[node] = List(d.readInt()) { d.readUTF() to d.readFloat() }; lex.hasCasings.set(node)
            }
            lex.pack()
            lex
        }.getOrNull()

        const val FORMAT_HEADER = "#dogboard-lexicon 1"
        const val ROOT = 0
        const val NONE = -1

        /**
         * A ligature as the two letters people type for it, or null: the trie holds "cœur" on the path c-o-e-u-r, so
         * "coeur" reaches it (French: few long-press for œ; the word keeps its spelling as its surface form).
         */
        fun ligature(c: Char): String? = when (c) {
            'œ', 'Œ' -> "oe"
            'æ', 'Æ' -> "ae"
            else -> null
        }

        /**
         * The trie letter for [c]: lowercase without accents, so "café" is typed on the e key
         * and keeps its accent as the stored form (iOS matches accent variants the same way).
         */
        /**
         * Letters with their own key on the language's layout (ñ in Spanish, å ä ö in Finnish): kept as they are, not
         * folded to a base letter, so a tap on that key matches them (folded, a tap on ä was scored against the a key).
         * Set from the pack's letter rows before its lexicon is built; accented letters typed by a long press (é) fold.
         */
        @Volatile var distinctLetters: Set<Char> = emptySet()

        /** [fold] ignoring [distinctLetters]: every letter to its base (the letter model is trained that way). */
        fun foldPlain(c: Char): Char {
            val lc = c.lowercaseChar()
            if (lc.code < 0xC0) return lc
            return java.text.Normalizer.normalize(lc.toString(), java.text.Normalizer.Form.NFD)[0]
        }

        fun fold(c: Char): Char {
            val lc = c.lowercaseChar()
            if (lc.code < 0xC0) return lc
            if (lc in distinctLetters) return lc
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
                    for (lc in ligature(c) ?: fold(c).toString()) node = lexicon.child(node, lc).takeIf { it != NONE } ?: lexicon.newNode(node, lc)
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
            lexicon.pack()
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
