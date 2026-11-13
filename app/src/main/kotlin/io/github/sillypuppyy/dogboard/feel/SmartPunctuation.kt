package io.github.sillypuppyy.dogboard.feel

/**
 * iOS smart punctuation notes/design/settings-model.md §6): what a
 * typed ', ", - or . becomes, given the text around the cursor. Two switches as on iOS: smart
 * quotes (curly quotes and apostrophes) and smart dashes (dashes and the ellipsis).
 *
 * Quotes: an opening quote at the start, after a space or new line, after ( [ { < / @ - = or an
 * opening quote, or right after an opening quote of the same kind; a single quote between two
 * letters or digits is an apostrophe; otherwise a closing quote.
 * Dashes, on "-": one hyphen before -> it becomes an en dash when the character before it is a
 * digit ("10--20" -> "10–20"), else an em dash; two or more unconverted hyphens -> two become an
 * em dash; an en dash before -> an em dash; an em dash before -> nothing.
 * Ellipsis, on ".": ".." before -> the three become "…".
 */
object SmartPunctuation {
    /** Delete [deleteBefore] characters before the cursor, then type [insert]. */
    data class Output(val deleteBefore: Int, val insert: String)

    /** English quote characters (iOS TISmartPunctuationOptions for en). */
    data class Quotes(val leftDouble: Char, val rightDouble: Char, val leftSingle: Char, val rightSingle: Char, val apostrophe: Char)

    val ENGLISH = Quotes('“', '”', '‘', '’', '’')

    /** What typing [input] does with [before] / [after] the cursor, or null to type it as is. */
    fun forInput(
        input: String,
        before: CharSequence,
        after: CharSequence,
        quotes: Boolean,
        dashes: Boolean,
        q: Quotes = ENGLISH,
    ): Output? {
        if (quotes && (input == "\"" || input == "'")) return Output(0, quote(input[0], before, after, q).toString())
        if (!dashes) return null
        if (input == "-") return dash(before)
        if (input == "." && before.endsWith("..")) return Output(2, "…")
        return null
    }

    private const val OPENERS = "([{</@-="

    private fun quote(input: Char, before: CharSequence, after: CharSequence, q: Quotes): Char {
        val prev = before.lastOrNull()
        val next = after.firstOrNull()
        val left = if (input == '"') q.leftDouble else q.leftSingle
        val open = prev == null || prev.isWhitespace() || prev in OPENERS || prev == q.leftDouble || prev == q.leftSingle ||
            before.endsWith(left)
        return when {
            input == '"' -> if (open) q.leftDouble else q.rightDouble
            open -> q.leftSingle
            prev != null && next != null && prev.isLetterOrDigit() && next.isLetterOrDigit() -> q.apostrophe
            else -> q.rightSingle
        }
    }

    private fun dash(before: CharSequence): Output? {
        var n = 0
        while (n < before.length && before[before.length - 1 - n] == '-') n++
        return when {
            n == 1 -> Output(1, if (before.length >= 2 && before[before.length - 2].isDigit()) "–" else "—")
            n >= 2 -> Output(2, "—")
            before.endsWith("–") -> Output(1, "—")
            else -> null // an em dash before, or nothing to join
        }
    }
}
