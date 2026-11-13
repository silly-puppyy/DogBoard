package io.github.sillypuppyy.dogboard.engine

import java.io.File
import kotlin.math.ln

/**
 * Which autocorrections not to make again, following iOS
 * :
 *
 * Hard rejections (the user undid a correction, or kept their own word while it was offered)
 * are counted per (typed, correction) with the time of the last one. The pair is blocked while
 * it has at least one, the last is at most [HARD_MAX_DAYS] old, and log4(days since the last) is
 * below (count − 1): one undo blocks for under a day, two for 4 days, three for 16, four for 64,
 * then the 180-day cap. A correction that is made and kept resets the count.
 *
 * Soft rejections: deleting a word soon after it was autocorrected (not an immediate undo) blocks
 * that correction for [SOFT_WINDOW_MS]. The last [SOFT_MAX_DELETED] such deletions are remembered,
 * each for at most a limit of blocks: 1 for one-letter pairs, 3 when the two differ only in case,
 * accents or punctuation, else 20.
 */
class Rejections(private val file: File? = null, private val now: () -> Long = System::currentTimeMillis) {
    private class Row(var performed: Int = 0, var soft: Int = 0, var hard: Int = 0, var lastSoft: Long = 0, var lastHard: Long = 0)

    private class Deleted(val typed: String, val correction: String, val time: Long, var count: Int = 0)

    private val rows = HashMap<Pair<String, String>, Row>()
    private val recentCorrections = ArrayDeque<Pair<String, String>>() // (typed, correction), newest last
    private val deleted = ArrayList<Deleted>()

    init {
        load()
    }

    /** The user rejected [correction] for [typed] (undo, or kept their word while it was offered). */
    @Synchronized
    fun recordHard(typed: String, correction: String) {
        val row = rows.getOrPut(typed to correction) { Row() }
        row.hard++
        row.lastHard = now()
        save()
    }

    /** [correction] was made for [typed] and the user typed on without undoing it. */
    @Synchronized
    fun recordKept(typed: String, correction: String) {
        val row = rows.getOrPut(typed to correction) { Row() }
        row.performed++
        row.hard = 0
        save()
    }

    /** [correction] was just made for [typed] (for soft rejections if it gets deleted soon). */
    @Synchronized
    fun noteCorrected(typed: String, correction: String) {
        recentCorrections.addLast(typed to correction)
        while (recentCorrections.size > RECENT_CORRECTIONS) recentCorrections.removeFirst()
    }

    /** The user deleted [word]; if it was just made by autocorrection, that correction is softly rejected. */
    @Synchronized
    fun noteDeleted(word: String) {
        val (typed, correction) = recentCorrections.lastOrNull { it.second == word } ?: return
        if (deleted.size >= SOFT_MAX_DELETED) deleted.remove(deleted.minBy { it.time })
        deleted.add(Deleted(typed, correction, now()))
        val row = rows.getOrPut(typed to correction) { Row() }
        row.soft++
        row.lastSoft = now()
        save()
    }

    /** Whether autocorrection must not turn [typed] into [correction] now. */
    @Synchronized
    fun isBlocked(typed: String, correction: String, ignoreSoft: Boolean = false): Boolean {
        if (!ignoreSoft && softBlocks(typed, correction)) return true
        val row = rows[typed to correction] ?: return false
        if (row.hard == 0) return false
        val days = (now() - row.lastHard) / MS_PER_DAY
        if (days > HARD_MAX_DAYS) return false
        return ln(days) / ln(4.0) < row.hard - 1
    }

    private fun softBlocks(typed: String, correction: String): Boolean {
        val t = now()
        deleted.removeAll { t - it.time > SOFT_WINDOW_MS }
        val limit = softLimit(typed, correction)
        val entry = deleted.lastOrNull { it.typed == typed && it.correction == correction && it.count < limit } ?: return false
        entry.count++
        return true
    }

    /** Hard-rejection counts per pair, for the settings screen and tests. */
    @Synchronized
    fun hardCount(typed: String, correction: String): Int = rows[typed to correction]?.hard ?: 0

    /** Corrections the user undid, for the settings screen: (typed, correction, times), most first. */
    @Synchronized
    fun undone(): List<Triple<String, String, Int>> =
        rows.filter { it.value.hard > 0 }.map { (k, r) -> Triple(k.first, k.second, r.hard) }.sortedByDescending { it.third }

    /** The user took back their undo of [correction] for [typed]: it may be made again. */
    @Synchronized
    fun restore(typed: String, correction: String) {
        rows[typed to correction]?.let { it.hard = 0; it.soft = 0; it.lastHard = 0; it.lastSoft = 0 }
        save()
    }

    @Synchronized
    fun clear() {
        rows.clear()
        deleted.clear()
        recentCorrections.clear()
        save()
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        f.forEachLine { line ->
            val p = line.split('\t')
            when (p.size) {
                // the earlier format: "typed<TAB>correction", a permanent block; becomes one hard
                // rejection dated by the file
                2 -> rows.getOrPut(p[0] to p[1]) { Row() }.apply { hard = maxOf(hard, 1); lastHard = f.lastModified() }
                7 -> rows[p[0] to p[1]] = Row(p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toLong(), p[6].toLong())
            }
        }
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.bufferedWriter().use { w ->
                for ((k, r) in rows) w.write("${k.first}\t${k.second}\t${r.performed}\t${r.soft}\t${r.hard}\t${r.lastSoft}\t${r.lastHard}\n")
            }
            tmp.renameTo(f)
        }
    }

    companion object {
        const val HARD_MAX_DAYS = 180.0
        const val SOFT_WINDOW_MS = 120_000L
        const val SOFT_MAX_DELETED = 3
        private const val RECENT_CORRECTIONS = 20
        private const val MS_PER_DAY = 86_400_000.0

        /** iOS: 1 for one-letter pairs; 3 if they differ only in case, accents or punctuation; else 20. */
        fun softLimit(typed: String, correction: String): Int {
            if (typed.length == 1 && correction.length == 1) return 1
            fun plain(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .filter { it.isLetterOrDigit() }.lowercase()
            return if (plain(typed) == plain(correction)) 3 else 20
        }
    }
}
