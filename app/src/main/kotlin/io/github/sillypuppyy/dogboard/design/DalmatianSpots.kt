package io.github.sillypuppyy.dogboard.design

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Kb:675-729, ported 1:1: the Dalmatian theme's spots, 2-4 groups of 2-7 touching keys grown over
 * the key adjacency graph from a seeded random generator, so a seed always draws the same coat.
 * The canvas shuffles with `Array.sort(() => rnd() - 0.5)`; [v8Sort] replays V8's sort so the
 * random draws come in the same order and the coat matches the canvas key for key.
 */
object DalmatianSpots {
    /** One spec key: its kind ("gap" for spacers) and width in key units. */
    data class Key(val kind: String, val w: Double)

    /**
     * The spot index of each spotted key, by "row:column" (columns count gaps, as Kb's ids do).
     * [numRowH] is the canvas's 44 dp number row (0 for DogBoard, whose number row is a full row).
     */
    fun of(spec: List<List<Key>>, kbW: Double, keyAreaH: Double, seed: Int, numRowH: Double = 0.0): Map<String, Int> {
        val units = spec[0].sumOf { it.w }
        val u = kbW / units
        val rowH = (keyAreaH - numRowH) / (spec.size - if (numRowH > 0) 1 else 0)
        data class Geo(val id: String, val ri: Int, val x0: Double, val x1: Double, val cx: Double, val cy: Double, val skip: Boolean, val big: Boolean)
        val geoById = LinkedHashMap<String, Geo>()
        val nbr = HashMap<String, MutableList<String>>()
        var yy = 0.0
        spec.forEachIndexed { ri, row ->
            val rh = if (numRowH > 0 && ri == 0) numRowH else rowH
            var xx = 0.0
            row.forEachIndexed { ci, k ->
                val w = k.w * u
                if (k.kind != "gap") {
                    val id = "$ri:$ci"
                    geoById[id] = Geo(id, ri, xx, xx + w, xx + w / 2, yy + rh / 2, k.kind == "ret", k.kind != "ret" && (k.kind == "space" || k.w > 1.6))
                    nbr[id] = ArrayList()
                }
                xx += w
            }
            yy += rh
        }
        val spotOf = LinkedHashMap<String, Int>()
        var s = seed
        // mulberry32
        fun rnd(): Double {
            s += 0x6D2B79F5
            var z = s
            z = (z xor (z ushr 15)) * (z or 1)
            z = z xor (z + (z xor (z ushr 7)) * (z or 61))
            return ((z xor (z ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
        }
        fun round(x: Double) = floor(x + 0.5) // JS Math.round
        val geo = geoById.values.toList()
        for (a in geo) for (b in geo) {
            if (a.ri == b.ri && kotlin.math.abs(a.x1 - b.x0) < u * 0.3) { nbr.getValue(a.id).add(b.id); nbr.getValue(b.id).add(a.id) }
            if (b.ri == a.ri + 1 && min(a.x1, b.x1) - max(a.x0, b.x0) > u * 0.4) { nbr.getValue(a.id).add(b.id); nbr.getValue(b.id).add(a.id) }
        }
        val taken = HashSet<String>(); val blocked = HashSet<String>()
        fun pickW(opts: List<Pair<Int, Double>>): Int {
            var r = rnd() * opts.sumOf { it.second }
            for (o in opts) { r -= o.second; if (r <= 0) return o.first }
            return opts.last().first
        }
        val nSpots = pickW(listOf(2 to 15.0, 3 to 40.0, 4 to 45.0))
        val order = v8Sort(MutableList(nSpots) { it }) { _, _ -> rnd() - 0.5 }
        val budget = round(geo.size * 0.4).toInt()
        val total = min(budget.toDouble(), round(geo.size * (0.3 + rnd() * 0.12)))
        val wants = List(nSpots) { 0.4 + rnd() * 1.2 }
        val wsum = wants.sum()
        var sp = 0
        for (slot in 0 until nSpots) {
            val want = max(2.0, min(7.0, round(total * wants[slot] / wsum))).toInt()
            var mine: List<String>? = null
            var attempt = 0
            while (attempt < 6 && mine == null) {
                attempt++
                val px = ((order[slot] + 0.15 + rnd() * 0.7) / nSpots) * kbW
                val py = rnd() * (keyAreaH - numRowH) + numRowH
                var first: Geo? = null; var bd = 1e9
                for (g in geo) {
                    if (g.skip || g.id in taken || g.id in blocked) continue
                    val d = hypot(g.cx - px, g.cy - py)
                    if (d < bd) { bd = d; first = g }
                }
                if (first == null) break
                val got = mutableListOf(first.id); val local = HashSet(got)
                while (got.size < want) {
                    val cand = LinkedHashMap<String, Double>()
                    for (id in got) for (n in nbr.getValue(id)) {
                        val g = geoById.getValue(n)
                        if (g.skip || g.big || first.big || n in taken || n in blocked || n in local) continue
                        if (hypot((g.cx - first.cx) / u, (g.cy - first.cy) / rowH) > 2.1) continue
                        cand[n] = (cand[n] ?: 0.0) + if (g.ri == geoById.getValue(id).ri) 1.0 else 1.3
                    }
                    val ids = cand.keys.toList(); if (ids.isEmpty()) break
                    val wts = ids.map { cand.getValue(it).pow(1.5) }
                    var r = rnd() * wts.sum(); var pickId = ids.last()
                    for (i in ids.indices) { r -= wts[i]; if (r <= 0) { pickId = ids[i]; break } }
                    got.add(pickId); local.add(pickId)
                }
                if (got.size >= 2 || first.big) mine = got
            }
            var m = mine ?: continue
            if (taken.size + m.size > budget) m = m.take(max(2, budget - taken.size))
            if (taken.size + m.size > budget) break
            m.forEach { taken.add(it); spotOf[it] = sp }
            m.forEach { id -> nbr.getValue(id).forEach { n -> if (n !in taken) blocked.add(n) } }
            sp++
        }
        return spotOf
    }

    /**
     * V8's Array.prototype.sort as it runs on a short array: binary insertion sort from the first
     * element, [cmp] called in V8's order (checked against node's V8: 2000 random runs of 2-4
     * elements, same calls, same result).
     */
    fun <T> v8Sort(a: MutableList<T>, cmp: (T, T) -> Double): List<T> {
        for (start in 1 until a.size) {
            var left = 0; var right = start
            val pivot = a[start]
            while (left < right) {
                val mid = left + ((right - left) shr 1)
                if (cmp(pivot, a[mid]) < 0) right = mid else left = mid + 1
            }
            for (p in start downTo left + 1) a[p] = a[p - 1]
            a[left] = pivot
        }
        return a
    }
}
