package io.github.sillypuppyy.dogboard.design

/**
 * The theme catalogue and the Day / Night choice of the designer's Themes page
 * (SetThemes.dc.html:139-217): what each theme is called, whether it is a light, a dark, a
 * day-and-night pair or one look for both, the page's sections and packs, what picking a tile
 * does, and which theme the keyboard draws.
 */
object ThemeChoice {
    /** :165 light / dark / pair (a day and a night version) / both (one look, day and night). */
    data class Theme(val id: String, val name: String, val sub: String, val mode: String, val nightName: String? = null)

    /** :139-157 */
    val THEMES: List<Theme> = listOf(
        Theme("dogboard", "DogBoard", "Colours from your wallpaper", "pair"),
        Theme("brand", "Warm", "Fixed colours, no wallpaper", "pair"),
        Theme("glass", "Photo", "Example photo, labels picked for it", "both"),
        Theme("paper", "Paper", "Neutral greys", "light"),
        Theme("midnight", "Midnight", "Soft dark", "dark"),
        Theme("ink", "Ink", "Black, saves power on OLED", "dark"),
        Theme("borderless", "Borderless", "Labels without keys", "pair"),
        Theme("bold", "Bold", "High contrast, all day", "both"),
        Theme("dog", "Dog", "Fur tones, paw on return", "pair"),
        Theme("lab", "Labrador", "Chocolate Lab at night", "pair", nightName = "Chocolate Lab"),
        Theme("husky", "Husky", "Grey, white, ice blue", "light"),
        Theme("greyhound", "Greyhound", "Slate with fawn", "dark"),
        Theme("dalmatian", "Dalmatian", "Busy on purpose: some letters on black", "light"),
        Theme("dusk", "Dusk", "3 colours", "dark"),
        Theme("lagoon", "Lagoon", "2 colours", "dark"),
        Theme("peach", "Peach", "2 colours", "light"),
        Theme("gloss", "Gloss", "Glossy caps, pressed-in labels", "light"),
    )
    private val byId = THEMES.associateBy { it.id }
    fun theme(id: String): Theme = if (id == "glass") byId.getValue("glass").copy(mode = glassMode) else byId[id] ?: GradientSpec.parse(id)?.let { g ->
        Theme(id, userNames[id] ?: "Your gradient", if (g.colors.size == 1) "Solid colour" else "${g.colors.size} colours", if (g.white) "dark" else "light")
    } ?: GradientSpec.parsePair(id)?.let { (b, _) ->
        Theme(id, userNames[id] ?: "Your gradient", if (b.colors.size == 1) "Solid colour" else "${b.colors.size} colours", "pair")
    } ?: byId.getValue("dogboard")

    /** The Photo theme's mode from its versions (PhotoVersions.mode): bright only is light, dark only dark, both a pair. */
    @Volatile var glassMode: String = "pair"

    /** The user's gradient themes' names by spec id (the Themes page sets it from the saved themes), for its messages. */
    @Volatile var userNames: Map<String, String> = emptyMap()

    /** :158-164 sections; [pack]: a theme pack the page can hide (:217). */
    data class Section(val id: String, val title: String, val sub: String, val ids: List<String>, val pack: Boolean = false, val add: Pair<String, String>? = null)

    val SECTIONS: List<Section> = listOf(
        // user 2026-11-22: "Yours / My themes / Photos / a New pack button before Everyday" was confusing, and "Warm" looked
        // like Dog: your own themes first (the photo slot, then saved packs), then the built-in ones, then each pack;
        // "brand" (Warm) is no longer listed (kept for whoever has it picked)
        Section("everyday", "Built in", "", listOf("dogboard", "paper", "midnight", "ink", "borderless", "bold")),
        Section("breeds", "Breeds", "Pack, 5 themes", listOf("dog", "lab", "husky", "greyhound", "dalmatian"), pack = true),
        Section("gradients", "Gradients", "Pack, 3 themes", listOf("dusk", "lagoon", "peach"), pack = true, add = "gradient" to "New gradient"),
        Section("retro", "Retro", "Pack, 1 theme", listOf("gloss"), pack = true),
        // user 2026-11-22: built-in themes and packs first, yours after them (and a "Yours" filter chip)
        Section("yours", "Your themes", "", listOf("glass"), add = "add_photo_alternate" to "From a photo"),
    )

    /** :217 the packs list: (section id, icon, name, sub). */
    val PACKS: List<List<String>> = listOf(
        listOf("breeds", "pets", "Breeds", "5 themes, built in"),
        listOf("gradients", "gradient", "Gradients", "3 themes, built in"),
        listOf("retro", "history", "Retro", "1 theme, built in"),
    )

    /** :165 the mode's icon and its spoken label. */
    val MODE: Map<String, Pair<String, String>> = mapOf(
        "pair" to ("contrast" to "Has a day and a night version"),
        "both" to ("brightness_auto" to "One look, day and night"),
        "light" to ("light_mode" to "Light theme"),
        "dark" to ("dark_mode" to "Dark theme"),
    )

    /** The stored choice: the day and night themes and whether the night one follows system dark mode. */
    /** [fixedNight]: not following the system, the night slot is used all the time (else the day slot). */
    data class Choice(val day: String = "dogboard", val night: String = "dogboard", val follow: Boolean = true, val fixedNight: Boolean = false)

    /** :169-175 picking a tile: the new choice and the page's message (empty: none). */
    fun pick(c: Choice, id: String): Pair<Choice, String> {
        val t = theme(id)
        // always one slot: only that slot changes (the other is kept for when following the system again)
        if (!c.follow) return (if (c.fixedNight) c.copy(night = id) else c.copy(day = id)) to "${t.name} all the time."
        return when (t.mode) {
            "pair" -> c.copy(day = id, night = id) to "${t.name} by day, ${t.nightName ?: t.name + " dark"} at night."
            "both" -> c.copy(day = id, night = id) to "${t.name} day and night."
            "light" -> c.copy(day = id) to "${t.name} is a light theme, so it’s your day theme. Night stays ${theme(c.night).name}."
            else -> c.copy(night = id) to "${t.name} is a dark theme, so it’s your night theme. Day stays ${theme(c.day).name}."
        }
    }

    /** :177-183 what a tile says when it is in use. */
    fun where(c: Choice, id: String): String = when {
        !c.follow -> if ((if (c.fixedNight) c.night else c.day) == id) "In use" else ""
        c.day == id && c.night == id -> "Day and night"
        c.day == id -> "Day"
        c.night == id -> "Night"
        else -> ""
    }

    /** :176 the filter chips: all / light / dark / pair. */
    fun keep(filter: String, mode: String): Boolean = filter == "all" || (filter == "light" && mode != "dark") ||
        (filter == "dark" && mode != "light") || (filter == "pair" && mode == "pair")

    /**
     * What the keyboard draws: the theme id and whether its dark version is used. Following the
     * system, the night theme at night; a pair draws its own night version then, a light or dark
     * theme is what it is, one look for both stays one look.
     */
    /** Settings › Themes › Return key, stored as "id=always;id=actions": whether [id]'s return key is coloured on plain fields. */
    fun retAlways(pref: String, id: String): Boolean =
        pref.split(';').firstOrNull { it.substringBefore('=') == id }?.substringAfter('=') == "always"

    /** [pref] with [id] set to [always]. */
    fun withRet(pref: String, id: String, always: Boolean): String =
        (pref.split(';').filter { it.isNotBlank() && it.substringBefore('=') != id } + "$id=${if (always) "always" else "actions"}").joinToString(";")

    fun resolve(c: Choice, systemDark: Boolean): Pair<String, Boolean> {
        val night = if (c.follow) systemDark else c.fixedNight
        val id = if (night) c.night else c.day
        val dark = when (theme(id).mode) {
            "dark" -> true
            "light" -> false
            "pair" -> night
            else -> systemDark
        }
        return id to dark
    }
}
