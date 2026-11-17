package io.github.sillypuppyy.dogboard.engine

import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.ln

/**
 * Decides whether the word typed so far is replaced when the user ends it (space, punctuation),
 * following the iOS rules: the top-ranked candidate
 * wins unless it is the typed word itself; completions of what was typed need a long enough stem
 * and a clear lead over the runner-up.
 */
class Autocorrect(private val lexicon: Lexicon, private val defaultLevel: Level = Level.STANDARD) {
    /**
     * How readily words are changed (design: Gentle / Standard / Strong; Off is not calling this).
     * Standard is iOS; the others were measured on the eval words (settings-model.md §4).
     */
    data class Level(
        /** A completion must be this many times more likely than the next candidate. */
        val dominanceRatio: Double,
        /** Minimum typed length for completing 1, 2, or 3+ more letters. */
        val minStemOneLetter: Int,
        val minStemTwoLetters: Int,
        val minStemMoreLetters: Int,
        /**
         * A correction must be this many times more likely than the typed letters as they are
         * (iOS: 1.39, FilterNondominantCorrectionOfMoreWords with one typed word).
         */
        val leadOverTyped: Double = SORT_KEY_MATCH_BOOST_RATIO,
        /**
         * Beyond iOS: a non-word missing only its last letter ("befor" -> "before") may be fixed from
         * this typed length on when the decoder read it as a skipped key, not as a completion (iOS's
         * stem rule above looks only at the strings and keeps "befor"). null = off (iOS); Standard: 3.
         */
        val minStemEndOmission: Int? = null,
        /**
         * How much context outweighs the taps: candidates are re-ranked by geometry + this x their
         * language score (the decoder's own power is iOS's 0.24). null = the decoder's ranking.
         * Real phone typos (ranking, 2026-11-16): 0.40 puts the intended word on top 59.2 % vs 52.1 %
         * of typos, at 98.8 % vs 99.0 % of correct words kept (Strong's trade).
         */
        val languagePower: Double? = null,
    ) {
        companion object {
            // minStemEndOmission 3 (user 2026-11-16: "that counts as a typo"): real phone typos fixed 47.5 -> 47.9 %,
            // correct words broken unchanged (0.6 %), control 98.5 % / unknown 95.8 % unchanged
            val STANDARD = Level(MIN_DOMINANCE_RATIO, 8, 6, 4, minStemEndOmission = 3)
        }
    }

    companion object {
        /** iOS MinSuggestionDominanceRatio. */
        const val MIN_DOMINANCE_RATIO = 2.7

        /**
         * Rule 3: words run together are only split when the words are at least this likely in
         * context compared with on their own (iOS "insufficient linguistic support").
         */
        private val MIN_CONTEXT_SUPPORT = ln(0.1)

        /**
         * iOS FilterNondominantCorrectionOfMoreWords (SortKeyMatchBoostRatio 1.39): a candidate
         * that keeps more of the typed words wins when within this ratio of the top. For one typed
         * word: what was typed stays unless the correction is 1.39x more likely.
         */
        const val SORT_KEY_MATCH_BOOST_RATIO = 1.39

        /** iOS BoostMinimumVariation: a plainer spelling this close to the top goes first. */
        private val MIN_VARIATION_RATIO = ln(1.1)

        /** Characters that make a completion a "special edit" (contractions and the like). */
        private val SPECIAL_EDIT_CHARS = setOf('\'', '’', '&')

        /** The casing rules when nothing more is known: capitals in [typed] count as shifted by the user. */
        fun inferredCase(typed: String): (String) -> String? {
            val flags = typed.map { if (it.isUpperCase()) CaseAdapt.SHIFTED else 0 }
            val context = if (flags.firstOrNull() == CaseAdapt.SHIFTED) CaseAdapt.Context.SHIFT else CaseAdapt.Context.NONE
            return { word -> CaseAdapt.apply(word, typed, flags, context, CaseAdapt.NoDictionary) }
        }
    }

    /**
     * Returns the word to commit instead of [typed] (as typed, with its casing), or null to keep it.
     *
     * @param candidates decoder output for the same taps, best first, including the typed string
     *   itself when it is not a known word
     * @param previousCorrection what this rule chose at the previous keystroke, if anything
     *   (a completion that was already the choice one letter earlier skips the length gate)
     */
    fun correction(
        typed: String,
        candidates: List<Candidate>,
        previousCorrection: String? = null,
        case: (String) -> String? = inferredCase(typed),
        level: Level = defaultLevel,
    ): String? {
        // iOS apply_case_changes: each candidate takes the typed capitals; ones the case rules
        // don't accept for this input are filtered out
        val ranked = ranked(candidates, level)
        val cased = ranked
            .filter { it.words == 1 || it.contextSupport >= MIN_CONTEXT_SUPPORT } // Rule 3
            .mapNotNull { c -> case(c.word)?.let { c to it } }
            .let(::boostPlainer)
        val (top, t) = cased.firstOrNull() ?: return null
        // Rule 1: the typed word wins.
        if (t == typed) return null
        // Rule 2: never complete a one-letter word into a longer word ("a" -> "and").
        if (typed.length == 1 && t.length > 1 && t.startsWith(typed, ignoreCase = true)) return null
        // iOS filter 20 (keep more typed words when nearly as likely), and Gentle's larger margin;
        // a correction with the typed letters ("dont" -> "don't") keeps the typed word already
        if (sortKey(t) != sortKey(typed)) {
            val asTyped = ranked.firstOrNull { it.isTypedString || it.word.equals(typed, ignoreCase = true) }
            // the typed word stays, as the dictionary spells it ("mr" -> "Mr", as when it is the top)
            if (asTyped != null && top.score - asTyped.score < ln(level.leadOverTyped)) return keptAsTyped(typed, asTyped, case)
        }
        // Rule 4: a correction that is not a completion of what was typed is taken as is.
        if (!(t.length >= typed.length && t.startsWith(typed))) return t
        // Rule 5: a completion needs a long enough stem (or a contraction-like ending, or the same
        // choice as one keystroke earlier) and must clearly beat the runner-up.
        val gain = t.length - typed.length
        val specialEdit = t.substring(typed.length).any { it in SPECIAL_EDIT_CHARS }
        val minStem = when {
            gain <= 1 -> level.minStemOneLetter
            gain == 2 -> level.minStemTwoLetters
            else -> level.minStemMoreLetters
        }
        // a typed non-word one letter short of a word the decoder read as a skipped last key
        val endOmission = gain == 1 && !top.isCompletion && !top.isTypedString && level.minStemEndOmission != null &&
            typed.length >= level.minStemEndOmission && !lexicon.contains(typed)
        val stemOk = specialEdit || endOmission || (previousCorrection != null && t.equals(previousCorrection, ignoreCase = true)) ||
            typed.length >= minStem
        if (!stemOk) return null
        val (second, s) = cased.getOrNull(1) ?: return t
        if (s.equals(t, ignoreCase = true) || s.startsWith(t, ignoreCase = true)) return t
        return if (level.dominanceRatio * exp(second.score) < exp(top.score)) t else null
    }

    /** The typed word kept: its dictionary capitals when they are all that differs ("Mr"), else null (left as typed). */
    private fun keptAsTyped(typed: String, asTyped: Candidate, case: (String) -> String?): String? {
        if (asTyped.isTypedString) return null
        val t = case(asTyped.word) ?: return null
        return t.takeIf { it != typed && it.equals(typed, ignoreCase = true) }
    }

    /**
     * iOS BoostMinimumVariation (ratio 1.1): when a spelling of the same letters with fewer
     * apostrophes, hyphens or accents scores within 10% of the top ("were" vs "we're"), it goes
     * first. Only spellings without capitals after the first letter qualify.
     */
    private fun boostPlainer(cased: List<Pair<Candidate, String>>): List<Pair<Candidate, String>> {
        if (cased.size < 2) return cased
        val (top, t) = cased[0]
        val key = sortKey(t)
        var best = variation(t)
        var pick = -1
        for (j in 1 until cased.size) {
            val (c, w) = cased[j]
            if (c.score + MIN_VARIATION_RATIO < top.score) break
            val v = variation(w)
            if (v < best && w.drop(1).none { it.isUpperCase() } && sortKey(w) == key) {
                best = v
                pick = j
            }
        }
        if (pick < 0) return cased
        return listOf(cased[pick]) + cased.filterIndexed { i, _ -> i != pick }
    }

    /** Marks inside words (apostrophes, hyphens, dots) plus accented letters. */
    private fun variation(word: String): Int {
        val medial = (1 until word.length - 1).count { i -> !word[i].isLetterOrDigit() && word[i] != ' ' }
        return medial + word.count { Normalizer.normalize(it.toString(), Normalizer.Form.NFD).length > 1 }
    }

}

/** The letters only, without case or accents ("We're" and "were" share one): iOS's sort key. */
internal fun sortKey(word: String): String =
    Normalizer.normalize(word, Normalizer.Form.NFD).filter { it.isLetterOrDigit() }.lowercase()

/**
 * iOS exact-typed-string veto: the typed string (a candidate when
 * the lexicon lacks it) is at least as likely, by the language-model terms alone, as the top
 * candidate: no autocorrection.
 */
/**
 * [candidates] ranked with [level]'s own language weight (geometry + weight x language score), or as
 * the decoder ranked them; the correction and the typed-string veto both use this ranking.
 */
internal fun ranked(candidates: List<Candidate>, level: Autocorrect.Level): List<Candidate> =
    level.languagePower?.let { w ->
        candidates.map { it.copy(score = it.geometry + w * it.languageModel) }.sortedByDescending { it.score }
    } ?: candidates

/**
 * iOS refines the candidates with its neural model at the word level (all candidates in one batch,
 * decompile 2026-11-16): each candidate's language score is mixed with the neural ln P(candidate |
 * text before) as (1 - [weight]) x n-gram + [weight] x neural, and the score is geometry + [power] x
 * that; a candidate the model has no score for keeps its n-gram score.
 */
internal fun withNeural(candidates: List<Candidate>, neural: (String) -> Double?, weight: Double, power: Double): List<Candidate> =
    candidates.map { c ->
        neural(c.word)?.let { n ->
            val l = (1 - weight) * c.languageModel + weight * n
            c.copy(score = c.geometry + power * l, languageModel = l)
        } ?: c
    }.sortedByDescending { it.score }

internal fun typedStringVeto(candidates: List<Candidate>): Boolean {
    val typed = candidates.firstOrNull { it.isTypedString } ?: return false
    val top = candidates.maxByOrNull { it.score } ?: return false
    return top !== typed && typed.languageModel >= top.languageModel
}
