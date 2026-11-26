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
        Entry("Symbol pages", "Layout", "layout", "123 punctuation pages iphone android"),
        Entry("Round keys", "Layout", "layout", "pill rounded 123 action"),
        Entry("Key hints", "Layout", "layout", "hold for symbols long-press hints hint corner accents symbols whole page"),
        Entry("Space-bar label", "Layout", "layout", "space bar language name blank"),
        Entry("Action key", "Layout", "layout", "enter return send search go done icon word label"),
        Entry("Capital letters on keys", "Layout", "layout", "capitals uppercase caps labels"),
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
        Entry("Hold the space bar", "Gestures", "gestures", "swipe trackpad cursor space bar hold slide hold to move the cursor"),
        Entry("Swipe to type", "Gestures", "gestures", "swipe glide gesture typing slide letters"),
        Entry("Before you type", "Autocorrect and typing › Suggestions", "suggestions", "empty tools toolbar first words predictions gboard"),
        Entry("Show the trail", "Gestures", "gestures", "swipe glide trail line"),
        Entry("Slide to delete words", "Gestures", "gestures", "delete backspace slide swipe left words"),
        Entry("Swipe to move the cursor", "Gestures", "gestures", "space bar swipe slide cursor quick"),
        Entry("Emoji", "Emoji and GIFs", "emoji", "skin tone recent gif"),
        Entry("Clipboard history", "Clipboard", "clipboard", "copy paste clips keep history"),
        Entry("Bring repeats to the top", "Clipboard", "clipboard", "duplicate repeat copy again dedupe merge"),
        Entry("Suggest what you copied", "Clipboard", "clipboard", "paste chip copy recent bar"),
        Entry("Link and address chips", "Clipboard", "clipboard", "phone number link email address copied pieces"),
        Entry("Strip trackers from links", "Clipboard", "clipboard", "tracking utm fbclid privacy clean url link"),
        Entry("Incognito typing", "Privacy", "privacy", "private learning"),
        Entry("Hold to show accents", "Timing and feel", "finetuning", "long press delay timing accents"),
        Entry("Caps lock double-tap", "Timing and feel", "finetuning", "shift timing"),
        Entry("Double-space speed", "Timing and feel", "finetuning", "period timing"),
        Entry("Delete key repeat", "Timing and feel", "finetuning", "backspace hold speed whole words"),
        Entry("Backup and restore", "Advanced", "advanced", "export import save settings"),
        Entry("Bar taps right after typing", "Advanced", "advanced", "bar tap guard top row window"),
        Entry("Key preview", "Advanced", "advanced", "popup bubble pressed letter"),
        Entry("Full-screen typing in landscape", "Advanced", "advanced", "landscape extract editor"),
        Entry("Physical keyboard", "Advanced", "advanced", "hardware bluetooth keyboard"),
        Entry("Neural model", "Advanced", "advanced", "battery memory ai language model"),
        Entry("Show the app icon", "Advanced", "advanced", "launcher hide icon"),
        Entry("About", "About", "about", "version licenses credits"),
        Entry("Check automatically", "About", "about", "update new version github download check for updates"),
        // rows added after the first index ("settings search only searches the top level pages");
        // SettingsSearchCoverageTest fails when a settings row has no entry
        Entry("Return to letters", "Layout", "layout", "symbols page return letters after space key comma"),
        Entry("Key sound", "Sound and vibration", "feedback", "click sound pack style pen mouse clack mech keys tick drop glass thock classic"),
        Entry("Sound variation", "Sound and vibration", "feedback", "sound vary random pitch natural"),
        Entry("Play on silent", "Sound and vibration", "feedback", "silent vibrate mute media bypass sound"),
        Entry("Volume", "Sound and vibration", "feedback", "sound loud quiet click"),
        Entry("Vibration style", "Sound and vibration", "feedback", "vibration haptic rich plain soft tap"),
        Entry("Vibrate on key presses", "Sound and vibration › When", "feedback", "haptic vibration keys"),
        Entry("Vibrate while delete repeats", "Sound and vibration › When", "feedback", "haptic backspace hold"),
        Entry("Vibrate on long press", "Sound and vibration › When", "feedback", "haptic hold accents"),
        Entry("Vibrate while sliding", "Sound and vibration › When", "feedback", "haptic cursor slide space delete"),
        Entry("Click on key presses", "Sound and vibration › When", "feedback", "sound keys"),
        Entry("Click while delete repeats", "Sound and vibration › When", "feedback", "sound backspace hold"),
        Entry("Fix the previous word", "Autocorrect and typing › Autocorrect", "autocorrect", "hindsight look back retro previous word change later"),
        Entry("Neural look-back (experimental)", "Autocorrect and typing › Autocorrect", "autocorrect", "neural model retro hindsight experimental"),
        Entry("Grammar check", "Autocorrect and typing › Autocorrect", "autocorrect", "grammar agreement blue underline gender number article spell checker"),
        Entry("Rejoin split words", "Autocorrect and typing › Autocorrect", "autocorrect", "join space split words missed space"),
        Entry("Correction strength", "Autocorrect and typing › Autocorrect", "autocorrect", "level strength gentle standard strong"),
        Entry("Notify when strength changes", "Autocorrect and typing › Autocorrect", "autocorrect", "notification adaptive strength"),
        Entry("Repeat speed", "Timing and feel", "finetuning", "delete backspace hold speed"),
        Entry("Start repeating after", "Timing and feel", "finetuning", "delete backspace hold delay"),
        Entry("Switch to whole words after", "Timing and feel", "finetuning", "delete backspace words hold"),
        Entry("Word repeat speed", "Timing and feel", "finetuning", "delete backspace words speed"),
        Entry("Hold space for", "Timing and feel", "finetuning", "space bar cursor trackpad hold time delay space-bar trackpad"),
        Entry("Cursor speed", "Timing and feel", "finetuning", "space bar trackpad cursor"),
        Entry("Recent emoji", "Emoji and GIFs", "emoji", "recent history"),
        Entry("How many to keep", "Emoji and GIFs", "emoji", "recent emoji count"),
        Entry("Skin tone", "Emoji and GIFs", "emoji", "skin colour color tone default"),
        Entry("Expand links", "Clipboard", "clipboard", "short links unshorten redirect url"),
        Entry("Link previews", "Clipboard", "clipboard", "preview title icon favicon url"),
        Entry("Clear passwords after", "Clipboard", "clipboard", "password sensitive clear time passwords clear themselves"),
        Entry("Delete clips after", "Clipboard", "clipboard", "clips expire time old delete old clips"),
        Entry("Clips to keep", "Clipboard", "clipboard", "count limit history limit the history"),
        Entry("Close after pasting", "Clipboard", "clipboard", "panel close paste"),
        Entry("Close in the next field", "Clipboard", "clipboard", "panel close field"),
        Entry("Paste as file", "Clipboard", "clipboard", "image file paste"),
        Entry("Share as file", "Clipboard", "clipboard", "image file share"),
    )

    /**
     * Everything searchable: the hand-written entries above (with synonyms), then every other row of the settings pages,
     * generated at build time with its description as search words (SettingsIndexGenerated, app/build.gradle.kts).
     */
    val all: List<Entry> by lazy {
        val byTitle = entries.associateBy { it.title }
        entries + SettingsIndexGenerated.entries.filter { it.title !in byTitle }
    }

    /** Entries matching [query]: every word of it starts a word of the title, page or synonyms. */
    fun search(query: String): List<Entry> {
        val terms = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        return all.filter { e ->
            val words = "${e.title} ${e.page} ${e.words}".lowercase().split(' ', '›', '-', '“', '”').filter { it.isNotBlank() }
            terms.all { t -> words.any { it.startsWith(t) } }
        }
    }
}
