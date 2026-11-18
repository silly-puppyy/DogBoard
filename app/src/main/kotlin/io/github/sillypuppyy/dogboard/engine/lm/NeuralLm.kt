package io.github.sillypuppyy.dogboard.engine.lm

import kotlin.math.ln
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * The neural language model for next-word predictions (12 x 384 transformer, int8, LiteRT; trained in
 * tmp/llm, v3): one token per call with fixed key/value caches ([layers, kv_heads, CTX, head_dim]) that
 * go in; the step returns the token's own keys/values ([layers, kv_heads, head_dim]) and they are
 * written at its slot here (tools/lm/export_kv2.py: 11x faster a step than the older export, whose
 * caches also came out and were swapped; that format still loads). The tokens of the last call stay cached, so the next call only runs what is new
 * (a space and a word: a few steps). Tries after the context (the capitalised path, word
 * continuations) write cache slots past the context, which the next real token overwrites, so they
 * need no copy. Not thread-safe on its own: [predict] is synchronized.
 */
class NeuralLm(model: ByteBuffer, private val tokenizer: PieceTokenizer, threads: Int = 1) {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(threads))
    private val idIn: Int; private val posIn: Int; private val kIn: Int; private val vIn: Int
    private val logitsOut: Int; private val kOut: Int; private val vOut: Int
    private val vocab: Int
    private val ctx: Int
    private val cacheBytes: Int
    // two cache pairs: the call reads one and writes the other, then they swap
    private var kA: ByteBuffer; private var vA: ByteBuffer; private var kB: ByteBuffer; private var vB: ByteBuffer
    /** Whether the step returns only the new token's K/V (written into kA/vA at its slot). */
    private val appCache: Boolean
    private val layers: Int; private val kvHeads: Int; private val headDim: Int
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
        layers = ins[kIn].shape()[0]; kvHeads = ins[kIn].shape()[1]; headDim = ins[kIn].shape()[3]
        cacheBytes = ins[kIn].numBytes()
        appCache = outs[kOut].shape().size == 3
        fun cache() = ByteBuffer.allocateDirect(cacheBytes).order(ByteOrder.nativeOrder())
        fun direct(bytes: Int) = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        kA = cache(); vA = cache()
        if (appCache) { kB = direct(outs[kOut].numBytes()); vB = direct(outs[vOut].numBytes()) } else { kB = cache(); vB = cache() }
        logits = ByteBuffer.allocateDirect(4 * vocab).order(ByteOrder.nativeOrder())
        pBuf = FloatArray(vocab + 1); qBuf = FloatArray(vocab + 1); nBuf = FloatArray(vocab + 1)
        isWordStart = BooleanArray(vocab) { val p = tokenizer.piece(it); p.length > 1 && p[0] == PieceTokenizer.SPACE && p[1].isLetter() }
        isLetters = BooleanArray(vocab) { val p = tokenizer.piece(it); p.isNotEmpty() && p.all { c -> c.isLetter() || c == '\'' } }
    }

    /** Runs [token] at position [pos] (cache in A, out to B, then swapped); next-token probabilities into [out]. */
    /** Model steps run so far (for the perf log). */
    @Volatile var steps = 0L
        private set
    /** Time in the interpreter, and in the softmax after it, so far (ns, for the perf log). */
    @Volatile var invokeNanos = 0L
        private set
    @Volatile var softmaxNanos = 0L
        private set
    @Volatile var encodeNanos = 0L
        private set

    private fun step(token: Int, pos: Int, out: FloatArray): FloatArray {
        steps++
        idBuf.rewind(); idBuf.putInt(token); idBuf.rewind()
        posBuf.rewind(); posBuf.putInt(pos); posBuf.rewind()
        kA.rewind(); vA.rewind(); kB.rewind(); vB.rewind(); logits.rewind()
        inputs[idIn] = idBuf; inputs[posIn] = posBuf; inputs[kIn] = kA; inputs[vIn] = vA
        outputs[logitsOut] = logits; outputs[kOut] = kB; outputs[vOut] = vB
        val t0 = System.nanoTime()
        interpreter.runForMultipleInputsOutputs(inputs, outputs)
        val t1 = System.nanoTime(); invokeNanos += t1 - t0
        if (appCache) { writeSlot(kB, kA, pos); writeSlot(vB, vA, pos) } else { kA = kB.also { kB = kA }; vA = vB.also { vB = vA } }
        logits.rewind()
        logits.asFloatBuffer().get(out, 0, vocab)
        if (logProbOutput == null) logProbOutput = looksLikeLogProbs(out)
        if (logProbOutput == true) { softmaxNanos += System.nanoTime() - t1; return out } // the model gave ln P itself
        var max = Float.NEGATIVE_INFINITY
        for (i in 0 until vocab) if (out[i] > max) max = out[i]
        // logits more than SOFTMAX_CUT below the best add < e^-20 each (all 16,000: < 4e-5 of the sum):
        // no exp for them (most of the vocabulary; the exps were a large part of a step). They keep
        // minus their distance to the best, so [lnP] still gives their exact ln P; read as probabilities
        // they are 0 ([prob]). out[vocab] = ln(sum), for [lnP].
        val floor = max - SOFTMAX_CUT
        var sum = 0.0
        for (i in 0 until vocab) {
            val x = out[i]
            if (x < floor) { out[i] = x - max; continue }
            val e = exp(x - max); out[i] = e; sum += e
        }
        val inv = (1.0 / sum).toFloat()
        for (i in 0 until vocab) if (out[i] > 0f) out[i] *= inv
        out[vocab] = ln(sum).toFloat()
        softmaxNanos += System.nanoTime() - t1
        return out
    }

    /** The token's K or V ([layers, kv_heads, head_dim]) into the cache ([layers, kv_heads, CTX, head_dim]) at [pos]. */
    private fun writeSlot(new: ByteBuffer, cache: ByteBuffer, pos: Int) {
        val rowBytes = headDim * 4
        for (lh in 0 until layers * kvHeads) {
            val src = lh * rowBytes; val dst = (lh * ctx + pos) * rowBytes
            for (b in 0 until rowBytes step 8) cache.putLong(dst + b, new.getLong(src + b)) // absolute: no garbage
        }
    }

    private val inputs = arrayOfNulls<Any>(4)
    private val outputs = HashMap<Int, Any>(4)

    private fun argmax(a: FloatArray): Int {
        var best = 0
        for (i in 1 until vocab) if (a[i] > a[best]) best = i
        return best
    }

    /** P(token [i]) from a [step] output: 0 for the tokens it left without an exp. */
    private fun prob(a: FloatArray, i: Int): Float = if (logProbOutput == true) exp(a[i]) else if (a[i] > 0f) a[i] else 0f

    /**
     * Whether the model's output already is ln P (tools/lm/export_kv2.py LOGSOFTMAX=1: log_softmax in the graph,
     * so the app skips its own 16,000-entry softmax, 1.1 ms a step on the phone): all values <= 0, summing to 1.
     */
    @Volatile private var logProbOutput: Boolean? = null

    private fun looksLikeLogProbs(a: FloatArray): Boolean {
        var max = Float.NEGATIVE_INFINITY
        for (i in 0 until vocab) if (a[i] > max) max = a[i]
        if (max > 1e-3f) return false
        var sum = 0.0
        for (i in 0 until vocab) sum += exp(a[i].toDouble())
        return kotlin.math.abs(sum - 1.0) < 1e-2
    }

    /** ln P(token [i]) from a [step] output, exact for every token. */
    private fun lnP(a: FloatArray, i: Int): Double = when {
        logProbOutput == true -> a[i].toDouble()
        a[i] > 0f -> ln(a[i].toDouble())
        a[i] < 0f -> (a[i] - a[vocab]).toDouble()
        else -> ln(1e-30)
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
        val e0 = System.nanoTime()
        val all = tokenizer.encode(textBefore)
        encodeNanos += System.nanoTime() - e0
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
    fun wordScores(textBefore: String, words: Collection<String>, maxSteps: Int = 6, deadlineNanos: Long = Long.MAX_VALUE): Map<String, Double> {
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
            var lp = if (seq[0] == PieceTokenizer.CAP) lnP(pBuf, PieceTokenizer.CAP) else 0.0
            val i = if (seq[0] == PieceTokenizer.CAP) 1 else 0
            if (i >= seq.size) continue
            lp += lnP(if (i == 1) qBuf else pBuf, seq[i])
            // the rest piece by piece
            val extra = seq.size - i - 1
            if (extra > 0) {
                // a slow phone stops at the deadline: the rest wait for the next keystroke (results are kept per context)
                if (steps + i + extra > maxSteps || pos + seq.size >= ctx || System.nanoTime() > deadlineNanos) continue
                var at = pos
                for (k in 0 until seq.size - 1) {
                    val next = step(seq[k], at, nBuf); at++; steps++
                    if (k + 1 > i) lp += lnP(next, seq[k + 1])
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
        val pCap = prob(p, PieceTokenizer.CAP).toDouble()
        // the [first] best word starts, without sorting or boxing the vocabulary
        val starts = IntArray(first) { -1 }; val startScore = DoubleArray(first) { -1.0 }
        for (i in 0 until vocab) {
            if (!isWordStart[i]) continue
            val sc = prob(p, i) + pCap * prob(q, i)
            if (sc <= startScore[first - 1]) continue
            var k = first - 1
            while (k > 0 && startScore[k - 1] < sc) { startScore[k] = startScore[k - 1]; starts[k] = starts[k - 1]; k-- }
            startScore[k] = sc; starts[k] = i
        }
        val words = LinkedHashMap<String, Double>()
        for ((si, t) in starts.withIndex()) {
            if (t < 0) continue
            val capFirst = pCap * prob(q, t) > prob(p, t)
            val seq = ArrayList<Int>().apply { if (capFirst) add(PieceTokenizer.CAP); add(t) }
            var score = startScore[si]
            if (!isWord(tokenizer.piece(t).substring(1))) {
                // the word may go on: follow the likeliest letter pieces
                var at = pos
                if (capFirst) { step(PieceTokenizer.CAP, at, nBuf); at++ } // slot pos may hold another try's token
                var next = step(t, at, nBuf); at++
                for (k in 0 until maxPieces) {
                    val j = argmax(next)
                    if (!isLetters[j] || at >= ctx - 1) break
                    seq.add(j); score *= prob(next, j)
                    next = step(j, at, nBuf); at++
                }
            }
            val word = tokenizer.decode(seq.toIntArray()).trim()
            if (word.isNotEmpty()) words[word] = (words[word] ?: 0.0) + score
        }
        return words.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
    }

    @Synchronized
    fun close() = interpreter.close()

    private companion object {
        /** Cached tokens that must line up before a slid text continues the cache (fewer: a chance match). */
        const val MIN_OVERLAP = 4
        const val SOFTMAX_CUT = 20f
    }
}
