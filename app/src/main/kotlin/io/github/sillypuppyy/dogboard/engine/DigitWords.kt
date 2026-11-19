package io.github.sillypuppyy.dogboard.engine

/**
 * Letters typed right after a number ("1st", "5pm"). iOS keeps such tokens in its lexicon; ours
 * has no digits, so a word after a digit is otherwise left alone (ImeController.followsDot). This
 * fixes one slipped key in a suffix that fits the number: "1sr" → "1st", "5om" → "5pm". DogBoard's
 * own rule (not an iOS rule): every letter must be the right key or a direct neighbour, and
 * anything else stays as typed.
 */
object DigitWords {
    /**
     * The suffix [letters] should be after [number] (its digits), or null to keep what was typed. The suffixes are
     * the language's ([rules]: ordinal_suffixes, hour_suffixes).
     */
    fun fix(number: String, letters: String, rules: LanguageRules = LanguageRules.ENGLISH): String? {
        if (number.isEmpty() || !number.all { it.isDigit() } || letters.isEmpty() || letters.length > 2) return null
        val lower = letters.lowercase()
        val targets = suffixes(number, rules)
        if (lower in targets) return null
        val match = targets.filter { it.length == lower.length && it.indices.all { i -> rules.letterRows.near(lower[i], it[i]) } }
        val fixed = match.singleOrNull() ?: return null
        // typed in capitals ("5PM"), the fix is too
        return if (letters.all { it.isUpperCase() }) fixed.uppercase() else fixed
    }

    /** The suffixes that fit [number]: its ordinal ending, and the hour suffixes (am/pm) for an hour 1-12. */
    fun suffixes(number: String, rules: LanguageRules = LanguageRules.ENGLISH): List<String> {
        val hour = number.length <= 2 && number.toInt() in 1..12
        return listOfNotNull(rules.ordinalSuffixes.of(number)) + (if (hour) rules.hourSuffixes else emptyList())
    }
}
