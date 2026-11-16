package io.github.sillypuppyy.dogboard.perf

import android.os.Debug
import android.util.Log

/**
 * Garbage-collection pressure per typed word (ART's runtime stats), logged under `DogBoardPerf`:
 * bytes allocated since the last word, and the blocking collections so far with their total time.
 * A blocking collection stalls the typing thread (the short freezes, user 2026-11-16).
 */
object GcStats {
    private var lastAllocated = -1L

    fun logWord() {
        val allocated = stat("art.gc.bytes-allocated") ?: return
        val blocking = stat("art.gc.blocking-gc-count") ?: 0
        val blockingMs = stat("art.gc.blocking-gc-time") ?: 0
        if (lastAllocated >= 0) {
            Log.i("DogBoardPerf", "gc: ${(allocated - lastAllocated) / 1024} KB allocated this word; blocking GCs $blocking ($blockingMs ms total)")
        }
        lastAllocated = allocated
    }

    private fun stat(key: String): Long? = Debug.getRuntimeStat(key)?.toLongOrNull()
}
