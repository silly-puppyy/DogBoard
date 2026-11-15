package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.KeyTarget
import java.io.File

/**
 * Personal key-centre offsets, as in "Spatial model personalization in Gboard" (Google, arXiv
 * 2209.11311; user, 2026-11-15: "build them that way"): where this user actually taps each key,
 * learned on the phone from words they typed and kept.
 *
 * - Data: for each letter of a word typed by tapping and kept as typed, the tap's offset from the
 *   key centre, in half key widths / heights (so it holds across layouts and orientations).
 * - History: the latest [BUCKETS] x [BUCKET_SIZE] = 800 taps; per bucket only per-key sums are kept
 *   (count, sum dx, sum dy), so the typed text can't be read back; the oldest bucket expires whole.
 *   The paper found decaying old buckets made no difference: all count the same.
 * - Clusters (the paper's Algorithm 1): starting from one cluster of all keys, greedily make the
 *   horizontal or vertical split that most increases f(C1) + f(C2) - f(C), f(C) = |C| (E[dx]^2 +
 *   E[dy]^2), until [CLUSTERS] clusters; every key moves by its cluster's mean offset.
 * - Used once [MIN_TAPS] taps are in (one full bucket).
 *
 * The paper's live result: small but significant (words changed after typing -0.4..-1.8 %, typing
 * speed +0.2..+0.6 %).
 */
class PersonalOffsets(private val file: File? = null) {
    private class Stat(var n: Int = 0, var sx: Double = 0.0, var sy: Double = 0.0)

    /** Oldest first; the last is being filled. */
    private val buckets = ArrayDeque<HashMap<Char, Stat>>()
    private var lastSize = 0

    @Volatile
    private var cache: Pair<Map<Char, KeyTarget>, Map<Char, KeyTarget>>? = null

    init {
        load()
    }

    /** Taps in the history. */
    val taps: Int get() = synchronized(this) { (buckets.size - 1).coerceAtLeast(0) * BUCKET_SIZE + lastSize }

    /** One kept word's letters: (letter, dx, dy), offsets in half key widths / heights. */
    @Synchronized
    fun record(samples: List<Triple<Char, Double, Double>>) {
        if (samples.isEmpty()) return
        for ((c, dx, dy) in samples) {
            if (buckets.isEmpty() || lastSize >= BUCKET_SIZE) {
                buckets.addLast(HashMap())
                lastSize = 0
                while (buckets.size > BUCKETS) buckets.removeFirst()
            }
            val s = buckets.last().getOrPut(c.lowercaseChar()) { Stat() }
            s.n++; s.sx += dx; s.sy += dy
            lastSize++
        }
        cache = null
        save()
    }

    /** [keys] with each letter key's centre moved by its cluster's offset (unchanged until [MIN_TAPS]). */
    fun apply(keys: Map<Char, KeyTarget>): Map<Char, KeyTarget> {
        cache?.let { (k, v) -> if (k === keys) return v }
        val offsets = offsets(keys)
        val moved = if (offsets.isEmpty()) keys else keys.mapValues { (c, k) ->
            val o = offsets[c.lowercaseChar()] ?: return@mapValues k
            k.copy(centerX = (k.centerX + o.first * k.halfWidth).toFloat(), centerY = (k.centerY + o.second * k.halfHeight).toFloat())
        }
        cache = keys to moved
        return moved
    }

    /** Per-key offsets (half key widths / heights) from the clusters, empty until [MIN_TAPS]. */
    @Synchronized
    fun offsets(keys: Map<Char, KeyTarget>): Map<Char, Pair<Double, Double>> {
        if (taps < MIN_TAPS) return emptyMap()
        val total = HashMap<Char, Stat>()
        for (b in buckets) for ((c, s) in b) total.getOrPut(c) { Stat() }.let { it.n += s.n; it.sx += s.sx; it.sy += s.sy }
        val letters = keys.keys.map { it.lowercaseChar() }.filter { it.isLetter() }.distinct()
        val clusters = cluster(letters, { keys[it] ?: keys.getValue(it.uppercaseChar()) }, total)
        val out = HashMap<Char, Pair<Double, Double>>()
        for (cl in clusters) {
            val n = cl.sumOf { total[it]?.n ?: 0 }
            if (n == 0) continue
            val o = cl.sumOf { total[it]?.sx ?: 0.0 } / n to cl.sumOf { total[it]?.sy ?: 0.0 } / n
            for (c in cl) out[c] = o
        }
        return out
    }

    @Synchronized
    fun reset() {
        buckets.clear(); lastSize = 0; cache = null
        save()
    }

    private fun save() {
        val f = file ?: return
        val sb = StringBuilder("#dogboard-personal-offsets 1\n")
        buckets.forEachIndexed { i, b ->
            sb.append("bucket ").append(if (i == buckets.size - 1) lastSize else BUCKET_SIZE).append('\n')
            for ((c, s) in b) sb.append(c).append('\t').append(s.n).append('\t').append("%.4f".format(java.util.Locale.ROOT, s.sx)).append('\t').append("%.4f".format(java.util.Locale.ROOT, s.sy)).append('\n')
        }
        f.writeTextAtomically(sb.toString())
    }

    private fun load() {
        val f = file?.takeIf { it.exists() } ?: return
        runCatching {
            for (line in f.readLines()) {
                when {
                    line.startsWith("#") || line.isBlank() -> {}
                    line.startsWith("bucket ") -> { buckets.addLast(HashMap()); lastSize = line.removePrefix("bucket ").trim().toInt() }
                    else -> {
                        val p = line.split('\t')
                        buckets.last()[p[0][0]] = Stat(p[1].toInt(), p[2].toDouble(), p[3].toDouble())
                    }
                }
            }
            while (buckets.size > BUCKETS) buckets.removeFirst()
        }.onFailure { buckets.clear(); lastSize = 0 }
    }

    companion object {
        const val BUCKETS = 4
        const val BUCKET_SIZE = 200
        const val CLUSTERS = 7
        const val MIN_TAPS = BUCKET_SIZE

        /**
         * The paper's Algorithm 1: greedy rectangular splits of [letters] (by their key centres) that
         * most increase f(C1) + f(C2) - f(C), f(C) = |C| (E[dx]^2 + E[dy]^2), up to [CLUSTERS] clusters.
         */
        private fun cluster(letters: List<Char>, key: (Char) -> KeyTarget, stats: Map<Char, Stat>): List<List<Char>> {
            fun f(c: List<Char>): Double {
                val n = c.sumOf { stats[it]?.n ?: 0 }
                if (n == 0) return 0.0
                val mx = c.sumOf { stats[it]?.sx ?: 0.0 } / n
                val my = c.sumOf { stats[it]?.sy ?: 0.0 } / n
                return n * (mx * mx + my * my)
            }
            fun splits(c: List<Char>): List<Pair<List<Char>, List<Char>>> {
                val out = ArrayList<Pair<List<Char>, List<Char>>>()
                // horizontal lines between rows, vertical lines between key centres
                for (ys in c.map { key(it).centerY }.distinct().sorted().zipWithNext()) {
                    val line = (ys.first + ys.second) / 2
                    out.add(c.partition { key(it).centerY < line })
                }
                for (xs in c.map { key(it).centerX }.distinct().sorted().zipWithNext()) {
                    val line = (xs.first + xs.second) / 2
                    out.add(c.partition { key(it).centerX < line })
                }
                return out
            }
            val leaves = mutableListOf(letters)
            while (leaves.size < CLUSTERS && leaves.any { it.size >= 2 }) {
                var best = 0.0
                var bestLeaf = -1
                var bestSplit: Pair<List<Char>, List<Char>>? = null
                for ((i, c) in leaves.withIndex()) for (s in splits(c)) {
                    val r = f(s.first) + f(s.second) - f(c)
                    if (r > best) { best = r; bestLeaf = i; bestSplit = s }
                }
                val s = bestSplit ?: break
                leaves.removeAt(bestLeaf)
                leaves.add(s.first); leaves.add(s.second)
            }
            return leaves
        }
    }
}
