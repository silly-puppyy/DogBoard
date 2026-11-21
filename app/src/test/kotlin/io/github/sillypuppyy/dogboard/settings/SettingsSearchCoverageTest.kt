package io.github.sillypuppyy.dogboard.settings

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File

/**
 * Every settings row is searchable (user 2026-11-21: "settings search only searches the top level pages"): the titles
 * of the rows on the settings pages (SetSwitchRow, SetSliderRow, TitleSub, SetNavRow) all have a SettingsSearch entry.
 * The photo and gradient editors' controls (hue, blur, ...) are editor tools, not settings, and are left out.
 */
class SettingsSearchCoverageTest : FunSpec({
    test("every settings row has a search entry") {
        val known = SettingsSearch.all.map { it.title }.toSet()
        val editors = setOf("SetGradient.kt", "SetPhoto.kt")
        val row = Regex("""Set(?:Switch|Slider|Nav)Row\(\s*"[^"]*",\s*"([^"]+)"|TitleSub\(\s*"([^"]+)"""")
        val files = File("src/main/kotlin/io/github/sillypuppyy/dogboard/settings").listFiles { f -> f.name.endsWith("Screen.kt") }.orEmpty().toList() +
            File("src/main/kotlin/io/github/sillypuppyy/dogboard/design/settings").listFiles { f -> f.name.startsWith("Set") && f.name !in editors }.orEmpty().toList()
        val missing = files.flatMap { f -> row.findAll(f.readText()).map { m -> m.groupValues.drop(1).first { it.isNotEmpty() } to f.name }.toList() }
            .filter { (title, _) -> title !in known }.distinct()
        missing.shouldBeEmpty()
    }
})
