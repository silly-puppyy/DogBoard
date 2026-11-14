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
    /** Which of "Xs" / "X's" is the usual spelling ([PreferredForms]); null: no preference (#28 off). */
    @Volatile
    var preferredForms: PreferredForms? = null

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
    ) {
        companion object {
            val STANDARD = Level(MIN_DOMINANCE_RATIO, 8, 6, 4)
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
        val cased = candidates
            .filter { it.words == 1 || it.contextSupport >= MIN_CONTEXT_SUPPORT } // Rule 3
            .mapNotNull { c -> case(c.word)?.let { c to it } }
            .let(::boostPlainer)
            .let(::boostPreferredForm)
        val (top, t) = cased.firstOrNull() ?: return null
        // Rule 1: the typed word wins.
        if (t == typed) return null
        // Rule 2: never complete a one-letter word into a longer word ("a" -> "and").
        if (typed.length == 1 && t.length > 1 && t.startsWith(typed, ignoreCase = true)) return null
        // iOS filter 20 (keep more typed words when nearly as likely), and Gentle's larger margin;
        // a correction with the typed letters ("dont" -> "don't") keeps the typed word already
        if (sortKey(t) != sortKey(typed)) {
            val asTyped = candidates.firstOrNull { it.isTypedString || it.word.equals(typed, ignoreCase = true) }
            if (asTyped != null && top.score - asTyped.score < ln(level.leadOverTyped)) return null
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
        val stemOk = specialEdit || (previousCorrection != null && t.equals(previousCorrection, ignoreCase = true)) ||
            typed.length >= minStem
        if (!stemOk) return null
        val (second, s) = cased.getOrNull(1) ?: return t
        if (s.equals(t, ignoreCase = true) || s.startsWith(t, ignoreCase = true)) return t
        return if (level.dominanceRatio * exp(second.score) < exp(top.score)) t else null
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

    /**
     * iOS BoostPreferedFormForSortkey (#28): when the top word has a twin spelled with the same letters
     * but for an apostrophe ("abbey's" / "abbeys", "Johns" / "John's"), the usual spelling of the two
     * goes first. iOS marks it in its lexicon; ours comes from word counts ([PreferredForms]).
     */
    private fun boostPreferredForm(cased: List<Pair<Candidate, String>>): List<Pair<Candidate, String>> {
        val forms = preferredForms ?: return cased
        if (cased.size < 2) return cased
        val (topCandidate, _) = cased[0]
        if (topCandidate.isTypedString) return cased
        val top = topCandidate.word
        // both spellings must be words: the typed letters themselves ("chicagos") are no twin
        val j = cased.indexOfFirst { (c, _) -> c.word != top && !c.isTypedString && lexicon.contains(c.word) && forms.twins(top, c.word) }
        if (j < 0) return cased
        val preferred = forms.preferred(top, cased[j].first.word) ?: return cased
        return if (preferred == top) cased else listOf(cased[j]) + cased.filterIndexed { i, _ -> i != j }
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
internal fun typedStringVeto(candidates: List<Candidate>): Boolean {
    val typed = candidates.firstOrNull { it.isTypedString } ?: return false
    val top = candidates.maxByOrNull { it.score } ?: return false
    return top !== typed && typed.languageModel >= top.languageModel
}
