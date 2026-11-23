package io.github.sillypuppyy.dogboard.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A script's typing rules as Gboard's dynamic layouts apply them (notes/design/data/layouts/README.md "Gboard's
 * conditional keys"): after each key, the first rule whose pattern matches the end of the text with the key writes
 * its replacement instead (Devanagari: a consonant cluster + आ -> the cluster + ा; a sign after a consonant from the
 * long-press -> the vowel itself; anusvara, nukta, virama). One set a script, derived from Gboard's rule files
 * (tools/layouts/dynamic_rules.py, assets dogboard/dynamic_rules/<script>.json). [compose] draws a word's keys,
 * [decompose] gives a drawn word's keys back (each sign as the key that writes it after a consonant).
 */
class RuleComposer private constructor(private val rules: List<Rule>) {
    @Serializable
    private class File(val rules: List<Spec>)

    @Serializable
    private class Spec(val pattern: String, val text: String, @SerialName("label") val label: String = "")

    /** [last]: the key the pattern ends with when it is a plain character (rules for other keys are skipped). */
    private class Rule(val regex: Regex, val text: String, val last: Char?)

    /** What each sign's key is: the key whose rule writes "$1" + the sign after a consonant (ा <- आ, Tamil ் <- அ). */
    private val keyOfSign: Map<Char, Char> = buildMap {
        for (r in rules) {
            val sign = r.text.removePrefix("$1").singleOrNull() ?: continue
            if (r.text.startsWith("$1") && r.last != null && sign != r.last && isSign(sign)) putIfAbsent(sign, r.last)
        }
    }

    fun compose(keys: String): String {
        val out = StringBuilder()
        for (k in keys) append(out, k)
        return out.toString().replace(INHERENT, "")
    }

    /** [out] with [key] typed: the first matching rule's replacement, or the key. */
    private fun append(out: StringBuilder, key: Char) {
        val from = maxOf(0, out.length - WINDOW)
        val text = out.substring(from) + key
        for (r in rules) {
            if (r.last != null && r.last != key) continue
            val m = r.regex.find(text) ?: continue
            val replaced = m.range.first
            out.setLength(from + replaced)
            // $n back-references, as the converter wrote them
            out.append(r.text.replace(BACKREF) { g -> m.groupValues.getOrNull(g.groupValues[1].toInt()).orEmpty() })
            return
        }
        out.append(key)
    }

    private fun isSign(c: Char) = Character.getType(c).let {
        it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
    }

    fun decompose(text: String): String = buildString { for (c in text) append(keyOfSign[c] ?: c) }

    companion object {
        /** Enough text before the cursor for the longest cluster a rule looks at. */
        private const val WINDOW = 16
        private val BACKREF = Regex("""\$(\d)""")

        /**
         * Gboard's mark for a consonant kept with its inherent vowel (a + अ key after it: the next vowel key then
         * writes the vowel, not a sign). Kept while composing, never written to the field.
         */
        private const val INHERENT = "\u200c\u200c\u200c"
        private val json = Json { ignoreUnknownKeys = true }

        /** A script's rule file: the app's assets (set at start-up), the source tree in tests. */
        @Volatile var source: (String) -> String? = { script ->
            java.io.File("src/main/assets/dogboard/dynamic_rules/$script.json").takeIf { it.exists() }?.readText()
        }
        private val loaded = HashMap<String, RuleComposer?>()

        /** [script]'s rules, read once; null when there is no such file. */
        fun forScript(script: String): RuleComposer? = synchronized(loaded) {
            loaded.getOrPut(script) { runCatching { source(script)?.let(::parse) }.getOrNull() }
        }

        /** The rules [language]'s layout types with (Indic), or null. */
        fun forLanguage(language: String): RuleComposer? = SCRIPT_OF[language.substringBefore('-')]?.let(::forScript)

        /** Layouts whose rules differ from their language's: Gboard's full Hindi (dynamic) beside the compact one. */
        private val SCRIPT_OF_LAYOUT = mapOf("hindi-hi" to "devanagari")

        /** The rules the layout [layoutId] types with, else [language]'s. */
        fun forLayout(layoutId: String, language: String): RuleComposer? =
            SCRIPT_OF_LAYOUT[layoutId]?.let(::forScript) ?: forLanguage(language)

        fun parse(text: String): RuleComposer = RuleComposer(json.decodeFromString<File>(text).rules.map { s ->
            val last = s.pattern.lastOrNull()?.takeIf { it !in """)]}?*+.|\$""" && s.pattern.getOrNull(s.pattern.length - 2) != '\\' }
            Rule(Regex("(?:${s.pattern})$"), s.text.replace("\\$", "\u0000").replace("\\\\", "\\").replace("\u0000", "$"), last)
        })

        /**
         * The rule set each language's layout uses live (Gboard's dynamic layout for that language). Not Hindi: our
         * Hindi is Gboard's compact layout, which has no dynamic layout; Gboard types क आ there as कआ and its
         * dictionary offers का (emulator, 2026-11-23), as the Hindi pack's word list along the keys does.
         */
        val SCRIPT_OF = mapOf(
            "sa" to "sanskrit", "mr" to "devanagari_eyelash_reph", "ne" to "devanagari_eyelash_reph",
            "bn" to "bengali", "as" to "bengali", "ta" to "tamil", "te" to "telugu", "kn" to "kannada", "ml" to "malayalam",
            "gu" to "gujarati", "pa" to "punjabi", "or" to "oriya",
        )
    }
}
