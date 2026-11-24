package io.github.sillypuppyy.dogboard.engine.swipe

import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The universal swipe model on LiteRT (tools/swipe/uni.py, export_tflite.py --uni): one network for every layout, the
 * layout given as its keys. Inputs: the path's features [1, T, 4] and the reversing index [T] as [SwipeNet]; the keys
 * [1, K, 4] (centre x, y, half width, half height, in the same physical units as the path: [UniUnits]) and a mask
 * [1, K] (1 for a key, 0 for padding). Returns [n x (1 + keys)] log-probs: 0 = blank, k = the k-th key given.
 * Not thread-safe: synchronized.
 */
class UniSwipeNet(model: ByteBuffer, threads: Int = 2) {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(threads).setUseXNNPACK(false))
    private val steps: Int
    /** The most keys a layout can have for this model (letter keys only). */
    val maxKeys: Int
    private val xIn: Int; private val revIn: Int; private val keysIn: Int; private val maskIn: Int
    private val x: ByteBuffer; private val rev: ByteBuffer; private val keys: ByteBuffer; private val mask: ByteBuffer
    private val out: ByteBuffer

    init {
        var xi = -1; var ri = -1; var ki = -1; var mi = -1
        var kmax = 0
        for (i in 0 until interpreter.inputTensorCount) {
            val s = interpreter.getInputTensor(i).shape()
            when {
                s.size == 1 -> ri = i
                s.size == 2 -> { mi = i; kmax = s[1] }
                s.size == 3 && xi < 0 && ki < 0 -> xi = i
                s.size == 3 -> ki = i
            }
        }
        // the path has T >= K steps: the larger of the two 3-d inputs is the path
        if (xi >= 0 && ki >= 0 && interpreter.getInputTensor(xi).shape()[1] < interpreter.getInputTensor(ki).shape()[1]) { val t = xi; xi = ki; ki = t }
        check(xi >= 0 && ri >= 0 && ki >= 0 && mi >= 0) { "universal swipe model inputs" }
        xIn = xi; revIn = ri; keysIn = ki; maskIn = mi; maxKeys = kmax
        steps = interpreter.getInputTensor(xIn).shape()[1]
        x = ByteBuffer.allocateDirect(steps * 4 * 4).order(ByteOrder.nativeOrder())
        rev = ByteBuffer.allocateDirect(steps * 4).order(ByteOrder.nativeOrder())
        keys = ByteBuffer.allocateDirect(maxKeys * 4 * 4).order(ByteOrder.nativeOrder())
        mask = ByteBuffer.allocateDirect(maxKeys * 4).order(ByteOrder.nativeOrder())
        out = ByteBuffer.allocateDirect(steps * (1 + maxKeys) * 4).order(ByteOrder.nativeOrder())
    }

    private var closed = false

    /** Frees the interpreter's native memory (memory pressure); a later [run] returns nothing. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        interpreter.close()
    }

    /**
     * [features]: n x 4 flat (SwipePath.features of the path in [UniUnits]); [keyBoxes]: k x 4 flat (centre x, y, half
     * width, half height, same units), k <= [maxKeys]. Log-probs [n x (1 + k)] flat; empty once [close]d or k too large.
     */
    @Synchronized
    fun run(features: FloatArray, keyBoxes: FloatArray): FloatArray {
        val k = keyBoxes.size / 4
        if (closed || k == 0 || k > maxKeys) return FloatArray(0)
        val n = minOf(features.size / 4, steps)
        x.clear()
        for (i in 0 until steps * 4) x.putFloat(if (i < n * 4) features[i] else 0f)
        rev.clear()
        for (i in 0 until steps) rev.putInt(if (i < n) n - 1 - i else i)
        keys.clear()
        for (i in 0 until maxKeys * 4) keys.putFloat(if (i < k * 4) keyBoxes[i] else 0f)
        mask.clear()
        for (i in 0 until maxKeys) mask.putFloat(if (i < k) 1f else 0f)
        x.rewind(); rev.rewind(); keys.rewind(); mask.rewind(); out.clear()
        val inputs = arrayOfNulls<Any>(4)
        inputs[xIn] = x; inputs[revIn] = rev; inputs[keysIn] = keys; inputs[maskIn] = mask
        interpreter.runForMultipleInputsOutputs(inputs, mapOf(0 to out))
        out.rewind()
        val width = 1 + maxKeys
        return FloatArray(n * (1 + k)).also { r ->
            for (t in 0 until n) for (c in 0..k) r[t * (1 + k) + c] = out.getFloat((t * width + c) * 4)
        }
    }
}

/**
 * The universal model's units (tools/swipe/uni.py): the letter keys' area (left to right of the outer keys, top of the
 * first letter row to the bottom of the last) as 0..1, then x * 10 and y * 10 * height / width, so both axes keep the
 * keyboard's real proportions. Key sizes come from the keys' spacing (neighbours in a row, rows apart).
 */
class UniUnits(centres: Map<Char, Pair<Float, Float>>) {
    /** The letters in the order their boxes are given to the model (sorted, as in training). */
    val letters: CharArray = centres.keys.sorted().toCharArray()
    private val left: Float; private val top: Float; private val w: Float; private val h: Float; private val sy: Float
    private val rx: Float; private val ry: Float

    init {
        val xs = letters.map { centres.getValue(it).first }; val ys = letters.map { centres.getValue(it).second }
        // half a key: half the median gap to the nearest key in the same row; half a row: half the median row step
        val rows = ys.map { it }.sorted().fold(ArrayList<Float>()) { acc, y -> if (acc.isEmpty() || y - acc.last() > 1f) acc.add(y); acc }
        val rowStep = rows.zipWithNext { a, b -> b - a }.sorted().let { if (it.isEmpty()) 1f else it[it.size / 2] }
        val gaps = letters.indices.mapNotNull { i ->
            letters.indices.filter { j -> j != i && kotlin.math.abs(ys[j] - ys[i]) < rowStep / 2 }.minOfOrNull { j -> kotlin.math.abs(xs[j] - xs[i]) }
        }.sorted()
        val keyW = if (gaps.isEmpty()) rowStep else gaps[gaps.size / 2]
        rx = keyW / 2; ry = rowStep / 2
        left = (xs.minOrNull() ?: 0f) - rx; top = (ys.minOrNull() ?: 0f) - ry
        w = ((xs.maxOrNull() ?: 0f) + rx - left).coerceAtLeast(1f); h = ((ys.maxOrNull() ?: 0f) + ry - top).coerceAtLeast(1f)
        sy = 10f * h / w
    }

    fun x(px: Float) = (px - left) / w * 10f
    fun y(py: Float) = (py - top) / h * sy

    /** The keys' boxes in [letters] order: k x 4 (centre x, y, half width, half height). */
    fun boxes(centres: Map<Char, Pair<Float, Float>>): FloatArray = FloatArray(letters.size * 4).also { b ->
        letters.forEachIndexed { i, c ->
            val (cx, cy) = centres.getValue(c)
            b[4 * i] = x(cx); b[4 * i + 1] = y(cy); b[4 * i + 2] = rx / w * 10f; b[4 * i + 3] = ry / h * sy
        }
    }
}

/** Which layouts can be swiped (the keyboard's gesture and the Languages page's "swipe typing" line agree on it). */
object SwipeSupport {
    /** The most letter keys the universal model takes (tools/swipe/uni.py MAX_KEYS). */
    const val UNI_KEYS = 40

    /**
     * English QWERTY (its own model), or any layout of at most [UNI_KEYS] letter keys whose keys don't compose (Korean
     * jamo, Indic signs, Vietnamese typed tones: a path over those keys spells no word of the lexicon).
     */
    fun layoutSwipes(id: String): Boolean {
        if (id == "qwerty-en") return true
        val layout = io.github.sillypuppyy.dogboard.ime.GeneratedLayouts.all.firstOrNull { it.id == id } ?: return false
        val letterKeys = layout.rows.joinToString("").filter { it.isLetter() }.toSet().size
        val composes = layout.language in setOf("ko", "vi") ||
            io.github.sillypuppyy.dogboard.engine.RuleComposer.forLayout(id, layout.language) != null
        return !composes && letterKeys in 1..UNI_KEYS
    }
}
