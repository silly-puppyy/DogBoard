package io.github.sillypuppyy.dogboard.design.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
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

    // settings layout v2 (approved by the user 2026-11-14, notes/design/settings-layout-proposal.md):
    // sections in order of how often people need them; titles and subs from that proposal
    val sections = listOf(
        s("translate", "Languages and layouts", "Your languages, QWERTY or another layout", P),
        s("palette", "Look", "Theme, photo background, key height", T, "SetLook"),
        s("spellcheck", "Typing and correction", "Autocorrect, capitals, punctuation", S),
        s("vibration", "Sound and vibration", "Volume, vibration style, feedback per key", P, "SetFeel"),
        s("swipe", "Gestures", "Space-bar trackpad, swipe to delete", T),
        s("widgets", "Clipboard and toolbar", "Clipboard history, how long clips are kept", S),
        s("menu_book", "Your words", "Learned, added and blocked words", P, "SetDict"),
        s("shield_person", "Privacy", "Incognito, what’s stored, delete your data", T),
        s("tune", "Advanced", "Fine-tuning, developer options, backup", S),
        s("info", "About", "Version, licenses, credits", P),
    )
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
)

@Composable
fun SetHome(modifier: Modifier = Modifier, actions: SetHomeActions? = null) {
    Box(modifier.background(SetColors.screen).lint("kb")) {
        LintParent("kb") {
            Column(
                Modifier.fillMaxWidth()
                    .then(if (actions != null) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 104.dp),
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
                // SetHome:31-81 main choices
                SetGroup("Main choices") {
                    // live: Simple and Comma & period set the presets; Custom (shown whenever the elements
                    // are mixed) opens Settings › Layout (designer answers §1)
                    SetBlockRow("main:layout") {
                        TitleSub("Layout style", "How the bottom row and long-press work")
                        Segmented("seg:layout", listOf("iPhone-style", "Android-style", "Custom"), actions?.layoutPreset ?: 0, listOf(1f, 1f, 0.8f),
                            onSelect = actions?.let { a -> { i: Int -> a.onLayoutPreset(i) } })
                    }
                    SetLinkRow("main:languages", actions?.onLanguages) {
                        TitleSub("Languages", "English (US) · QWERTY", Modifier.weight(1f))
                        // SetHome:46 outlined Add button
                        Row(
                            Modifier.height(40.dp).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, SetColors.outline)), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Symbol("add", 18.sp, SetColors.primary)
                            DText("Add", style = setText(14f, 500, SetColors.primary))
                        }
                    }
                    SetLinkRow("main:look", actions?.onLook) {
                        MiniKeyboard()
                        TitleSub("Look", actions?.lookSub ?: "DogBoard, follows your wallpaper", Modifier.weight(1f))
                        Symbol("chevron_right", 24.sp, SetColors.onSurfaceVariant)
                    }
                    SetBlockRow("main:autocorrect") {
                        TitleSub("Autocorrect", "How readily typos are fixed when you press space")
                        Segmented(
                            "seg:autocorrect", listOf("Off", "Gentle", "Standard", "Strong"), actions?.autocorrect ?: 2,
                            listOf(1f, 1f, 1.3f, 1f), checkGap = 4, onSelect = actions?.onAutocorrect,
                        )
                        // SetHome:67
                        BasicText(
                            buildAnnotatedString {
                                append("Most people never need to change this. ")
                                withStyle(SpanStyle(color = SetColors.primary, fontWeight = FontWeight(500))) { append("Customize…") }
                            },
                            style = setText(13f, color = SetColors.onSurfaceVariant),
                            modifier = actions?.let { a -> Modifier.clickable { a.onSection("Typing and correction") } } ?: Modifier,
                        )
                    }
                    // live: no presets until they are designed; the row opens the vibration and sound settings
                    if (actions == null) SetBlockRow("main:feel") {
                        TitleSub("Feel", "Vibration and sound on each key")
                        Segmented("seg:feel", listOf("Quiet", "Default", "Crisp", "Custom"), 1, listOf(1f, 1.3f, 1f, 1f), checkGap = 4)
                    } else {
                        // settings layout v2: plain switches; the full page is under All settings
                        SetSwitchRow("main:sound", "Sound", "A click on each key", actions.sound, actions.onSound)
                        SetSwitchRow("main:vibration", "Vibration", "A tap on each key", actions.vibration, actions.onVibration)
                        SetSwitchRow("main:numrow", "Number row", "Digits above the letters", actions.numberRow, actions.onNumberRow)
                        SetSliderRow(
                            "main:height", "Keyboard height", "Taller keys are easier to hit; shorter ones leave more room for the app.",
                            actions.keyboardHeight.toFloat(), 100f, 80f..130f, 5f, { v -> "${v.toInt()} %" },
                        ) { v -> actions.onKeyboardHeight(v.toInt()) }
                    }
                }
                // SetHome:83-93 all settings
                SetGroup("All settings") {
                    for (s in SetHomeSpec.sections) {
                        val (bg, fg) = SetColors.container(s.bg, s.fg)
                        SetSectionRow(s.icon, s.title, s.sub, bg, fg, actions?.let { a -> { a.onSection(s.title) } })
                    }
                }
            }
            TryItDock(Modifier.align(Alignment.BottomCenter), live = actions != null)
        }
        // the page colour behind the status bar, so the scrolled page doesn't run under the clock
        if (actions != null) Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(SetColors.screen))
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
