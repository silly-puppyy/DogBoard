package io.github.sillypuppyy.dogboard.engine

/**
 * Letters typed right after a number ("1st", "5pm"). iOS keeps such tokens in its lexicon; ours
 * has no digits, so a word after a digit is otherwise left alone (ImeController.followsDot). This
 * fixes one slipped key in a suffix that fits the number: "1sr" → "1st", "5om" → "5pm". DogBoard's
 * own rule (not an iOS rule): every letter must be the right key or a direct neighbour, and
 * anything else stays as typed.
 */
object DigitWords {
    private val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    /**
     * The suffix [letters] should be after [number] (its digits), or null to keep what was typed. The suffixes are
     * the language's ([rules]: ordinal_suffixes, hour_suffixes).
     */
    fun fix(number: String, letters: String, rules: LanguageRules = LanguageRules.ENGLISH): String? {
        if (number.isEmpty() || !number.all { it.isDigit() } || letters.isEmpty() || letters.length > 2) return null
        val lower = letters.lowercase()
        val targets = suffixes(number, rules)
        if (lower in targets) return null
        val match = targets.filter { it.length == lower.length && it.indices.all { i -> near(lower[i], it[i]) } }
        val fixed = match.singleOrNull() ?: return null
        // typed in capitals ("5PM"), the fix is too
        return if (letters.all { it.isUpperCase() }) fixed.uppercase() else fixed
    }

    /** The suffixes that fit [number]: its ordinal ending, and the hour suffixes (am/pm) for an hour 1-12. */
    fun suffixes(number: String, rules: LanguageRules = LanguageRules.ENGLISH): List<String> {
        val hour = number.length <= 2 && number.toInt() in 1..12
        return listOfNotNull(rules.ordinalSuffixes.of(number)) + (if (hour) rules.hourSuffixes else emptyList())
    }

    /**
     * The same key, or one touching it: key centres within 1.25 key widths of each other, with
     * the rows' offsets of the letter layout (row 2 half a key in, row 3 after the shift key).
     */
    private fun near(a: Char, b: Char): Boolean {
        if (a == b) return true
        val (ra, ca) = position(a) ?: return false
        val (rb, cb) = position(b) ?: return false
        val dx = (ca + ROW_OFFSET[ra]) - (cb + ROW_OFFSET[rb])
        val dy = (ra - rb).toDouble()
        return dx * dx + dy * dy <= 1.6 * 1.6 && kotlin.math.abs(dx) <= 1.25
    }

    private val ROW_OFFSET = doubleArrayOf(0.0, 0.5, 1.5)

    private fun position(c: Char): Pair<Int, Int>? {
        for ((r, row) in ROWS.withIndex()) row.indexOf(c).takeIf { it >= 0 }?.let { return r to it }
        return null
    }
}
