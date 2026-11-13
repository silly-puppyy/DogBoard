package io.github.sillypuppyy.dogboard.engine

import java.io.BufferedReader
import java.io.File

/**
 * Emoji suggestions, following iOS:
 * the last few words before the cursor (at most [MAX_WORDS]) are looked up as phrases, longest
 * first ("thumbs up" before "up"), in the keyword index (en.emoji.tsv: CLDR keywords ranked by
 * how much people use each emoji); the matches are ranked by the user's own emoji use
 * ([EmojiUsage]) and then the index order.
 */
class EmojiSuggest(
    private val index: Map<String, List<String>>,
    private val usage: EmojiUsage,
    /** Emoji -> its five skin-tone forms, light to dark (en.emoji-tones.tsv). */
    private val tones: Map<String, List<String>> = emptyMap(),
) {
    /**
     * Emoji for the words at the end of [textBefore], best first; empty if none match. [tone]
     * is the user's default skin tone (U+1F3FB..U+1F3FF, or 0 for none), applied as iOS does.
     */
    fun suggest(textBefore: CharSequence, max: Int = 3, tone: Int = 0): List<String> {
        val words = WORD.findAll(textBefore.toString().lowercase())
            .map { it.value.trim('.', '-', '\'', '’', '&') }.filter { it.isNotEmpty() }.toList().takeLast(MAX_WORDS)
        for (n in words.size downTo 1) {
            val phrase = words.takeLast(n).joinToString(" ")
            val found = index[phrase] ?: continue
            return found.withIndex()
                .sortedWith(compareByDescending<IndexedValue<String>> { usage.score(it.value) }.thenBy { it.index })
                .map { withTone(it.value, tone) }
                .take(max)
        }
        return emptyList()
    }

    private fun withTone(emoji: String, tone: Int): String {
        if (tone !in 0x1F3FB..0x1F3FF) return emoji
        return tones[emoji.replace("\uFE0F", "")]?.getOrNull(tone - 0x1F3FB) ?: emoji
    }

    companion object {
        /** iOS looks at the last 8 tokens. */
        const val MAX_WORDS = 8
        private val WORD = Regex("[\\p{L}\\p{N}'’&.-]+")

        /** Without its skin tone and variation selector, for comparing uses of the same emoji. */
        fun base(emoji: String): String =
            emoji.codePoints().filter { it !in 0x1F3FB..0x1F3FF && it != 0xFE0F }.toArray().let { String(it, 0, it.size) }

        /** en.emoji-tones.tsv: emoji, then its five toned forms, tab-separated. */
        fun parseTones(reader: BufferedReader): Map<String, List<String>> = buildMap {
            reader.forEachLine { line ->
                if (line.startsWith("#")) return@forEachLine
                val p = line.split('\t')
                if (p.size == 6) put(p[0], p.drop(1))
            }
        }

        fun parse(reader: BufferedReader): Map<String, List<String>> = buildMap {
            reader.forEachLine { line ->
                if (line.startsWith("#")) return@forEachLine
                val tab = line.indexOf('\t')
                if (tab > 0) put(line.substring(0, tab), line.substring(tab + 1).split(' ').filter { it.isNotEmpty() })
            }
        }
    }
}

/**
 * Which emoji the user uses, and where: iOS ranks emoji by Σ 1 / (0.1 · (now − use) + 1) over
 * the uses, counted in emoji used (not time), keeping the last [KEEP] uses; and shows emoji
 * suggestions only once the user has used emoji at all and in the app they're typing in.
 */
class EmojiUsage(private val file: File? = null) {
    private val uses = ArrayList<Pair<String, Long>>() // (emoji, sequence number)
    private val apps = HashMap<String, Int>()
    private var sequence = 0L

    init {
        load()
    }

    @Synchronized
    fun record(emoji: String, app: String?) {
        sequence++
        uses.add(emoji to sequence)
        if (uses.size > PRUNE_AT) uses.subList(0, uses.size - KEEP).clear()
        if (app != null) apps[app] = (apps[app] ?: 0) + 1
        save()
    }

    @Synchronized
    fun score(emoji: String): Double {
        val b = EmojiSuggest.base(emoji)
        return uses.sumOf { (e, seq) -> if (EmojiSuggest.base(e) == b) 1.0 / (0.1 * (sequence - seq) + 1) else 0.0 }
    }

    /** iOS: emoji suggestions only once the user has used emoji, overall and in [app]. */
    @Synchronized
    fun hasUsedEmoji(app: String?): Boolean = uses.isNotEmpty() && (app == null || (apps[app] ?: 0) > 0)

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.bufferedWriter().use { w ->
                w.write("#dogboard-emoji-usage 1\t$sequence\n")
                for ((e, seq) in uses) w.write("u\t$e\t$seq\n")
                for ((a, n) in apps) w.write("a\t$a\t$n\n")
            }
        }
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        f.forEachLine { line ->
            val p = line.split('\t')
            when {
                p[0].startsWith("#") && p.size >= 2 -> sequence = p[1].toLongOrNull() ?: 0
                p[0] == "u" && p.size == 3 -> uses.add(p[1] to p[2].toLong())
                p[0] == "a" && p.size == 3 -> apps[p[1]] = p[2].toInt()
            }
        }
    }

    companion object {
        /** iOS: prune when the sequence passes 1000, keep the last 500. */
        const val KEEP = 500
        private const val PRUNE_AT = 1000
    }
}
