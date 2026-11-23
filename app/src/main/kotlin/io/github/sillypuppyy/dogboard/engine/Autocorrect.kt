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
     * A word typed in lowercase that the lexicon has only capitalised (a name: "hom" for "Hom") counts as the
     * typed word, kept and not read as a skipped last letter, only when that name is among the [nameAsTypedRank]
     * most frequent words ("john", "mr" are; user 2026-11-19: "going hom" was kept, rare web-text names block
     * corrections). Int.MAX_VALUE: every name counts.
     */
    var nameAsTypedRank: Int = Int.MAX_VALUE

    /**
     * A word one letter short of the correction may be read as a skipped last key even when the typed letters are
     * themselves a word, if the context already ruled that word out (it lost to the correction by the keep-the-typed
     * -word margin, filter 20 above) rather than only when the lexicon lacks it (user 2026-11-19: "it's the LLM's job
     * to give context-aware probabilities"; "going hom" kept because the lexicon has the name "Hom").
     */
    var contextEndOmission: Boolean = true

    /**
     * Rule 3's bar: -infinity = off (2026-11-20). Our context support (n-gram or neural ln P in context minus the
     * words' unigram ln P) is not iOS's measure (its denominator is the dictionary's own word probability, scale
     * not decoded): common words are rarely likelier in context than overall, so good splits fell under ln 0.1
     * ("later and waited" -4.98, "of the" -3.14) while a garbage one sat in between ("bomb flush" -4.44). Measured:
     * missed-space set with neural scores fixed 87.1 -> 90.2 %, wrong 0.4 both; the main typo set unchanged with
     * and without neural scores. Back to MIN_CONTEXT_SUPPORT once the iOS measure is decoded.
     */
    var minContextSupport: Double = Double.NEGATIVE_INFINITY

    /** The typed-word margin also for a typed non-word (the old way; benchmarks: DOGBOARD_NONWORD_MARGIN=1). */
    var nonWordMargin: Boolean = false

    /**
     * The lead an accent twin of a typed WORD needs ("que" -> "qué", "paso" -> "pasó"; both are words), as a ratio;
     * null = the level's [Level.leadOverTyped]. iOS's filter 20 is skipped for same-sort-key candidates so "dont" can
     * become "don't"; for a typed word that differs only by accents that let any twin with a hair more context win
     * (Spanish controls: que -> qué, acabo -> acabó, 2026-11-22). Apostrophes and hyphens keep iOS's rule.
     */
    var accentTwinLead: Double? = null

    /** Marks are typed (LanguageRules.typedMarks): a typed word is never changed into its accent twin. */
    var keepAccentTwins: Boolean = false

    /**
     * A compounding language (Finnish, German: rules.json "compounds"): a typed word that is exactly two or more
     * dictionary words joined ("vakoiluosasto") is a compound, kept as typed, not split ("vakoilu osasto"). Finnish
     * real typing, 2026-11-22: most correct words broken were such splits.
     */
    var keepCompounds: Boolean = false

    /**
     * The pair support an exact split's words need: the second at least 2x likelier after the first than anywhere.
     * At 1x (any pair that goes together) unknown names split ('Renderscript' -> 'render script', 'LiteStep' ->
     * 'lite step'): leave-alone sets 2026-11-20, unknown brands changed 13.2 % (no splits) -> 24.2 %, inner-capital
     * names 10.0 -> 19.4 %; at 2x 13.8 / 10.4 % and missed-space typos 91.5 -> 93.9 % fixed (3x: the same for names,
     * 93.7 %, and "of the" at 2.8x no longer split; unseen pairs sit at 0.4x).
     */
    var minPairSupport: Double = ln(2.0)

    /** [exactSplitFirst] on (benchmarks: DOGBOARD_EXACT_SPLITS=0 turns it off). */
    var exactSplitsEnabled: Boolean = true

    /**
     * A short word typed in capitals throughout (2-5 letters: CKM, GOST, NAFO) is left as typed, as Gboard does
     * (measured 2026-11-20): autocapitalisation only ever capitals the first letter, so these are deliberate
     * abbreviations. Longer ones and [knownMisspellings] ("TEH", "GOOGEL" in caps lock) are still corrected.
     * Typed as written: unknown brands changed 15.8 -> 10.4 %, real typos unchanged (benchmarks:
     * DOGBOARD_ALLCAPS_SHIELD=0 turns it off).
     */
    var allCapsShield: Boolean = true

    /**
     * A one-word correction to a word rarer than this (ln P, the lexicon's) is not made: it stays a suggestion.
     * Our 307k lexicon offers rare words a smaller one lacks ("moknday" -> "noonday", "ghandi" -> "chandi",
     * "ekinf" -> "eking"; Gboard's 2.5 % wrong vs our 5.6 %, 2026-11-21). -16 (about the 100,000th word): main set
     * fixed 46.8 -> 46.7 %, wrong 5.6 -> 5.4 %; held-out ITE dev set fixed 56.0 -> 55.9 %, wrong 6.7 -> 6.2 % (-15 and
     * -14 trade away more fixes). Negative infinity = off (benchmarks: DOGBOARD_MIN_TARGET=x).
     */
    var minTargetLogProb: Double = -16.0

    /** Known misspellings, lowercase (en.misspellings.txt): never shielded as an abbreviation. */
    var knownMisspellings: Set<String> = emptySet()

    /**
     * ln P(second | first) - ln P(second) from the word-pair model (TypingEngine sets it): how much likelier the
     * second word is after the first than anywhere; null = unknown. Without it [exactSplitFirst] is off.
     */
    var pairSupport: ((String, String) -> Double?)? = null

    /**
     * The other languages typed together with this one (multilingual typing, notes/design/multilingual.md): a word
     * of any of them is a word as typed, and its probability counts. Each list is asked on its own (node ids never mix).
     */
    var otherLexicons: List<Lexicon> = emptyList()

    /** [typed] is a word as typed: a casing of it is stored, or it is a common enough name typed in lowercase. */
    /** [typed] is a spelling of its own in a word list (not only a folding of another spelling onto its entry). */
    private fun isListedForm(typed: String): Boolean = (listOf(lexicon) + otherLexicons).any { l ->
        l.find(typed).let { n -> n != Lexicon.NONE && l.isWord(n) && l.forms(n).any { it.first.equals(typed, ignoreCase = true) } }
    }

    private fun isWordAsTyped(typed: String): Boolean = isWordAsTypedIn(lexicon, typed) || otherLexicons.any { isWordAsTypedIn(it, typed) }

    private fun isWordAsTypedIn(lexicon: Lexicon, typed: String): Boolean {
        val node = lexicon.find(typed)
        if (node == Lexicon.NONE || !lexicon.isWord(node)) return false
        if (typed != typed.lowercase() || lexicon.forms(node).any { it.first == typed }) return true
        return nameAsTypedRank >= lexicon.size || lexicon.logProb(node) >= lexicon.logProb(lexicon.nodeOfWord(nameAsTypedRank))
    }

    /** ln P of [word] in whichever of the entry's languages lists it most often; null in none. */
    private fun anyLogProb(word: String): Double? =
        (listOf(lexicon) + otherLexicons).mapNotNull { it.logProb(word) }.maxOrNull()

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
        val MIN_CONTEXT_SUPPORT = ln(0.1)

        /** [Autocorrect.pairSupport] from the word-pair model: ln P(second | first) - ln P(second). */
        fun pairSupport(lexicon: Lexicon, bigrams: Bigrams): (String, String) -> Double? = { first, second ->
            val w = second.lowercase()
            lexicon.logProb(w)?.let { u -> bigrams.logProb(bigrams.contextId(first.lowercase()), lexicon.idOf(w), u) - u }
        }

        /**
         * iOS FilterNondominantCorrectionOfMoreWords (SortKeyMatchBoostRatio 1.39): a candidate
         * that keeps more of the typed words wins when within this ratio of the top. For one typed
         * word: what was typed stays unless the correction is 1.39x more likely.
         */
        const val SORT_KEY_MATCH_BOOST_RATIO = 1.39

        /** iOS BoostMinimumVariation: a plainer spelling this close to the top goes first. */
        private val MIN_VARIATION_RATIO = ln(1.1)

        /**
         * Characters that make a completion a "special edit" (spec 4.1 P: word-medial punctuation ' & ’ and
         * the Hebrew geresh and gershayim; diacritics are tested in [isSpecialEdit]).
         */
        private val SPECIAL_EDIT_CHARS = setOf('\'', '’', '&', '\u05F3', '\u05F4')

        /** Spec 4.1 P: a precomposed (é) or separated (e + U+0301) diacritic, or a [SPECIAL_EDIT_CHARS] (audit 2026-11-20). */
        internal fun isSpecialEdit(c: Char): Boolean = c in SPECIAL_EDIT_CHARS ||
            Character.getType(c) == Character.NON_SPACING_MARK.toInt() ||
            java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD).length > 1

        private fun oneLetterWords(s: String) = s.split(' ').count { it.length == 1 }

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
            .mapNotNull { c -> case(c.word)?.let { c to it } }
            .let(::boostPlainer)
            .let { exactSplitFirst(typed, it) }
        val (top, t) = cased.firstOrNull() ?: return null
        // (a capital needed: Arabic and Georgian letters have no case, so "no lowercase" held for every short word
        // and none was corrected, 2026-11-22)
        if (allCapsShield && typed.count { it.isLetter() } in 2..5 && typed.none { it.isLowerCase() } && typed.any { it.isUpperCase() } && typed.lowercase() !in knownMisspellings) return null
        // Rule 3 (spec 4.1): a multi-word top without linguistic support rejects the correction; iOS judges
        // only the top, so the next candidate down doesn't take its place (audit 2026-11-20)
        if (top.words > 1 && top.contextSupport < minContextSupport) return null
        // Rule 1: the typed word wins.
        if (t == typed) return null
        if (keepCompounds && top.words > 1 && !top.isCompletion && top.word.replace(" ", "").equals(typed, ignoreCase = true)) return null
        // (only a change of letters: a casing or apostrophe fix of what was typed stays, "gboard" -> "Gboard")
        if (top.words == 1 && minTargetLogProb > Double.NEGATIVE_INFINITY && sortKey(t) != sortKey(typed) &&
            (anyLogProb(top.word) ?: anyLogProb(top.word.lowercase()) ?: Double.NEGATIVE_INFINITY) < minTargetLogProb) return null
        // Rule 2 (spec 4.1): a correction longer than the input that has fewer one-letter words than it is
        // rejected ("a" -> "and", and as well "o" -> "in"; audit 2026-11-20: ours needed T to start with I)
        if (t.length > typed.length && oneLetterWords(t) < oneLetterWords(typed)) return null
        // iOS filter 20 (keep more typed words when nearly as likely), and Gentle's larger margin;
        // a correction with the typed letters ("dont" -> "don't") keeps the typed word already
        if (sortKey(t) != sortKey(typed) && !isExactSplit(typed, top)) {
            val asTyped = ranked.firstOrNull { it.isTypedString || (it.word.equals(typed, ignoreCase = true) && isWordAsTyped(typed)) }
            // the typed word stays, as the dictionary spells it ("mr" -> "Mr", as when it is the top). Only a typed
            // WORD gets this margin: iOS never ranks a typed non-word (spec 2.6), so its filter 20 can't protect one;
            // ours did, and the neural model's vote for "Teh" (-3.96 vs "the" -3.76, 2026-11-20 emulator) or the
            // letter model's for "thankyou" kept real typos within 1.39x (nonWordMargin = the old way, benchmarks)
            if (asTyped != null && (!asTyped.isTypedString || nonWordMargin) && top.score - asTyped.score < ln(level.leadOverTyped))
                return keptAsTyped(typed, asTyped, case)
        }
        // an accent twin of a typed word needs the same lead as any other change of a typed word (see accentTwinLead)
        // only a bare spelling the list has as a form of its own ("ngay"): one that merely folds onto a marked word's
        // entry ("επειτα" onto έπειτα) is still restored
        if (keepAccentTwins && top.words == 1 && t != typed && isAccentTwin(t, typed) && isListedForm(typed)) return null
        if (top.words == 1 && t != typed && isAccentTwin(t, typed) && isWordAsTyped(typed)) {
            val asTyped = ranked.firstOrNull { it.words == 1 && it.word.equals(typed, ignoreCase = true) }
            if (asTyped != null && top.score - asTyped.score < ln(accentTwinLead ?: level.leadOverTyped)) return keptAsTyped(typed, asTyped, case)
        }
        // Rule 4: a correction that is not a completion of what was typed is taken as is.
        if (!(t.length >= typed.length && t.startsWith(typed))) return t
        // Rule 5: a completion needs a long enough stem (or a contraction-like ending, or the same
        // choice as one keystroke earlier) and must clearly beat the runner-up.
        val gain = t.length - typed.length
        val specialEdit = t.substring(typed.length).any(::isSpecialEdit)
        val minStem = when {
            gain <= 1 -> level.minStemOneLetter
            gain == 2 -> level.minStemTwoLetters
            else -> level.minStemMoreLetters
        }
        // a typed non-word one letter short of a word the decoder read as a skipped last key
        val endOmission = gain == 1 && !top.isCompletion && !top.isTypedString && level.minStemEndOmission != null &&
            typed.length >= level.minStemEndOmission && (!isWordAsTyped(typed) || contextEndOmission)
        val stemOk = specialEdit || endOmission || (previousCorrection != null && t.equals(previousCorrection, ignoreCase = true)) ||
            typed.length >= minStem
        if (!stemOk) return null
        // the runner-up: never the typed non-word (iOS doesn't rank one, spec 2.6, as for the margin above): it held
        // back "closin" -> "closing" and "carryin" -> "carrying" (Gboard fixes them; omitted-letter bench 2026-11-22)
        val (second, s) = cased.drop(1).firstOrNull { nonWordMargin || !it.first.isTypedString } ?: return t
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
    /**
     * [c] is [typed]'s letters with spaces put in, nothing else changed ("thankyou" -> "thank you"), into words that
     * go together: each next word at least [minPairSupport] likelier after the one before than anywhere. That keeps
     * a brand or unknown word made of unrelated words ("Gumroad" not "Gum road", "befour" not "be four", "Nowember"
     * not "now ember": held-out bench 2026-11-20) and splits run-together phrases (thank you, of the, what I, will it).
     */
    private fun isExactSplit(typed: String, c: Candidate): Boolean {
        val support = pairSupport ?: return false
        if (!exactSplitsEnabled || c.words < 2 || c.isCompletion || !c.word.replace(" ", "").equals(typed, ignoreCase = true)) return false
        return c.word.split(' ').zipWithNext().all { (a, b) -> (support(a, b) ?: return false) >= minPairSupport }
    }

    /**
     * A typed NON-word that is exactly two or more dictionary words with the spaces missing: the best real
     * candidate, when it is that split, goes ahead of the typed string (and skips the typed-word margin below).
     * The split explains every letter; the letter model rates a run-together of common words ("thankyou",
     * "ofthe") as a plausible unknown word and kept it ahead by a hair (user 2026-11-20: "the keyboard can
     * correctly split words apart but just doesn't do it"). iOS never ranks a typed non-word at all (spec 2.6); this
     * takes that rule only where the alternative is an exact split. A learned word is a dictionary word: kept.
     */
    private fun exactSplitFirst(typed: String, cased: List<Pair<Candidate, String>>): List<Pair<Candidate, String>> {
        if (!exactSplitsEnabled || cased.size < 2 || !cased[0].first.isTypedString) return cased
        if (cased.any { (c, _) -> !c.isTypedString && c.words == 1 && c.word.equals(typed, ignoreCase = true) }) return cased
        val best = cased.firstOrNull { (c, _) -> !c.isTypedString } ?: return cased
        if (!isExactSplit(typed, best.first)) return cased
        return listOf(best) + cased.filter { it !== best }
    }

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

/** [a] and [b] differ only by accents (and case): "qué" / "que", not "we're" / "were". */
internal fun isAccentTwin(a: String, b: String): Boolean {
    if (a.length != b.length || a.equals(b, ignoreCase = true)) return false
    for (i in a.indices) {
        val x = a[i].lowercaseChar(); val y = b[i].lowercaseChar()
        if (x != y && Lexicon.fold(x) != Lexicon.fold(y)) return false
    }
    return true
}

/** The letters only, without case or accents ("We're" and "were" share one): iOS's sort key. */
internal fun sortKey(word: String): String {
    val distinct = Lexicon.distinctLetters
    if (distinct.isEmpty() || word.none { it.lowercaseChar() in distinct })
        return Normalizer.normalize(word, Normalizer.Form.NFD).filter { it.isLetterOrDigit() }.lowercase()
    // a letter with its own key (ñ, ä) is a letter of its own, not an accent: "año" and "ano" differ
    val sb = StringBuilder(word.length)
    for (c in word) {
        val lc = c.lowercaseChar()
        if (lc in distinct) sb.append(lc)
        else Normalizer.normalize(lc.toString(), Normalizer.Form.NFD).forEach { if (it.isLetterOrDigit()) sb.append(it) }
    }
    return sb.toString()
}

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
/**
 * The neural model also scores the typed non-word: it keeps names and British spellings typed in a sentence
 * (unknown brands changed 13.8 -> 12.4 %, British 42 -> 30 %), but also long typos it reads as plausible pieces
 * ("havig", "goesd", "differnce": fixed without it, kept with it; 2026-11-21). Measured off: typos about even (main
 * fixed 48.8 -> 48.5 %, wrong 4.8 -> 4.4 %; Gboard set 49.1 -> 49.3 %), unknown brands in context 10.2 -> 7.8 %
 * changed, but British spellings 26.4 -> 40.2 % changed: kept on. Benchmarks: DOGBOARD_NEURAL_TYPED=0.
 */
@Volatile internal var neuralScoresTyped: Boolean = true

internal fun withNeural(candidates: List<Candidate>, neural: (String) -> Double?, weight: Double, power: Double): List<Candidate> =
    candidates.map { c ->
        (if (c.isTypedString && !neuralScoresTyped) null else neural(c.word))?.let { n ->
            val l = (1 - weight) * c.languageModel + weight * n
            // Rule 3's context probability for a split from the neural model (iOS: Word+0x38, written by the
            // neural refinement as the LM's static conditional probability, ios-decomp notes; the n-gram backs
            // off on an unseen pair, so "nonbnglish" -> "bomb flush" passed; the
            // neural model gives it -5.0 against ln 0.1 = -2.3, and "later and" -> "layer and" -6.4; 2026-11-20).
            // The words' static log probability is what the n-gram support was measured against:
            // languageModel - contextSupport.
            val support = if (c.words > 1) n - (c.languageModel - c.contextSupport) else c.contextSupport
            c.copy(score = c.geometry + power * l, languageModel = l, contextSupport = support)
        } ?: c
    }.sortedByDescending { it.score }

/**
 * iOS exact-typed-string LM veto (spec 2.6; on for English, ios-decomp notes VERIFIED): when the literal typed
 * string is at least as likely in context (LM term only) as the top candidate, nothing is corrected. [typedText]
 * also finds a valid typed word among the decoder's readings (audit 2026-11-20: ours vetoed for non-words
 * only), as typed: a lowercase "i" is not the lexicon's "I", a sentence-start "The" is "the".
 */
internal fun typedStringVeto(candidates: List<Candidate>, typedText: String? = null): Boolean {
    val typed = candidates.firstOrNull { it.isTypedString } ?: typedText?.let { t ->
        candidates.firstOrNull { c ->
            !c.isCompletion && c.words == 1 && c.word.equals(t, ignoreCase = true) && !(t == t.lowercase() && c.word != c.word.lowercase())
        }
    } ?: return false
    val top = candidates.maxByOrNull { it.score } ?: return false
    return top !== typed && typed.languageModel >= top.languageModel
}
