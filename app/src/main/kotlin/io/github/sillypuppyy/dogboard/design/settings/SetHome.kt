package io.github.sillypuppyy.dogboard.design.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

    val sections = listOf(
        s("translate", "Languages and layouts", "English (US) · QWERTY · Simple layout", P),
        s("palette", "Look", "Theme, key height, key shape", T, "SetLook"),
        s("spellcheck", "Typing and correction", "Autocorrect, suggestions, text replacements", S),
        s("menu_book", "Dictionaries and models", "Your words, blocked words, language models", P, "SetDict"),
        s("vibration", "Feel", "Vibration, sound, timing", T, "SetFeel"),
        s("swipe", "Gestures", "Space-bar trackpad, swipe to delete", S),
        s("widgets", "Toolbar and tools", "Tools, clipboard, emoji", P),
        s("shield_person", "Privacy", "Incognito, what’s stored, delete your data", T),
        s("tune", "Advanced", "Developer options, backup, reset", S),
        s("info", "About", "Version, licences, credits", P),
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
                // SetHome:26-29 search (live: hidden until settings search exists)
                if (actions == null) Row(
                    Modifier.fillMaxWidth().height(56.dp).background(SetColors.surfaceContainerHighest, RoundedCornerShape(28.dp)).padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("search", 24.sp, SetColors.onSurfaceVariant)
                    DText("Search settings", Modifier.weight(1f), style = setText(16f, color = SetColors.onSurfaceVariant))
                }
                // SetHome:31-81 main choices
                SetGroup("Main choices") {
                    // live: hidden until the comma-and-period layout exists
                    if (actions == null) SetBlockRow("main:layout") {
                        TitleSub("Layout style", "How the bottom row and long-press work")
                        Segmented("seg:layout", listOf("Simple", "Comma & period", "Custom"), 0, listOf(1f, 1f, 0.8f))
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
                        TitleSub("Look", "DogBoard · follows your wallpaper", Modifier.weight(1f))
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
                                withStyle(SpanStyle(color = SetColors.primary, fontWeight = FontWeight(500))) { append("Customise…") }
                            },
                            style = setText(13f, color = SetColors.onSurfaceVariant),
                            modifier = actions?.let { a -> Modifier.clickable { a.onSection("Typing and correction") } } ?: Modifier,
                        )
                    }
                    // live: no presets until they are designed; the row opens the vibration and sound settings
                    if (actions == null) SetBlockRow("main:feel") {
                        TitleSub("Feel", "Vibration and sound on each key")
                        Segmented("seg:feel", listOf("Quiet", "Default", "Crisp", "Custom"), 1, listOf(1f, 1.3f, 1f, 1f), checkGap = 4)
                    } else SetLinkRow("main:feel", { actions.onSection("Feel") }) {
                        TitleSub("Feel", "Vibration and sound on each key", Modifier.weight(1f))
                        Symbol("chevron_right", 24.sp, SetColors.onSurfaceVariant)
                    }
                }
                // SetHome:83-93 all settings
                SetGroup("All settings") {
                    for (s in SetHomeSpec.sections) {
                        SetSectionRow(s.icon, s.title, s.sub, s.bg, s.fg, actions?.let { a -> { a.onSection(s.title) } })
                    }
                }
            }
            TryItDock(Modifier.align(Alignment.BottomCenter), live = actions != null)
        }
    }
}

/**
 * SetHome:50-54: the Look row's mini keyboard. CSS grid: 64 x 44 box, padding 6, 4 equal columns,
 * gap 3, three stretched rows: column = (52 - 9) / 4 = 10.75 dp, span-2 cell = 2 columns + 1 gap,
 * row = (32 - 6) / 3 dp.
 */
@Composable
private fun MiniKeyboard() {
    val w = css("#ffffff"); val f = css("#dbe2f9"); val a = css("#435e91")
    val col = (52f - 9f) / 4f
    val rowH = (32f - 6f) / 3f
    Column(
        Modifier.size(64.dp, 44.dp).background(css("#ededf4"), RoundedCornerShape(10.dp)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (row in listOf(listOf(w to 1, w to 1, w to 1, w to 1), listOf(f to 1, w to 1, w to 1, f to 1), listOf(f to 1, w to 2, a to 1))) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for ((c, span) in row) Box(Modifier.size((col * span + 3f * (span - 1)).dp, rowH.dp).background(c, RoundedCornerShape(3.dp)))
            }
        }
    }
}
