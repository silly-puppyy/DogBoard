package io.github.sillypuppyy.dogboard.engine

import kotlin.math.exp
import kotlin.math.ln

/**
 * iOS's ensemble step for the neural tap net: its best reading of the typed keys joins the decoder's
 * candidates when it differs from their top and the language model likes it more. It is then
 * sorted in by ω like any other candidate, so it still has to win on score and pass the filters.
 */
object TapNetMerge {
    /**
     * The net's one candidate for [typed] (letters only, at least 3: iOS skips everything else), scored
     * with the decoder's ω and context model and normalised over the net's readings (softmax), or null.
     */
    fun candidate(readings: List<TapNetDecoder.Reading>, typed: String, decoder: Decoder, previous: Int, previous2: Int = -1): Candidate? {
        if (typed.length < 3 || typed.any { it !in 'a'..'z' } || readings.isEmpty()) return null
        // a reading that is the typed string itself stands alone
        val pool = readings.firstOrNull { it.word.equals(typed, ignoreCase = true) }?.let { listOf(it) } ?: readings
        val scored = pool.map {
            decoder.rescored(Candidate(it.word, 0.0, GEOMETRY_WEIGHT * it.ctc, 0.0, edits = 0, isCompletion = false), previous, previous2)
        }
        val max = scored.maxOf { it.score }
        val lse = max + ln(scored.sumOf { exp(it.score - max) })
        val best = scored.maxBy { it.score }
        return best.copy(score = best.score - lse, geometry = best.geometry - lse)
    }

    /** [candidates] (best first) with the net's [net] candidate merged in by iOS's rule. */
    fun merge(candidates: List<Candidate>, net: Candidate?, typed: String): List<Candidate> {
        if (net == null) return candidates
        val top = candidates.firstOrNull() ?: return listOf(net)
        if (top.word.equals(typed, ignoreCase = true)) return candidates // the decoder kept what was typed
        // our possessives are generated in the decoder, not stored: the net reads only stored words, so it
        // can't offer one (iOS stores them, so its net can) and shouldn't overrule one
        if (top.word.any { it == '\'' || it == '’' }) return candidates
        if (net.word == top.word || net.languageModel <= top.languageModel) return candidates
        // both lists normalised (softmax) before they meet: the net's ω is moved onto the decoder's scale
        val max = candidates.maxOf { it.score }
        val lse = max + ln(candidates.sumOf { exp(it.score - max) })
        val shifted = net.copy(score = net.score + lse, geometry = net.geometry + lse)
        if (shifted.score < top.score + MARGIN) return candidates
        return (candidates.filter { it.word != net.word } + shifted).sortedByDescending { it.score }
    }

    /**
     * How far the net's candidate must beat the decoder's top (ω, nats). Not iOS's (it has none): our net
     * isn't Apple's and over-trusts letter drops; 0.5 kept 116 of 144 fixes and 16 of 42 breaks on the
     * control set (2026-11-14), checked on the rare-word set.
     */
    const val MARGIN = 0.5

    /** How much the net's CTC score counts as geometry (iOS's models.plist geometryScoreWeight). */
    const val GEOMETRY_WEIGHT = 0.7048
}
