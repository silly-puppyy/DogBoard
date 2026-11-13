package io.github.sillypuppyy.dogboard.engine

import java.io.File

/**
 * Text replacements (iOS Settings › Keyboard › Text Replacement): a shortcut the user types is
 * replaced by its phrase when the word is finished. As on iOS
 * : the shortcut is matched ignoring case and is tried before the
 * correction engine, so it beats autocorrection; the phrase then takes the typed capitals like
 * any correction ([CaseAdapt]) and shows as the autocorrection chip.
 *
 * File: one "shortcut<TAB>phrase" per line. A new store starts with iOS's sample entry.
 */
class TextReplacements(private val file: File? = null) {
    private val entries = LinkedHashMap<String, Pair<String, String>>() // lowercase shortcut -> (shortcut, phrase)

    init {
        if (file != null && file.exists()) {
            file.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) put(line.substring(0, tab), line.substring(tab + 1), save = false)
            }
        } else {
            put(SAMPLE.first, SAMPLE.second, save = file != null)
        }
    }

    /** The phrase for [typed] (any case), or null. */
    @Synchronized
    fun phraseFor(typed: String): String? = entries[typed.lowercase()]?.second

    /** All entries as (shortcut, phrase), in the order they were added. */
    @Synchronized
    fun all(): List<Pair<String, String>> = entries.values.toList()

    /** Adds or replaces the entry for [shortcut]; returns false if it is not a valid shortcut. */
    @Synchronized
    fun put(shortcut: String, phrase: String, save: Boolean = true): Boolean {
        val s = shortcut.trim()
        if (s.isEmpty() || phrase.isEmpty() || s.any { it.isWhitespace() }) return false
        entries[s.lowercase()] = s to phrase
        if (save) save()
        return true
    }

    @Synchronized
    fun remove(shortcut: String) {
        entries.remove(shortcut.lowercase())
        save()
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(entries.values.joinToString("") { (s, p) -> "$s\t$p\n" })
        }
    }

    companion object {
        /** iOS's sample shortcut (TIUserDictionarySampleShortcuts), also the design's example. */
        val SAMPLE = "omw" to "On my way!"
    }
}
