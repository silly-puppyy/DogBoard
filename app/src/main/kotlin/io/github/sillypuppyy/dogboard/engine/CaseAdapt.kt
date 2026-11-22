package io.github.sillypuppyy.dogboard.engine

/**
 * How a suggestion or correction takes the capitals the user typed, following iOS
 * (settings-model.md §3).
 *
 * The typed word is classified from its letters and, per letter, whether shift was pressed by the
 * user ([SHIFTED]) or set by the keyboard ([AUTOSHIFTED]); the class decides what happens to a
 * candidate:
 * - [Type.NONE] (nothing shifted): the candidate keeps the dictionary's casing ("paris" → "Paris").
 * - [Type.AUTOSHIFT] (the keyboard capitalised the first letter): first letter capitalised, unless
 *   the word must not be ("iPhone", "eBay").
 * - [Type.AUTOSHIFT_ALL] (field asks for all capitals): all capitals.
 * - [Type.EXPLICIT_SHIFT] (user shifted the first letter): capitalised when the candidate starts
 *   with the typed letter and isn't more than one letter shorter, and is capitalised in the
 *   dictionary or exists there capitalised; otherwise it has to pass the mixed-case test, then
 *   gets its first letter capitalised.
 * - [Type.ALL_CAPS] ("GOOGLE", or one letter in caps lock): all capitals.
 * - [Type.MIXED] ("gOOgle"): accepted only when at most one letter was shifted by the user, none of them
 *   after the first, and the input is all letters, or when the candidate keeps the typed letters as a
 *   case-sensitive prefix; first letter capitalised if it was shifted.
 * Returns null when the candidate is not acceptable for this input (it is filtered out).
 */
object CaseAdapt {
    /**
     * The language's casing rules (EngineLoader sets the pack's locale): Turkish and Azerbaijani capital i is İ and
     * capital ı is I; the root locale gave "Istanbul" for "istanbul" at a sentence start (2026-11-23).
     */
    @Volatile var locale: java.util.Locale = java.util.Locale.ROOT

    /** Per-letter input flags. */
    const val SHIFTED = 1
    const val AUTOSHIFTED = 2

    /** The shift state when the word started, together with the field's capitalisation. */
    enum class Context { NONE, SHIFT, AUTOSHIFT, AUTOSHIFT_WORDS, AUTOSHIFT_ALL, CAPS_LOCK }

    enum class Type { NONE, AUTOSHIFT, AUTOSHIFT_ALL, EXPLICIT_SHIFT, ALL_CAPS, MIXED }

    /** What the dictionary knows about a word's capitals. */
    interface Casing {
        /** The dictionary has this word (ignoring case) in a form starting with a capital. */
        fun hasCapitalisedForm(word: String): Boolean
        /** The word must not get a capital first letter ("iPhone", "eBay"). */
        fun keepsLowercaseStart(word: String): Boolean = word.length > 1 && word[0].isLowerCase() && word.drop(1).any { it.isUpperCase() }
    }

    /** Shift flags guessed from the letters when the real ones are unknown: a capital was shifted by the user. */
    fun inferredFlags(typed: String): List<Int> = typed.map { if (it.isUpperCase()) SHIFTED else 0 }

    /** For callers without a dictionary: only the candidate's own capitals are known. */
    object NoDictionary : Casing {
        override fun hasCapitalisedForm(word: String) = false
    }

    fun classify(input: String, flags: List<Int>, context: Context): Type {
        if (flags.isEmpty()) return Type.NONE
        val hasLower = input.any { it.isLowerCase() }
        val upper = input.count { it.isUpperCase() }
        val shiftedAfterFirst = flags.drop(1).any { it and SHIFTED != 0 }
        if (hasLower && (shiftedAfterFirst || upper > 1)) return Type.MIXED
        if (!hasLower) {
            if (context == Context.AUTOSHIFT_ALL && flags[0] and AUTOSHIFTED != 0) return Type.AUTOSHIFT_ALL
            if (upper > 1) return Type.ALL_CAPS
        }
        if (shiftedAfterFirst && flags.size > 1) return Type.ALL_CAPS
        val first = flags[0]
        if (first and SHIFTED != 0 && context == Context.CAPS_LOCK && flags.size == 1) return Type.ALL_CAPS
        if (first and AUTOSHIFTED != 0) return Type.AUTOSHIFT
        return if (first and SHIFTED != 0) Type.EXPLICIT_SHIFT else Type.NONE
    }

    fun apply(candidate: String, input: String, flags: List<Int>, context: Context, casing: Casing): String? =
        when (classify(input, flags, context)) {
            Type.NONE -> candidate
            Type.AUTOSHIFT -> if (casing.keepsLowercaseStart(candidate)) candidate else capitaliseFirst(candidate)
            Type.AUTOSHIFT_ALL, Type.ALL_CAPS -> candidate.uppercase(locale)
            Type.EXPLICIT_SHIFT -> {
                val capitalised = candidate.firstOrNull()?.isUpperCase() == true || casing.hasCapitalisedForm(candidate)
                if (capitalised && candidate.length >= input.length - 1 && sameLetter(candidate.firstOrNull(), input.firstOrNull())) {
                    capitaliseFirst(candidate)
                } else if (acceptsForMixedCase(candidate, input, flags)) {
                    // iOS falls through to the capitalising step for this type;
                    // the exact path in between was not recovered, so the mixed-case test gates it
                    capitaliseFirst(candidate)
                } else null
            }
            // a capital the user shifted after the first letter was meant ("DiT", "iPhone": on a phone the shift
            // lets go after one letter, so it took a shift tap mid-word): only a candidate that keeps the typed
            // letters as they are goes (iOS's fallback test; user 2026-11-20 "DiT" -> "Dit" -> "Fit"). The first
            // letter takes the capital when the user or the keyboard shifted it (it was dropped when autoshifted)
            Type.MIXED -> if (!acceptsForMixedCase(candidate, input, flags, allowOneCapital = !shiftedInside(flags))) null
                else if (flags[0] and (SHIFTED or AUTOSHIFTED) != 0 && !casing.keepsLowercaseStart(candidate)) capitaliseFirst(candidate)
                else candidate
        }

    /** A letter after the first that the user shifted (not the keyboard). */
    private fun shiftedInside(flags: List<Int>): Boolean = flags.drop(1).any { it and SHIFTED != 0 && it and AUTOSHIFTED == 0 }

    /**
     * Mixed-case acceptance (should_accept_candidate_for_mixed_case_input, decompiled 2026-11-20): with fewer
     * than two user capitals and letters only it accepts when a flag of the input manager (+0x2db, set in
     * syncToKeyboardState from an Objective-C call not yet named) is on; else only a candidate whose capitalised
     * form starts with the input as typed (or a shortcut's sort-key match).
     */
    internal fun acceptsForMixedCase(candidate: String, input: String, flags: List<Int>, allowOneCapital: Boolean = true): Boolean {
        val explicitCapitals = flags.count { it and SHIFTED != 0 && it and AUTOSHIFTED == 0 }
        if (allowOneCapital && explicitCapitals < 2 && input.all { it.isLetter() }) return true
        // no case-insensitive match: iOS compares sort keys only for a text-replacement word (Word+0xe0), so
        // "dit" for "DiT" is not the same word in other capitals (decompile 2026-11-20; it made "DiT" -> "Dit")
        return candidate.length >= input.length && candidate.startsWith(input)
    }

    private fun sameLetter(a: Char?, b: Char?): Boolean =
        a != null && b != null && java.text.Normalizer.normalize(a.toString(), java.text.Normalizer.Form.NFD)[0]
            .equals(java.text.Normalizer.normalize(b.toString(), java.text.Normalizer.Form.NFD)[0], ignoreCase = true)

    /** Capitalises the first letter (the second when the word starts with an apostrophe: "'tis"); keeps other capitals. */
    fun capitaliseFirst(word: String): String {
        val i = word.indexOfFirst { it.isLetter() }
        if (i < 0) return word
        return word.substring(0, i) + word[i].toString().uppercase(locale) + word.substring(i + 1)
    }
}
