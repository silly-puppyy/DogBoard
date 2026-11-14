package io.github.sillypuppyy.dogboard.engine

import java.io.BufferedReader

/**
 * The usual spelling of a plain plural and its possessive twin ("abbeys" / "abbey's"): the plain
 * form, except for the words listed as possessive-preferred (names and mostly-singular nouns: "John's",
 * "world's") and the near-ties, which get no preference. Built from word counts by
 * tools/data/build_preferred_forms.py (en.preferred.txt).
 */
class PreferredForms(private val possessive: Set<String>, private val ties: Set<String>) {
    /** True if [a] and [b] are "Xs" and "X's" (in either order, any case), X's not a contraction. */
    fun twins(a: String, b: String): Boolean = baseOf(a, b) != null

    /** Whichever of the twins [a] / [b] is the usual spelling, or null if neither clearly is. */
    fun preferred(a: String, b: String): String? {
        val base = baseOf(a, b) ?: return null
        if (base in ties) return null
        val possessiveForm = if (isPossessive(a)) a else b
        return if (base in possessive) possessiveForm else (if (possessiveForm == a) b else a)
    }

    private fun isPossessive(w: String) = w.endsWith("'s") || w.endsWith("’s")

    private fun baseOf(a: String, b: String): String? {
        val (poss, plain) = when {
            isPossessive(a) && !isPossessive(b) -> a to b
            isPossessive(b) && !isPossessive(a) -> b to a
            else -> return null
        }
        if (plain.any { it == '\'' || it == '’' }) return null
        val base = poss.dropLast(2).lowercase()
        if (base in CONTRACTIONS || !plain.equals(poss.dropLast(2) + "s", ignoreCase = true)) return null
        return base
    }

    companion object {
        private val CONTRACTIONS = setOf("it", "he", "she", "that", "what", "there", "here", "who", "let", "where", "how", "when", "why")

        fun parse(reader: BufferedReader): PreferredForms {
            val possessive = HashSet<String>()
            val ties = HashSet<String>()
            reader.forEachLine { line ->
                val l = line.trim()
                when {
                    l.isEmpty() || l.startsWith("#") -> {}
                    l.startsWith("=") -> ties.add(l.substring(1))
                    else -> possessive.add(l)
                }
            }
            return PreferredForms(possessive, ties)
        }
    }
}
