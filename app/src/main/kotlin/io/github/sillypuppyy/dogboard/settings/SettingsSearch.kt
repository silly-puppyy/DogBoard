package io.github.sillypuppyy.dogboard.settings

/**
 * Settings search (settings layout v2): every setting with the page it lives on and the words
 * people might search for. [route] is a key the settings app maps to its screen.
 */
object SettingsSearch {
    data class Entry(val title: String, val page: String, val route: String, val words: String = "")

    val entries = listOf(
        // home
        Entry("Autocorrect", "Main choices", "typing", "correction typos fix gentle standard strong off"),
        Entry("Sound", "Main choices", "feedback", "click audio noise volume"),
        Entry("Vibration", "Main choices", "feedback", "haptics haptic buzz tap feel"),
        Entry("Number row", "Main choices", "home", "digits numbers top row"),
        Entry("Layout", "Main choices", "layout", "layout style simple classic comma period custom mix match"),
        Entry("Bottom row", "Layout", "layout", "comma period 123 emoji action"),
        Entry("Long-press hints", "Layout", "layout", "hint corner accents symbols"),
        Entry("Space-bar label", "Layout", "layout", "space bar language name blank"),
        Entry("Action key", "Layout", "layout", "enter return send search go done icon word label"),
        Entry("Letter case", "Layout", "layout", "capitals uppercase caps labels"),
        // languages
        Entry("Languages", "Languages and layouts", "languages", "language layout qwerty add keyboard"),
        // look
        Entry("Theme", "Look", "theme", "dark light colors colours night day appearance"),
        Entry("Photo background", "Look", "theme", "glass wallpaper image picture blur dim"),
        // typing
        Entry("Capitalize sentence starts", "Typing and correction", "typing", "auto capitalization shift caps"),
        Entry("Capitalize names and “I”", "Typing and correction", "typing", "capitals proper nouns casing"),
        Entry("Caps lock", "Typing and correction", "typing", "double tap shift capitals"),
        Entry("How readily autocorrect fixes words", "Typing and correction", "typing", "autocorrect level gentle strong"),
        Entry("Display suggestions", "Typing and correction", "typing", "predictions bar words"),
        Entry("Emoji suggestions", "Typing and correction", "typing", "emoji"),
        Entry("Block possibly offensive words", "Typing and correction", "typing", "swear profanity filter"),
        Entry("Incognito mode", "Typing and correction", "typing", "private privacy learning"),
        Entry("Double-space period", "Typing and correction › Punctuation", "typing", "full stop dot two spaces"),
        Entry("Smart quotes", "Typing and correction › Punctuation", "typing", "curly apostrophe quotes"),
        Entry("Smart dashes", "Typing and correction › Punctuation", "typing", "dash ellipsis hyphen"),
        Entry("Spell checker", "Typing and correction", "typing", "red underline spelling"),
        // sound and vibration
        Entry("Vibration style", "Sound and vibration", "feedback", "haptics phone click soft tap strength"),
        Entry("Sound volume", "Sound and vibration", "feedback", "audio loud quiet"),
        // gestures
        Entry("Gestures", "Gestures", "gestures", "swipe trackpad cursor space bar"),
        // clipboard
        Entry("Clipboard history", "Clipboard and toolbar", "clipboard", "copy paste clips keep"),
        // your words
        Entry("Your words", "Your words", "dictionary", "learned added blocked dictionary personal reset"),
        Entry("Text replacements", "Your words", "dictionary", "shortcuts omw expand phrases"),
        // advanced
        Entry("Hold to show accents", "Advanced › Fine-tuning", "finetuning", "long press delay timing accents"),
        Entry("Caps lock double-tap", "Advanced › Fine-tuning", "finetuning", "shift timing"),
        Entry("Double-space for a full stop", "Advanced › Fine-tuning", "finetuning", "period timing"),
        Entry("Delete key repeat", "Advanced › Fine-tuning", "finetuning", "backspace hold speed whole words"),
        Entry("Space-bar trackpad", "Advanced › Fine-tuning", "finetuning", "cursor hold speed"),
        Entry("Backup and restore", "Advanced", "advanced", "export import save settings"),
        Entry("About", "About", "about", "version licenses credits"),
    )

    /** Entries matching [query]: every word of it starts a word of the title, page or synonyms. */
    fun search(query: String): List<Entry> {
        val terms = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        return entries.filter { e ->
            val words = "${e.title} ${e.page} ${e.words}".lowercase().split(' ', '›', '-', '“', '”').filter { it.isNotBlank() }
            terms.all { t -> words.any { it.startsWith(t) } }
        }
    }
}
