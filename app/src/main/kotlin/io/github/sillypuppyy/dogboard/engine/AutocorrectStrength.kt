package io.github.sillypuppyy.dogboard.engine

import java.io.File
import kotlin.math.exp

/**
 * Autocorrect that adapts to the user (user's design, 2026-11-15): it grows more careful when the
 * user undoes its corrections and bolder when they keep them or fix, from the suggestion bar, typos
 * it left alone. (Gboard has a learned per-user threshold, built but switched off on the device we
 * studied; this is a simple, explainable stand-in.)
 *
 * A score moves with each event: +1 for a reverted correction, +0.5 for a correction deleted right
 * away, -1/9 for a kept one (so a steady 10 % revert rate holds it still), -1 for a fix picked from
 * the bar. Clamped to ±[SCORE_LIMIT]; nothing changes inside ±[DEAD_ZONE] (user, 2026-11-15: "don't
 * make it overly sensitive, we want a clear signal that the user is being over corrected"): past it
 * the chosen level's "lead over the typed letters" scales linearly up to e^±[MAX_SHIFT] (±19 %):
 * Standard 1.39 moves within 1.17 - 1.65, always between Strong (1.0) and Gentle (2.0). With 30 %
 * of corrections undone it starts after about 23 corrections and is at full effect after about 90.
 */
class AutocorrectStrength(private val file: File? = null) {
    @Volatile
    var score: Double = file?.takeIf { it.exists() }?.readText()?.trim()?.toDoubleOrNull()?.coerceIn(-SCORE_LIMIT, SCORE_LIMIT) ?: 0.0
        private set

    fun reverted() = move(REVERTED)
    fun deletedAfterCorrection() = move(DELETED)
    fun kept() = move(KEPT)
    fun fixPicked() = move(FIX_PICKED)

    /** [level] made more careful (score > 0) or bolder (score < 0). */
    fun adjust(level: Autocorrect.Level): Autocorrect.Level {
        val beyond = kotlin.math.abs(score) - DEAD_ZONE
        if (beyond <= 0) return level
        val shift = MAX_SHIFT * kotlin.math.sign(score) * beyond / (SCORE_LIMIT - DEAD_ZONE)
        return level.copy(leadOverTyped = level.leadOverTyped * exp(shift))
    }

    fun reset() {
        score = 0.0
        save()
    }

    @Synchronized
    private fun move(by: Double) {
        score = (score + by).coerceIn(-SCORE_LIMIT, SCORE_LIMIT)
        save()
    }

    private fun save() {
        file?.writeTextAtomically("%.4f".format(java.util.Locale.ROOT, score))
    }

    companion object {
        const val SCORE_LIMIT = 20.0
        const val DEAD_ZONE = 5.0
        const val MAX_SHIFT = 0.17
        const val REVERTED = 1.0
        const val DELETED = 0.5
        const val KEPT = -1.0 / 9
        const val FIX_PICKED = -1.0
    }
}
