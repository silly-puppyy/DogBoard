package io.github.sillypuppyy.dogboard.design.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sillypuppyy.dogboard.design.CssShadow
import io.github.sillypuppyy.dogboard.design.DText
import io.github.sillypuppyy.dogboard.design.LintParent
import io.github.sillypuppyy.dogboard.design.Symbol
import io.github.sillypuppyy.dogboard.design.css
import io.github.sillypuppyy.dogboard.design.cssShadow
import io.github.sillypuppyy.dogboard.design.lint
import androidx.compose.foundation.text.BasicText

/** SetHome.dc.html:106-120: the "All settings" sections (icon, title, sub, colour set). */
object SetHomeSpec {
    private val P = css("#d8e2ff") to css("#2a4678") // primaryContainer
    private val S = css("#dbe2f9") to css("#3f4759") // secondaryContainer
    private val T = css("#fbd7fc") to css("#583e5b") // tertiaryContainer

    data class Section(val icon: String, val title: String, val sub: String, val bg: androidx.compose.ui.graphics.Color, val fg: androidx.compose.ui.graphics.Color, val target: String = "")

    private fun s(icon: String, title: String, sub: String, c: Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>, target: String = "") =
        Section(icon, title, sub, c.first, c.second, target)

    // settings tree v3 ("remove the main section and sort everything Gboard style";
    // notes/design/settings-tree-v3.md): one list in Gboard's order, pages a few levels deep
    val sections = listOf(
        s("translate", "Languages", "English (US), QWERTY", P),
        s("keyboard", "Layout", "Style, number row, bottom row, keyboard height", S),
        s("palette", "Theme", "Day and night themes, photo background", T),
        s("spellcheck", "Autocorrect and typing", "Autocorrect, suggestions, your words", P),
        s("vibration", "Sound and vibration", "Vibration style, volume, when they play", S),
        s("swipe", "Gestures", "Swipe typing, delete and space-bar slides", T),
        // "a timing and feel section in the settings? Something like delete speed"
        s("schedule", "Timing and feel", "Delete speed, hold times, double taps", P),
        s("mood", "Emoji and GIFs", "Skin tone, recent emoji", S),
        s("content_paste", "Clipboard", "History, how long clips are kept", T),
        s("shield_person", "Privacy", "Incognito, contacts, your data", P),
        s("tune", "Advanced", "Neural model, suggestion-bar taps, backup", S),
        s("info", "About", "Version, licenses, credits", T),
    )

    /**
     * The home page's cards, Gboard style ("split out sections in the main settings page like gboard,
     * to make it easier to find what you're looking for"): setup, typing, your data, the app; no headers, as Gboard.
     */
    val groups: List<List<Section>> = listOf(
        listOf("Languages", "Layout", "Theme"),
        listOf("Autocorrect and typing", "Sound and vibration", "Gestures", "Timing and feel", "Emoji and GIFs"),
        listOf("Clipboard", "Privacy"),
        listOf("Advanced", "About"),
    ).map { titles -> titles.map { t -> sections.first { it.title == t } } }
}

/** SetHome.dc.html:16-101, the settings home page as drawn (static, like the canvas). */
/**
 * What the live settings app wires into the home page (null in the design gallery, where the page
 * is static): the autocorrect choice (0 Off ... 3 Strong) and where each row leads.
 */
class SetHomeActions(
    val autocorrect: Int,
    val onAutocorrect: (Int) -> Unit,
    val onLanguages: () -> Unit,
    val onLook: () -> Unit,
    /** A section row, by its title (SetHomeSpec.sections). */
    val onSection: (String) -> Unit,
    // settings layout v2: the first-week choices as plain switches
    val sound: Boolean = true,
    val onSound: (Boolean) -> Unit = {},
    val vibration: Boolean = true,
    val onVibration: (Boolean) -> Unit = {},
    val numberRow: Boolean = false,
    val onNumberRow: (Boolean) -> Unit = {},
    val actionWords: Boolean = false,
    /** Layout style: 0 Simple, 1 Comma & period, 2 Custom. */
    val layoutPreset: Int = 0,
    val onLayoutPreset: (Int) -> Unit = {},
    val onActionWords: (Boolean) -> Unit = {},
    val onSearch: (() -> Unit)? = null,
    val keyboardHeight: Int = 100,
    /** The Look row's line: the chosen theme(s). */
    val lookSub: String = "DogBoard, follows your wallpaper",
    val onKeyboardHeight: (Int) -> Unit = {},
    /** Your languages, for the Languages row ("English (US), French and 3 more"); null: the design's sample. */
    val languages: String? = null,
    /** A newer DogBoard on GitHub (AppUpdates), its version; null: none. [updating]: its download is running. */
    val update: String? = null,
    val updating: Boolean = false,
    val onUpdate: () -> Unit = {},
)

@Composable
fun SetHome(modifier: Modifier = Modifier, actions: SetHomeActions? = null) {
    Box(modifier.background(SetColors.screen).lint("kb")) {
        LintParent("kb") {
            Column(
                Modifier.fillMaxWidth()
                    .then(if (actions != null) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    // live: below the real status bar (its band covered the logo and title with the
                    // canvas's fixed 40 dp); the gallery keeps the canvas's spacing
                    .then(if (actions != null) Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars) else Modifier)
                    .padding(start = 16.dp, end = 16.dp, top = if (actions != null) 16.dp else 40.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // SetHome:18-24 top bar
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        PawLogo(36f)
                        DText("DogBoard", style = appNameStyle())
                    }
                    // live: no help page yet, so no help button
                    if (actions == null) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Symbol("help", 24.sp, SetColors.onSurfaceVariant) }
                }
                // SetHome:26-29 search (live: opens the settings search page)
                if (actions == null || actions.onSearch != null) Row(
                    Modifier.fillMaxWidth().height(56.dp).then(if (actions?.onSearch != null) Modifier.clickable { actions.onSearch.invoke() } else Modifier).background(SetColors.surfaceContainerHighest, RoundedCornerShape(28.dp)).padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("search", 24.sp, SetColors.onSurfaceVariant)
                    DText("Search settings", Modifier.weight(1f), style = setText(16f, color = SetColors.onSurfaceVariant))
                }
                // a newer DogBoard (the GitHub build checks its releases once a day)
                actions?.update?.let { v ->
                    Row(
                        Modifier.fillMaxWidth().background(SetColors.secondaryContainer, RoundedCornerShape(24.dp)).padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DText("DogBoard $v is out", Modifier.weight(1f), style = setText(16f, 500, SetColors.onSecondaryContainer).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                        Box(
                            Modifier.height(40.dp).background(SetColors.primary, RoundedCornerShape(20.dp)).then(if (actions.updating) Modifier else Modifier.clickable { actions.onUpdate() }).padding(horizontal = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) { DText(if (actions.updating) "Downloading" else "Update", style = setText(14f, 500, SetColors.screen)) }
                    }
                }
                // settings tree v3 in Gboard's order, split into cards as Gboard's home is
                for (group in SetHomeSpec.groups) SetRows(Modifier.fillMaxWidth()) {
                    for (s in group) {
                        val (bg, fg) = SetColors.container(s.bg, s.fg)
                        // a status, not a description (drop the descriptions to avoid clutter, as Gboard,
                        // whose home shows only the language under Languages)
                        // no line under any row, Languages included (user 2026-11-24: "remove the description from the
                        // languages tab"; it was the design's sample "English (US), QWERTY", then your languages)
                        val sub = ""
                        SetSectionRow(s.icon, s.title, sub, bg, fg, actions?.let { a -> { a.onSection(s.title) } })
                    }
                }
            }
            TryItDock(Modifier.align(Alignment.BottomCenter), live = actions != null)
        }
        // the page colour behind the status bar, so the scrolled page doesn't run under the clock
        if (actions != null) StatusBarBand()
    }
}

/**
 * SetHome:50-54: the Look row's mini keyboard. CSS grid: 64 x 44 box, padding 6, 4 equal columns,
 * gap 3, three stretched rows: column = (52 - 9) / 4 = 10.75 dp, span-2 cell = 2 columns + 1 gap,
 * row = (32 - 6) / 3 dp.
 */
@Composable
private fun MiniKeyboard() {
    // the canvas's colours are the default scheme's roles; the app's scheme now follows the keyboard theme
    val w = SetColors.row; val f = SetColors.secondaryContainer; val a = SetColors.primary
    val col = (52f - 9f) / 4f
    val rowH = (32f - 6f) / 3f
    Column(
        Modifier.size(64.dp, 44.dp).background(SetColors.surfaceContainerHighest, RoundedCornerShape(10.dp)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (row in listOf(listOf(w to 1, w to 1, w to 1, w to 1), listOf(f to 1, w to 1, w to 1, f to 1), listOf(f to 1, w to 2, a to 1))) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for ((c, span) in row) Box(Modifier.size((col * span + 3f * (span - 1)).dp, rowH.dp).background(c, RoundedCornerShape(3.dp)))
            }
        }
    }
}
