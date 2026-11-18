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

    /** [track] split in two, for code that can't go in a lambda (a composable call): -1 = not counting. */
    @Suppress("DEPRECATION")
    fun begin(): Long {
        if (!dev.patrickgold.florisboard.BuildConfig.DEBUG) return -1L
        if (!counting) { Debug.startAllocCounting(); counting = true }
        return Debug.getThreadAllocSize().toLong()
    }

    @Suppress("DEPRECATION")
    fun end(name: String, a0: Long) {
        if (a0 < 0) return
        val used = Debug.getThreadAllocSize() - a0
        synchronized(sections) { sections[name] = (sections[name] ?: 0L) + used }
    }

    private var mainLast = -1L

    /**
     * Debug builds: everything the UI thread allocated since the last call, as section "UI thread (all)"
     * (called on each key on the UI thread, so the sum per word is the UI thread's whole share).
     */
    @Suppress("DEPRECATION")
    fun markUiThread() {
        if (!dev.patrickgold.florisboard.BuildConfig.DEBUG) return
        if (!counting) { Debug.startAllocCounting(); counting = true }
        val now = Debug.getThreadAllocSize().toLong()
        if (mainLast >= 0) synchronized(sections) { sections["UI thread (all)"] = (sections["UI thread (all)"] ?: 0L) + (now - mainLast) }
        mainLast = Debug.getThreadAllocSize().toLong()
    }

    private fun stat(key: String): Long? = Debug.getRuntimeStat(key)?.toLongOrNull()
}
