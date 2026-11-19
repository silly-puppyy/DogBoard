package io.github.sillypuppyy.dogboard.settings

/**
 * Settings search (settings layout v2): every setting with the page it lives on and the words
 * people might search for. [route] is a key the settings app maps to its screen.
 */
object SettingsSearch {
    data class Entry(val title: String, val page: String, val route: String, val words: String = "")

    val entries = listOf(
        // settings tree v3 (notes/design/settings-tree-v3.md)
        Entry("Languages", "Languages", "languages", "language add keyboard"),
        Entry("Letter layout", "Layout", "layout", "qwerty qwertz azerty letters german french"),
        Entry("Layout style", "Layout", "layout", "iphone android style preset custom mix match"),
        Entry("Number row", "Layout", "layout", "digits numbers top row hold"),
        Entry("Bottom row", "Layout", "layout", "comma period 123 emoji action"),
        Entry("Symbols", "Layout", "layout", "123 punctuation pages iphone android"),
        Entry("Round keys", "Layout", "layout", "pill rounded 123 action"),
        Entry("Hold for symbols", "Layout", "layout", "long-press hints hint corner accents symbols whole page"),
        Entry("Space-bar label", "Layout", "layout", "space bar language name blank"),
        Entry("Action key", "Layout", "layout", "enter return send search go done icon word label"),
        Entry("Letter case", "Layout", "layout", "capitals uppercase caps labels"),
        Entry("Keyboard height", "Layout", "layout", "size tall short bigger smaller keys"),
        Entry("Theme", "Theme", "theme", "dark light colors colours night day appearance look"),
        Entry("Photo background", "Theme", "theme", "glass wallpaper image picture blur dim"),
        Entry("Autocorrect", "Autocorrect and typing › Autocorrect", "autocorrect", "correction typos fix gentle standard strong off"),
        Entry("Undo with backspace", "Autocorrect and typing › Autocorrect", "autocorrect", "revert undo correction delete"),
        Entry("Learn from your undos", "Autocorrect and typing › Autocorrect", "autocorrect", "adaptive strength careful bolder"),
        Entry("Suggestions", "Autocorrect and typing › Suggestions", "suggestions", "predictions bar words"),
        Entry("Emoji in suggestions", "Autocorrect and typing › Suggestions", "suggestions", "emoji"),
        Entry("Block offensive words", "Autocorrect and typing › Suggestions", "suggestions", "swear profanity filter"),
        Entry("Contact names", "Autocorrect and typing › Suggestions", "suggestions", "contacts people names"),
        Entry("Sentence starts", "Autocorrect and typing › Capitals", "capitals", "auto capitalization shift caps"),
        Entry("Names and “I”", "Autocorrect and typing › Capitals", "capitals", "capitals proper nouns casing"),
        Entry("Caps lock", "Autocorrect and typing › Capitals", "capitals", "double tap shift capitals"),
        Entry("Smart quotes", "Autocorrect and typing › Smart punctuation", "punctuation", "curly apostrophe quotes"),
        Entry("Smart dashes", "Autocorrect and typing › Smart punctuation", "punctuation", "dash ellipsis hyphen"),
        Entry("Double-space full stop", "Autocorrect and typing › Smart punctuation", "punctuation", "period dot two spaces"),
        Entry("Your words", "Autocorrect and typing › Your words", "dictionary", "learned added blocked dictionary personal reset"),
        Entry("Text replacements", "Autocorrect and typing › Your words", "dictionary", "shortcuts omw expand phrases"),
        Entry("Vibration", "Sound and vibration", "feedback", "haptics haptic buzz tap feel style rich plain soft"),
        Entry("Sound", "Sound and vibration", "feedback", "click audio noise volume"),
        Entry("Move the cursor", "Gestures", "gestures", "swipe trackpad cursor space bar hold slide"),
        Entry("Swipe to type", "Gestures", "gestures", "swipe glide gesture typing slide letters"),
        Entry("Show the trail", "Gestures", "gestures", "swipe glide trail line"),
        Entry("Slide to delete words", "Gestures", "gestures", "delete backspace slide swipe left words"),
        Entry("Swipe to move the cursor", "Gestures", "gestures", "space bar swipe slide cursor quick"),
        Entry("Emoji", "Emoji and GIFs", "emoji", "skin tone recent gif"),
        Entry("Clipboard history", "Clipboard", "clipboard", "copy paste clips keep history"),
        Entry("Suggest what you copied", "Clipboard", "clipboard", "paste chip copy recent bar"),
        Entry("Passwords clear themselves", "Clipboard", "clipboard", "password sensitive clear auto delete"),
        Entry("Delete old clips", "Clipboard", "clipboard", "clean expire minutes"),
        Entry("Smart chips", "Clipboard", "clipboard", "phone number link email address copied pieces"),
        Entry("Strip trackers from links", "Clipboard", "clipboard", "tracking utm fbclid privacy clean url link"),
        Entry("Incognito typing", "Privacy", "privacy", "private learning"),
        Entry("Hold to show accents", "Timing and feel", "finetuning", "long press delay timing accents"),
        Entry("Caps lock double-tap", "Timing and feel", "finetuning", "shift timing"),
        Entry("Double-space for a full stop", "Timing and feel", "finetuning", "period timing"),
        Entry("Delete key repeat", "Timing and feel", "finetuning", "backspace hold speed whole words"),
        Entry("Space-bar trackpad", "Timing and feel", "finetuning", "cursor hold speed"),
        Entry("Backup and restore", "Advanced", "advanced", "export import save settings"),
        Entry("Taps on the suggestion bar", "Advanced", "advanced", "bar tap guard top row window"),
        Entry("Key preview", "Advanced", "advanced", "popup bubble pressed letter"),
        Entry("Full-screen typing in landscape", "Advanced", "advanced", "landscape extract editor"),
        Entry("Physical keyboard", "Advanced", "advanced", "hardware bluetooth keyboard"),
        Entry("Neural model", "Advanced", "advanced", "battery memory ai language model"),
        Entry("Show the app icon", "Advanced", "advanced", "launcher hide icon"),
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
