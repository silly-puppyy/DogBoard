package io.github.sillypuppyy.dogboard.engine.lm

import kotlin.math.ln
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * The neural language model for next-word predictions (12 x 384 transformer, int8, LiteRT; trained in
 * tmp/llm, v3): one token per call with fixed key/value caches ([layers, kv_heads, CTX, head_dim]) that
 * go in and come out. The tokens of the last call stay cached, so the next call only runs what is new
 * (a space and a word: a few steps). Tries after the context (the capitalised path, word
 * continuations) write cache slots past the context, which the next real token overwrites, so they
 * need no copy. Not thread-safe on its own: [predict] is synchronized.
 */
class NeuralLm(model: ByteBuffer, private val tokenizer: PieceTokenizer, threads: Int = 2) {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(threads))
    private val idIn: Int; private val posIn: Int; private val kIn: Int; private val vIn: Int
    private val logitsOut: Int; private val kOut: Int; private val vOut: Int
    private val vocab: Int
    private val ctx: Int
    private val cacheBytes: Int
    // two cache pairs: the call reads one and writes the other, then they swap
    private var kA: ByteBuffer; private var vA: ByteBuffer; private var kB: ByteBuffer; private var vB: ByteBuffer
    private val logits: ByteBuffer
    private val idBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
    private val posBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
    /** Tokens whose keys/values sit in the cache (BOS first), and the logits after the last of them. */
    private val cached = ArrayList<Int>()
    // probability buffers, reused (a prediction allocates nothing per vocabulary entry: the
    // typing thread stalls on garbage collection when background work allocates fast)
    private lateinit var pBuf: FloatArray // after the context
    private lateinit var qBuf: FloatArray // after the capital token
    private lateinit var nBuf: FloatArray // after a word's pieces
    private var haveContext = false
    private val isWordStart: BooleanArray
    private val isLetters: BooleanArray

    init {
        val ins = (0 until interpreter.inputTensorCount).map { interpreter.getInputTensor(it) }
        idIn = ins.indexOfFirst { it.dataType() == org.tensorflow.lite.DataType.INT32 && it.shape().contentEquals(intArrayOf(1, 1)) }
        posIn = ins.indexOfFirst { it.dataType() == org.tensorflow.lite.DataType.INT32 && it.shape().contentEquals(intArrayOf(1)) }
        val floats = ins.indices.filter { ins[it].dataType() == org.tensorflow.lite.DataType.FLOAT32 }
        kIn = floats[0]; vIn = floats[1]
        val outs = (0 until interpreter.outputTensorCount).map { interpreter.getOutputTensor(it) }
        vocab = tokenizer.size
        logitsOut = outs.indexOfFirst { it.shape().last() == vocab }
        val cacheOuts = outs.indices.filter { it != logitsOut }
        kOut = cacheOuts[0]; vOut = cacheOuts[1]
        ctx = ins[kIn].shape()[2]
        cacheBytes = ins[kIn].numBytes()
        fun cache() = ByteBuffer.allocateDirect(cacheBytes).order(ByteOrder.nativeOrder())
        kA = cache(); vA = cache(); kB = cache(); vB = cache()
        logits = ByteBuffer.allocateDirect(4 * vocab).order(ByteOrder.nativeOrder())
        pBuf = FloatArray(vocab); qBuf = FloatArray(vocab); nBuf = FloatArray(vocab)
        isWordStart = BooleanArray(vocab) { val p = tokenizer.piece(it); p.length > 1 && p[0] == PieceTokenizer.SPACE && p[1].isLetter() }
        isLetters = BooleanArray(vocab) { val p = tokenizer.piece(it); p.isNotEmpty() && p.all { c -> c.isLetter() || c == '\'' } }
    }

    /** Runs [token] at position [pos] (cache in A, out to B, then swapped); next-token probabilities into [out]. */
    private fun step(token: Int, pos: Int, out: FloatArray): FloatArray {
        idBuf.rewind(); idBuf.putInt(token); idBuf.rewind()
        posBuf.rewind(); posBuf.putInt(pos); posBuf.rewind()
        kA.rewind(); vA.rewind(); kB.rewind(); vB.rewind(); logits.rewind()
        inputs[idIn] = idBuf; inputs[posIn] = posBuf; inputs[kIn] = kA; inputs[vIn] = vA
        outputs[logitsOut] = logits; outputs[kOut] = kB; outputs[vOut] = vB
        interpreter.runForMultipleInputsOutputs(inputs, outputs)
        kA = kB.also { kB = kA }; vA = vB.also { vB = vA }
        logits.rewind()
        logits.asFloatBuffer().get(out)
        var max = Float.NEGATIVE_INFINITY
        for (x in out) if (x > max) max = x
        var sum = 0.0
        for (i in out.indices) { val e = exp((out[i] - max).toDouble()); out[i] = e.toFloat(); sum += e }
        val inv = (1.0 / sum).toFloat()
        for (i in out.indices) out[i] *= inv
        return out
    }

    private val inputs = arrayOfNulls<Any>(4)
    private val outputs = HashMap<Int, Any>(4)

    private fun argmax(a: FloatArray): Int {
        var best = 0
        for (i in 1 until a.size) if (a[i] > a[best]) best = i
        return best
    }

    /**
     * The [n] most likely next words after [textBefore] (most likely first), each with its probability;
     * a word whose capitalised path wins comes capitalised ("Thursday"). A first piece that is already a
     * known word ([isWord]) is taken as it is, without asking the model whether the word goes on: most
     * predictions then cost the new context tokens and one capital step (a phone runs ~10 ms a step).
     */
    @Synchronized
    fun predict(
        textBefore: String, n: Int = 3, isWord: (String) -> Boolean = { false }, first: Int = 6, maxPieces: Int = 3,
        stillWanted: () -> Boolean = { true },
    ): List<Pair<String, Double>> {
        // a call that waited for an older one may no longer be wanted (a newer word end)
        if (!stillWanted()) return emptyList()
        ensureContext(textBefore, maxPieces)
        return candidates(n, isWord, first, maxPieces)
    }

    /** The text whose tokens the cache holds (and [pBuf] the distribution after), for word scores. */
    private var contextText: String? = null
    private var capReady = false
    /** ln P(word | [contextText]) already worked out. */
    private val wordCache = HashMap<String, Double>()

    private fun contextChanged(text: String) {
        if (text != contextText) { contextText = text; capReady = false; wordCache.clear() }
    }

    /** Brings the cache (and [pBuf]) to [textBefore]: continues it, or runs what differs. */
    private fun ensureContext(textBefore: String, maxPieces: Int) {
        if (haveContext && textBefore == contextText) return
        // the context: BOS + the text's tokens, the newest that leave room for the tries after it
        val room = ctx - 2 - maxPieces - 2
        val all = tokenizer.encode(textBefore)
        // a text that slid on (the editor's window starts later each word) continues the cache when
        // the cached tokens end inside the new ones: only the new tokens run (the cache keeps the
        // older context too)
        val more = if (haveContext) continuation(all) else null
        if (more != null && cached.size + more.size <= room + 1) {
            for (t in more) { step(t, cached.size, pBuf); cached.add(t) }
            return contextChanged(textBefore)
        }
        // otherwise from BOS; a text too long keeps its newest two thirds, so the next words continue it
        val tokens = if (all.size <= room) all else all.copyOfRange(all.size - room * 2 / 3, all.size)
        val want = ArrayList<Int>(tokens.size + 1).apply { add(PieceTokenizer.BOS); tokens.forEach { add(it) } }
        // keep the cached prefix, run the rest
        var same = 0
        while (same < cached.size && same < want.size && cached[same] == want[same]) same++
        if (same < want.size || same < cached.size || !haveContext) {
            while (cached.size > same) cached.removeAt(cached.size - 1)
            if (same == want.size) { cached.removeAt(cached.size - 1); same-- } // rerun the last token for its logits
            for (i in same until want.size) { step(want[i], i, pBuf); cached.add(want[i]) }
            haveContext = true
        }
        contextChanged(textBefore)
    }

    /**
     * ln P(word | [textBefore]) for each of [words] (capitalised ones through the capital token, as the
     * model was trained), for refining autocorrect's candidates (iOS scores every candidate with its
     * neural model at the word level). Words of one piece cost nothing once the context is in the
     * cache; longer ones run their extra pieces, at most [maxSteps] new steps a call (results are kept
     * for the context, so the next keystroke goes on); words not reached are left out.
     */
    @Synchronized
    fun wordScores(textBefore: String, words: Collection<String>, maxSteps: Int = 6): Map<String, Double> {
        ensureContext(textBefore, 3)
        val pos = cached.size
        var steps = 0
        val out = HashMap<String, Double>()
        for (w in words) {
            wordCache[w]?.let { out[w] = it; continue }
            val seq = tokenizer.encode(w)
            if (seq.isEmpty()) continue
            if (seq[0] == PieceTokenizer.CAP && !capReady) {
                if (steps >= maxSteps) continue
                step(PieceTokenizer.CAP, pos, qBuf); steps++; capReady = true
            }
            // the first piece from the context's distribution (or after the capital token)
            var lp = if (seq[0] == PieceTokenizer.CAP) ln(pBuf[PieceTokenizer.CAP].toDouble()) else 0.0
            val i = if (seq[0] == PieceTokenizer.CAP) 1 else 0
            if (i >= seq.size) continue
            lp += ln((if (i == 1) qBuf else pBuf)[seq[i]].toDouble().coerceAtLeast(1e-30))
            // the rest piece by piece
            val extra = seq.size - i - 1
            if (extra > 0) {
                if (steps + i + extra > maxSteps || pos + seq.size >= ctx) continue
                var at = pos
                for (k in 0 until seq.size - 1) {
                    val next = step(seq[k], at, nBuf); at++; steps++
                    if (k + 1 > i) lp += ln(next[seq[k + 1]].toDouble().coerceAtLeast(1e-30))
                }
            }
            wordCache[w] = lp; out[w] = lp
        }
        return out
    }

    /**
     * The tokens of [all] after the cached ones (empty: nothing new), when the cached tokens after BOS
     * end as a prefix of [all] (the whole cache, or at least [MIN_OVERLAP] of its newest tokens).
     */
    private fun continuation(all: IntArray): IntArray? {
        val c = cached.size - 1
        for (j in 0 until c) {
            val len = c - j
            if (len < MIN_OVERLAP && j > 0) return null
            if (len > all.size) continue
            var k = 0
            while (k < len && cached[1 + j + k] == all[k]) k++
            if (k == len) return all.copyOfRange(len, all.size)
        }
        return null
    }

    private fun candidates(n: Int, isWord: (String) -> Boolean, first: Int, maxPieces: Int): List<Pair<String, Double>> {
        val p = pBuf
        val pos = cached.size
        // the capitalised path: CAP at pos, then the word
        val q = step(PieceTokenizer.CAP, pos, qBuf)
        capReady = true
        val pCap = p[PieceTokenizer.CAP].toDouble()
        // the [first] best word starts, without sorting or boxing the vocabulary
        val starts = IntArray(first) { -1 }; val startScore = DoubleArray(first) { -1.0 }
        for (i in 0 until vocab) {
            if (!isWordStart[i]) continue
            val sc = p[i] + pCap * q[i]
            if (sc <= startScore[first - 1]) continue
            var k = first - 1
            while (k > 0 && startScore[k - 1] < sc) { startScore[k] = startScore[k - 1]; starts[k] = starts[k - 1]; k-- }
            startScore[k] = sc; starts[k] = i
        }
        val words = LinkedHashMap<String, Double>()
        for ((si, t) in starts.withIndex()) {
            if (t < 0) continue
            val capFirst = pCap * q[t] > p[t]
            val seq = ArrayList<Int>().apply { if (capFirst) add(PieceTokenizer.CAP); add(t) }
            var prob = startScore[si]
            if (!isWord(tokenizer.piece(t).substring(1))) {
                // the word may go on: follow the likeliest letter pieces
                var at = pos
                if (capFirst) { step(PieceTokenizer.CAP, at, nBuf); at++ } // slot pos may hold another try's token
                var next = step(t, at, nBuf); at++
                for (k in 0 until maxPieces) {
                    val j = argmax(next)
                    if (!isLetters[j] || at >= ctx - 1) break
                    seq.add(j); prob *= next[j]
                    next = step(j, at, nBuf); at++
                }
            }
            val word = tokenizer.decode(seq.toIntArray()).trim()
            if (word.isNotEmpty()) words[word] = (words[word] ?: 0.0) + prob
        }
        return words.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
    }

    @Synchronized
    fun close() = interpreter.close()

    private companion object {
        /** Cached tokens that must line up before a slid text continues the cache (fewer: a chance match). */
        const val MIN_OVERLAP = 4
    }
}
