package io.github.sillypuppyy.dogboard.perf

import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log

/**
 * Debug builds: finds what stalls the keyboard. Every main-thread message (key handling, frames,
 * callbacks) that runs longer than a frame is logged under `DogBoardJank` with its duration and
 * what it was; StrictMode logs disk and network access on the main thread. Release builds don't
 * install it (the message printer formats a string per message).
 */
object JankWatch {
    private const val FRAME_MS = 16L
    private var start = 0L
    private var what = ""
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        Looper.getMainLooper().setMessageLogging { line ->
            if (line.startsWith(">>>>> Dispatching")) {
                start = SystemClock.uptimeMillis()
                what = line
            } else if (line.startsWith("<<<<< Finished") && start > 0) {
                val ms = SystemClock.uptimeMillis() - start
                if (ms > FRAME_MS) Log.w("DogBoardJank", "main thread blocked $ms ms: ${what.removePrefix(">>>>> Dispatching to ").take(160)}")
                start = 0
            }
        }
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
    }
}
