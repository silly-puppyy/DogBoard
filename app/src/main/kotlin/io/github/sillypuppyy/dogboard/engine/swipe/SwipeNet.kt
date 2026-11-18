package io.github.sillypuppyy.dogboard.engine.swipe

import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The swipe model on LiteRT (tools/swipe/export_tflite.py): features [1, T, 4] padded with zeros after
 * the swipe's n points, and an index table [T] that reverses the real points for the backward LSTMs
 * (so the padding can't change the result). Returns [n x 27] log-probs. Not thread-safe: synchronized.
 */
class SwipeNet(model: ByteBuffer, threads: Int = 2) {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(threads).setUseXNNPACK(false))
    private val steps: Int
    private val xIn: Int
    private val revIn: Int
    private val x: ByteBuffer
    private val rev: ByteBuffer
    private val out: ByteBuffer

    init {
        var xi = -1; var ri = -1
        for (i in 0 until interpreter.inputTensorCount) {
            val t = interpreter.getInputTensor(i)
            if (t.shape().size == 3) xi = i else ri = i
        }
        check(xi >= 0 && ri >= 0) { "swipe model inputs" }
        xIn = xi; revIn = ri
        steps = interpreter.getInputTensor(xIn).shape()[1]
        x = ByteBuffer.allocateDirect(steps * 4 * 4).order(ByteOrder.nativeOrder())
        rev = ByteBuffer.allocateDirect(steps * 4).order(ByteOrder.nativeOrder())
        out = ByteBuffer.allocateDirect(steps * 27 * 4).order(ByteOrder.nativeOrder())
    }

    /** [features]: n x 4 flat (SwipePath.features). Log-probs [n x 27] flat. */
    @Synchronized
    fun run(features: FloatArray): FloatArray {
        val n = minOf(features.size / 4, steps)
        x.clear()
        for (i in 0 until steps * 4) x.putFloat(if (i < n * 4) features[i] else 0f)
        rev.clear()
        for (i in 0 until steps) rev.putInt(if (i < n) n - 1 - i else i)
        x.rewind(); rev.rewind(); out.clear()
        val inputs = arrayOfNulls<Any>(2)
        inputs[xIn] = x; inputs[revIn] = rev
        interpreter.runForMultipleInputsOutputs(inputs, mapOf(0 to out))
        out.rewind()
        return FloatArray(n * 27).also { r -> for (i in r.indices) r[i] = out.getFloat() }
    }

    val maxSteps: Int get() = steps
}
