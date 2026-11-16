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
            val parts = synchronized(sections) { sections.entries.joinToString { "${it.key} ${it.value / 1024} KB" }.also { sections.clear() } }
            Log.i("DogBoardPerf", "gc: ${(allocated - lastAllocated) / 1024} KB allocated this word ($parts); blocking GCs $blocking ($blockingMs ms total)")
        }
        lastAllocated = allocated
    }

    // debug builds: memory allocated by named sections of the typing path (on the calling thread),
    // summed per word and logged with it, to find what allocates
    private val sections = LinkedHashMap<String, Long>()
    @Volatile private var counting = false

    @Suppress("DEPRECATION")
    fun <T> track(name: String, block: () -> T): T {
        if (!dev.patrickgold.florisboard.BuildConfig.DEBUG) return block()
        if (!counting) { Debug.startAllocCounting(); counting = true }
        val a0 = Debug.getThreadAllocSize()
        val r = block()
        val used = Debug.getThreadAllocSize() - a0
        synchronized(sections) { sections[name] = (sections[name] ?: 0L) + used }
        return r
    }

    private fun stat(key: String): Long? = Debug.getRuntimeStat(key)?.toLongOrNull()
}
