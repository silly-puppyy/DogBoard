package io.github.sillypuppyy.dogboard.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

/**
 * DogBoard's neural tap-typing model (our own weights; iOS's tap-model design, research/neural-autocorrect-plan.md):
 * the nearest key of each tap, fed as two frames, through a linear layer, 4 × [BatchNorm, BiLSTM] and a
 * linear layer to log-probabilities over blank, a-z and the apostrophe per frame (CTC). Weights come from
 * tools/ml/mltt_export.py ("DGT1"). Plain Kotlin: ~1.4 M multiply-adds per frame, a few ms per word.
 */
class TapNet private constructor(
    private val inSize: Int,
    private val hidden: Int,
    private val inW: FloatArray, private val inB: FloatArray,
    private val layers: List<Layer>,
    private val outSize: Int,
    private val outW: FloatArray, private val outB: FloatArray,
) {
    private class Lstm(val wih: FloatArray, val whh: FloatArray, val b: FloatArray, val inputs: Int)
    private class Layer(val scale: FloatArray, val shift: FloatArray, val fwd: Lstm, val rev: Lstm)

    /** Log-probabilities per frame (two frames per key) for key codes 1-26 (a-z). */
    fun forward(keys: IntArray): Array<FloatArray> {
        val t = keys.size * 2
        var x = Array(t) { f ->
            val k = keys[f / 2]
            FloatArray(hidden) { h -> inB[h] + (if (k in 0 until inSize) inW[h * inSize + k] else 0f) }
        }
        for (l in layers) {
            val n = x[0].size
            for (row in x) for (i in 0 until n) row[i] = row[i] * l.scale[i] + l.shift[i]
            val f = run(l.fwd, x, false)
            val r = run(l.rev, x, true)
            x = Array(t) { i -> f[i] + r[i] }
        }
        return Array(t) { i ->
            val logits = FloatArray(outSize) { o ->
                var s = outB[o]
                val base = o * x[i].size
                for (j in x[i].indices) s += outW[base + j] * x[i][j]
                s
            }
            val m = logits.max()
            var sum = 0.0
            for (v in logits) sum += exp((v - m).toDouble())
            val lse = m + ln(sum).toFloat()
            FloatArray(outSize) { logits[it] - lse }
        }
    }

    private fun run(c: Lstm, x: Array<FloatArray>, reverse: Boolean): Array<FloatArray> {
        val h = FloatArray(hidden)
        val cell = FloatArray(hidden)
        val gates = FloatArray(4 * hidden)
        val out = Array(x.size) { FloatArray(hidden) }
        val order = if (reverse) x.indices.reversed() else x.indices
        for (step in order) {
            val xs = x[step]
            for (g in 0 until 4 * hidden) {
                var s = c.b[g]
                val bi = g * c.inputs
                for (j in 0 until c.inputs) s += c.wih[bi + j] * xs[j]
                val bh = g * hidden
                for (j in 0 until hidden) s += c.whh[bh + j] * h[j]
                gates[g] = s
            }
            for (j in 0 until hidden) { // PyTorch gate order: input, forget, cell (g), output
                val ig = sigmoid(gates[j])
                val fg = sigmoid(gates[hidden + j])
                val gg = tanh(gates[2 * hidden + j])
                val og = sigmoid(gates[3 * hidden + j])
                cell[j] = fg * cell[j] + ig * gg
                h[j] = og * tanh(cell[j])
            }
            h.copyInto(out[step])
        }
        return out
    }

    private fun sigmoid(v: Float): Float = 1f / (1f + exp(-v))

    companion object {
        /** Greedy CTC reading: the best symbol per frame, repeats merged, blanks dropped. */
        fun greedy(logp: Array<FloatArray>): String = buildString {
            var prev = 0
            for (row in logp) {
                var best = 0
                for (k in row.indices) if (row[k] > row[best]) best = k
                if (best != prev && best != 0) append(if (best == 27) '\'' else 'a' + (best - 1))
                prev = best
            }
        }

        fun read(bytes: ByteArray): TapNet? {
            if (bytes.size < 20 || String(bytes, 0, 4) != "DGT1") return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(4)
            val inSize = buf.int; val hidden = buf.int; val nLayers = buf.int; val outSize = buf.int
            fun arr(): FloatArray { val n = buf.int; return FloatArray(n).also { buf.asFloatBuffer().get(it); buf.position(buf.position() + n * 4) } }
            val inW = arr(); val inB = arr()
            var inputs = hidden
            val layers = List(nLayers) {
                val scale = arr(); val shift = arr()
                val fwd = Lstm(arr(), arr(), arr(), inputs)
                val rev = Lstm(arr(), arr(), arr(), inputs)
                inputs = 2 * hidden
                Layer(scale, shift, fwd, rev)
            }
            val outW = arr(); val outB = arr()
            return TapNet(inSize, hidden, inW, inB, layers, outSize, outW, outB)
        }
    }
}

private operator fun FloatArray.plus(o: FloatArray): FloatArray = FloatArray(size + o.size) { if (it < size) this[it] else o[it - size] }

