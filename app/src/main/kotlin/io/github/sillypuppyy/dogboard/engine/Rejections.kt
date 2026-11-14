package io.github.sillypuppyy.dogboard.engine

import java.io.File
import kotlin.math.ln

/**
 * Which autocorrections not to make again, following iOS:
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
    /** [permanent]: the user added "never correct typed to correction" in settings; no expiry. */
    private class Row(var performed: Int = 0, var soft: Int = 0, var hard: Int = 0, var lastSoft: Long = 0, var lastHard: Long = 0, var permanent: Boolean = false)

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

    /** The user said in settings: never correct [typed] to [correction]. Lasts until removed. */
    @Synchronized
    fun neverCorrect(typed: String, correction: String) {
        val row = rows.getOrPut(typed to correction) { Row() }
        row.permanent = true
        row.hard = maxOf(row.hard, 1)
        row.lastHard = now()
        save()
    }

    /** [correction] was made for [typed] and the user typed on without undoing it. */
    @Synchronized
    fun recordKept(typed: String, correction: String) {
        // only pairs the user once rejected have a row: the rest have nothing to reset, and a row
        // (and a file write) per kept correction would grow forever
        val row = rows[typed to correction] ?: return
        row.performed++
        if (row.hard == 0) return
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

    /**
     * Whether autocorrection must not turn [typed] into [correction] now. [consume]: this check
     * is the correction actually being made (a word finished), so a soft block it applies counts
     * toward that block's limit; the suggestion bar's checks while typing don't count.
     */
    @Synchronized
    fun isBlocked(typed: String, correction: String, ignoreSoft: Boolean = false, consume: Boolean = false): Boolean {
        if (!ignoreSoft && softBlocks(typed, correction, consume)) return true
        val row = rows[typed to correction] ?: return false
        if (row.permanent) return true
        if (row.hard == 0) return false
        val days = (now() - row.lastHard) / MS_PER_DAY
        if (days > HARD_MAX_DAYS) return false
        return ln(days) / ln(4.0) < row.hard - 1
    }

    private fun softBlocks(typed: String, correction: String, consume: Boolean): Boolean {
        val t = now()
        deleted.removeAll { t - it.time > SOFT_WINDOW_MS }
        val limit = softLimit(typed, correction)
        val entry = deleted.lastOrNull { it.typed == typed && it.correction == correction && it.count < limit } ?: return false
        if (consume) entry.count++
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
        rows[typed to correction]?.let { it.hard = 0; it.soft = 0; it.lastHard = 0; it.lastSoft = 0; it.permanent = false }
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
                7, 8 -> rows[p[0] to p[1]] = Row(
                    p[2].toIntOrNull() ?: 0, p[3].toIntOrNull() ?: 0, p[4].toIntOrNull() ?: 0,
                    p[5].toLongOrNull() ?: 0, p[6].toLongOrNull() ?: 0, p.getOrNull(7) == "1",
                )
            }
        }
        prune()
    }

    /** Rows that can no longer block anything: no hard rejection in force and no recent soft one. */
    private fun prune() {
        val t = now()
        rows.values.removeAll { r ->
            !r.permanent && (r.hard == 0 || (t - r.lastHard) / MS_PER_DAY > HARD_MAX_DAYS) && t - r.lastSoft > SOFT_WINDOW_MS
        }
    }

    private fun save() {
        val f = file ?: return
        prune()
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.bufferedWriter().use { w ->
                for ((k, r) in rows) w.write("${k.first}\t${k.second}\t${r.performed}\t${r.soft}\t${r.hard}\t${r.lastSoft}\t${r.lastHard}\t${if (r.permanent) 1 else 0}\n")
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
