package io.github.sillypuppyy.dogboard.perf

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log

/**
 * Debug builds: finds what stalls the keyboard. Every main-thread message (key handling, frames,
 * callbacks) that runs longer than a frame is logged under `DogBoardJank` with its duration and
 * what it was, and where it spent the time: the main thread's stack is sampled every [SAMPLE_MS]
 * while a message runs, and a slow one logs its most frequent frames in our code (2026-11-20: the phone
 * showed 17-46 ms frames while typing). StrictMode logs disk and network access on the main thread.
 * Release builds don't install it (the message printer formats a string per message).
 */
object JankWatch {
    private const val FRAME_MS = 16L
    private const val SAMPLE_MS = 3L
    @Volatile private var start = 0L
    private var what = ""
    private var installed = false
    private val samples = java.util.concurrent.ConcurrentLinkedQueue<Array<StackTraceElement>>()
    private lateinit var sampler: Handler
    private val main = Looper.getMainLooper().thread

    /** Key caps composed / drawn since the last message (KbView counts them): what a slow frame redid. */
    @JvmField val capsComposed = java.util.concurrent.atomic.AtomicInteger()
    @JvmField val capsDrawn = java.util.concurrent.atomic.AtomicInteger()

    private val sample = object : Runnable {
        override fun run() {
            if (start == 0L) return
            samples.add(main.stackTrace)
            sampler.postDelayed(this, SAMPLE_MS)
        }
    }

    fun install() {
        if (installed) return
        installed = true
        sampler = Handler(HandlerThread("DogBoardJankSampler").apply { start() }.looper)
        Looper.getMainLooper().setMessageLogging { line ->
            if (line.startsWith(">>>>> Dispatching")) {
                samples.clear()
                capsComposed.set(0); capsDrawn.set(0)
                start = SystemClock.uptimeMillis()
                what = line
                sampler.postDelayed(sample, SAMPLE_MS)
            } else if (line.startsWith("<<<<< Finished") && start > 0) {
                val ms = SystemClock.uptimeMillis() - start
                start = 0
                sampler.removeCallbacks(sample)
                if (ms > FRAME_MS) Log.w("DogBoardJank", "main thread blocked $ms ms: ${what.removePrefix(">>>>> Dispatching to ").take(160)}; caps composed ${capsComposed.get()} drawn ${capsDrawn.get()}${hotFrames()}")
            }
        }
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
    }

    /** The frames this message's samples were in most often.*/
    private fun hotFrames(): String {
        val taken = samples.toList()
        if (taken.isEmpty()) return ""
        fun name(e: StackTraceElement) = "${e.className.substringAfterLast('.')}.${e.methodName}:${e.lineNumber}"
        fun top(keys: List<String?>) = keys.filterNotNull().groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(5).joinToString { "${it.key} ${it.value}" }
        // per sample: the deepest frame of ours (what our code was doing) and the deepest frame at all
        val ours = taken.map { st -> st.firstOrNull { it.className.startsWith("io.github.sillypuppyy") || it.className.startsWith("dev.patrickgold") }?.let(::name) }
        val leaf = taken.map { st -> st.firstOrNull()?.let(::name) }
        return "; ${taken.size} samples; ours: ${top(ours)}; leaf: ${top(leaf)}"
    }
}
