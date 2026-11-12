package io.github.sillypuppyy.dogboard.perf

import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Startup timing marks, logged under the tag `DogBoardPerf`. Each mark reports milliseconds since
 * the process started and since the previous mark, so a cold start can be read straight from
 * `adb logcat -s DogBoardPerf`.
 */
object Perf {
    private const val TAG = "DogBoardPerf"
    @Volatile private var last = 0L

    fun mark(name: String) {
        val now = SystemClock.elapsedRealtime()
        val sinceStart = now - Process.getStartElapsedRealtime()
        val sinceLast = if (last == 0L) sinceStart else now - last
        last = now
        Log.i(TAG, "$name: +${sinceLast}ms (t=${sinceStart}ms)")
    }

    inline fun <T> measure(name: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        val result = block()
        Log.i("DogBoardPerf", "$name took ${SystemClock.elapsedRealtime() - start}ms")
        return result
    }
}
