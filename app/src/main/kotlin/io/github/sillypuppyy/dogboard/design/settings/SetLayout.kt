package io.github.sillypuppyy.dogboard.design.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sillypuppyy.dogboard.design.CssShadow
import io.github.sillypuppyy.dogboard.design.DText
import io.github.sillypuppyy.dogboard.design.KbProps
import io.github.sillypuppyy.dogboard.design.KbSpec
import io.github.sillypuppyy.dogboard.design.KbView
import io.github.sillypuppyy.dogboard.design.Symbol
import io.github.sillypuppyy.dogboard.design.cssShadow
import io.github.sillypuppyy.dogboard.design.liveDark
import io.github.sillypuppyy.dogboard.design.liveTheme

/**
 * The layout elements (SetLayout.dc.html:96-98): number row, bottom row, long-press hints, space-bar
 * label, action key, letter case.
 */
data class LayoutElements(
    val numRow: String = "off", // off, on, hints
    val bottom: String = "simple", // simple, classic
    val hints: String = "off", // off, accents, symbols
    val space: String = "auto", // auto, lang, space, blank
    val action: String = "icon", // icon, word
    val caseMode: String = "shift", // shift, caps
    // user 2026-11-17, mix and match: the symbol pages and ?123 / action keys as pills
    val symbols: String = "iphone", // iphone (123 / #+=), android (?123 / =\<)
    val pill: Boolean = false,
) {
    companion object {
        /** SetLayout.dc.html:96-97: the two presets. */
        /** iPhone-style (was "Simple"). */
        val SIMPLE = LayoutElements()
        // digits on the top row only, as Gboard and the designer's answer (D3) have it; symbol or
        // accent hints stay one choice away (Settings > Layout > Hints)
        /** Android-style (was "Classic"): Gboard's bottom row, symbol pages and pill keys. */
        val CLASSIC = LayoutElements(numRow = "hints", bottom = "classic", hints = "off", symbols = "android", pill = true)
    }
}

/** What the live page shows and does: the stored elements and a setter (the screen saves each change). */
class SetLayoutState(
    val elements: LayoutElements,
    val onChange: (LayoutElements) -> Unit,
    val onBack: () -> Unit,
    /** The language and layout under the title ("English (US), QWERTY"). */
    val subtitle: String = "English (US), QWERTY",
    /** The letter layout (gen_layouts.py ids); null hides the choice (previews). */
    val layoutId: String? = null,
    val onLayoutId: (String) -> Unit = {},
    /** Keyboard height in percent (moved here from the home page, settings tree v3); null hides it. */
    val heightPercent: Int? = null,
    val onHeightPercent: (Int) -> Unit = {},
)

/** SetLayout.dc.html:107-112: the note under the preset selector. */
private val PRESET_NOTES = mapOf(
    "simple" to "As on an iPhone: a wide return key, punctuation on the 123 pages and on long-press.",
    "classic" to "As on Android: comma and period beside space, digits on the top row, round ?123 and action keys.",
    "custom" to "Your own mix. Pick iPhone-style or Android-style to start over from a preset.",
)

/** Settings › Layout, mix and match (SetLayout.dc.html, 390 x 1900). */
@Composable
fun SetLayout(state: SetLayoutState) {
    val st = state.elements
    // :105-106 the preset is whichever one every element matches, else Custom
    val preset = when (st) { LayoutElements.SIMPLE -> "simple"; LayoutElements.CLASSIC -> "classic"; else -> "custom" }
    var field by remember { mutableStateOf("text") } // :98 the preview's field, not stored
    Box(Modifier.fillMaxSize().background(SetColors.screen)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 40.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // :18-22 header: back, title + language, Reset all while anything differs from Simple
            Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable { state.onBack() }, contentAlignment = Alignment.Center) { Symbol("arrow_back", 24.sp, SetColors.onSurface) }
                Column(Modifier.weight(1f)) {
                    DText("Layout", style = setText(22f))
                    DText(state.subtitle, style = setText(13f, color = SetColors.onSurfaceVariant))
                }
                if (st != LayoutElements.SIMPLE) {
                    Box(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).clickable { state.onChange(LayoutElements.SIMPLE) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        DText("Reset all", style = setText(14f, 500, SetColors.primary))
                    }
                }
            }
            // :24-29 the preview card: field chips and the keyboard as set
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(SetColors.row, RoundedCornerShape(24.dp)).padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((id, label) in listOf("text" to "Message", "email" to "Email", "url" to "Web address")) {
                        val on = field == id
                        Box(
                            Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(if (on) SetColors.secondaryContainer else Color.Transparent)
                                .then(if (on) Modifier else Modifier.cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, SetColors.outline)), RoundedCornerShape(8.dp)))
                                .clickable { field = id }.padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) { DText(label, style = setText(13f, 500, if (on) SetColors.onSecondaryContainer else SetColors.onSurfaceVariant)) }
                    }
                }
                val dark = liveDark()
                val props = liveTheme().apply(
                    KbProps(
                        dark = dark, width = 358f, field = field, bar = "bal",
                        ret = when (field) { "url" -> "go"; "email" -> "next"; else -> "send" },
                        kstyle = if (st.bottom == "classic") "android" else "ios",
                        numRow = st.numRow, hintKind = st.hints, spaceLabel = st.space,
                        actionStyle = st.action, caseMode = st.caseMode, symbols = st.symbols, pillKeys = st.pill,
                    ),
                )
                val render = remember(props) { KbSpec.render(props) }
                Box(Modifier.width(358.dp)) { KbView(render) }
            }
            // DogBoard: the letter layout (moved here from the old Typing screen, 2026-11-17)
            state.layoutId?.let { id ->
                SetGroup("Letters") {
                    Column(Modifier.fillMaxWidth().background(SetColors.row).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ElementSeg("letters", listOf("qwerty-en" to "QWERTY", "qwertz-de" to "QWERTZ", "azerty-fr" to "AZERTY"), id, state.onLayoutId)
                        DText(
                            "QWERTZ and AZERTY type German and French letters; suggestions and autocorrect stay English-only for now, so they’re off with these.",
                            style = setText(13f, color = SetColors.onSurfaceVariant), maxLines = 4,
                        )
                    }
                }
            }
            // :31-41 Preset
            SetGroup("Preset") {
                Column(Modifier.fillMaxWidth().background(SetColors.row).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Segmented(
                        "layout:preset", listOf("iPhone-style", "Android-style", "Custom"), listOf("simple", "classic", "custom").indexOf(preset),
                    ) { i -> if (i == 0) state.onChange(LayoutElements.SIMPLE) else if (i == 1) state.onChange(LayoutElements.CLASSIC) }
                    DText(PRESET_NOTES.getValue(preset), style = setText(13f, color = SetColors.onSurfaceVariant), maxLines = 3)
                }
            }
            // :43-78 Mix and match
            SetGroup("Mix and match") {
                ElementRow("Number row", "A row of digits above the letters, or each digit when you hold its top-row letter.", st.numRow != LayoutElements.SIMPLE.numRow, { state.onChange(st.copy(numRow = LayoutElements.SIMPLE.numRow)) }) {
                    ElementSeg("numrow", listOf("off" to "Off", "on" to "Always", "hints" to "On hold"), st.numRow) { state.onChange(st.copy(numRow = it)) }
                }
                ElementRow("Bottom row", "Where comma and period live. Email fields put @ in the comma slot, web addresses /.", st.bottom != LayoutElements.SIMPLE.bottom, { state.onChange(st.copy(bottom = LayoutElements.SIMPLE.bottom)) }) {
                    BottomRowCard(st.bottom == "simple", "123, emoji, space, action", listOf(Mk("123", 1.24f, 'f'), Mk("emo", 1.25f, 'f'), Mk("", 5f, 'k'), Mk("ret", 2.5f, 'a'))) { state.onChange(st.copy(bottom = "simple")) }
                    BottomRowCard(st.bottom == "classic", "?123, comma, emoji, space, period, action", listOf(Mk("?123", 1.5f, 'f'), Mk(",", 1f, 'k'), Mk("emo", 1f, 'f'), Mk("", 4f, 'k'), Mk(".", 1f, 'k'), Mk("ret", 1.5f, 'a'))) { state.onChange(st.copy(bottom = "classic")) }
                }
                ElementRow("Symbols", "The punctuation pages behind 123: iPhone-style (123 and #+=) or Android-style (?123 and =\\<).", st.symbols != LayoutElements.SIMPLE.symbols, { state.onChange(st.copy(symbols = LayoutElements.SIMPLE.symbols)) }) {
                    ElementSeg("symbols", listOf("iphone" to "iPhone-style", "android" to "Android-style"), st.symbols) { state.onChange(st.copy(symbols = it)) }
                }
                ElementRow("Round keys", "123 and the action key as round pills, as on Android.", st.pill != LayoutElements.SIMPLE.pill, { state.onChange(st.copy(pill = LayoutElements.SIMPLE.pill)) }) {
                    ElementSeg("pill", listOf("off" to "Off", "on" to "On"), if (st.pill) "on" else "off") { state.onChange(st.copy(pill = it == "on")) }
                }
                ElementRow("Hold for symbols", "“Whole page” puts the symbols page on the letters: each key shows a symbol in its corner, and holding it types that symbol. “Accents” shows each letter’s first accent in its corner.", st.hints != LayoutElements.SIMPLE.hints, { state.onChange(st.copy(hints = LayoutElements.SIMPLE.hints)) }) {
                    ElementSeg("hints", listOf("off" to "Off", "accents" to "Accents", "symbols" to "Whole page"), st.hints) { state.onChange(st.copy(hints = it)) }
                }
                ElementRow("Space-bar label", "Auto shows the language name only when you have more than one.", st.space != LayoutElements.SIMPLE.space, { state.onChange(st.copy(space = LayoutElements.SIMPLE.space)) }) {
                    ElementSeg("space", listOf("auto" to "Auto", "lang" to "Language", "space" to "“space”", "blank" to "Blank"), st.space) { state.onChange(st.copy(space = it)) }
                }
                ElementRow("Action key", "Icons read the same in every language; words say exactly what happens.", st.action != LayoutElements.SIMPLE.action, { state.onChange(st.copy(action = LayoutElements.SIMPLE.action)) }) {
                    ElementSeg("action", listOf("icon" to "Icon", "word" to "Word"), st.action) { state.onChange(st.copy(action = it)) }
                }
                ElementRow("Letter case", "Whether letters on keys follow shift or always show capitals.", st.caseMode != LayoutElements.SIMPLE.caseMode, { state.onChange(st.copy(caseMode = LayoutElements.SIMPLE.caseMode)) }) {
                    ElementSeg("case", listOf("shift" to "Follows shift", "caps" to "Always capitals"), st.caseMode) { state.onChange(st.copy(caseMode = it)) }
                }
            }
            // settings tree v3: keyboard height (was on the home page)
            state.heightPercent?.let { h ->
                SetGroup("Size") {
                    SetSliderRow(
                        "layout:height", "Keyboard height", "Taller keys are easier to hit; shorter ones leave more room for the app.",
                        h.toFloat(), 100f, 80f..130f, 5f, { v -> "${v.toInt()} %" },
                    ) { v -> state.onHeightPercent(v.toInt()) }
                }
            }
            // :80-83 per-language note
            Row(Modifier.padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Symbol("translate", 18.sp, SetColors.primary)
                DText(
                    "Set per language. Languages without capital letters hide Letter case; Japanese adds Flick guides; Arabic and Hindi add Digit shapes.",
                    style = setText(13f, color = SetColors.onSurfaceVariant), maxLines = 4,
                )
            }
        }
        // :85-90 Try it
        TryItDock(Modifier.align(Alignment.BottomCenter), live = true)
    }
}

/** :47-52 an element: title, sub, and the 44 dp reset disc while it differs from Simple. */
@Composable
private fun ElementRow(title: String, sub: String, changed: Boolean, onReset: () -> Unit, control: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(SetColors.row).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.heightIn(min = 44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DText(title, style = setText(16f))
                DText(sub, style = setText(13f, color = SetColors.onSurfaceVariant), maxLines = 3)
            }
            if (changed) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(SetColors.secondaryContainer).clickable(onClick = onReset), contentAlignment = Alignment.Center) {
                    Symbol("restart_alt", 20.sp, SetColors.primary)
                }
            }
        }
        Box(Modifier.padding(end = 8.dp)) { control() }
    }
}

/** :54-60 an element's segmented choice (13 sp, 16 dp check). */
@Composable
private fun ElementSeg(id: String, options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    Segmented("layout:$id", options.map { it.second }, options.indexOfFirst { it.first == value }, checkGap = 3) { i -> onPick(options[i].first) }
}

/** :87-89 a mini key in a bottom-row card: label (or icon for emoji / action), width weight, tone. */
private data class Mk(val l: String, val w: Float, val tone: Char)

/** :62-75 a bottom-row choice: a card with the row drawn small and a radio line. */
@Composable
private fun BottomRowCard(selected: Boolean, label: String, keys: List<Mk>, onPick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, if (selected) 2f else 1f, if (selected) SetColors.primary else SetColors.outlineVariant)), shape)
            .background(if (selected) SetColors.screen else SetColors.row, shape).clip(shape).clickable(onClick = onPick).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(34.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (k in keys) {
                val bg = when (k.tone) { 'a' -> SetColors.primary; 'f' -> SetColors.secondaryContainer; else -> SetColors.surfaceContainerHighest }
                val fg = when (k.tone) { 'a' -> Color.White; 'f' -> SetColors.onSecondaryContainer; else -> SetColors.onSurface }
                Box(Modifier.weight(k.w).fillMaxHeight().background(bg, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    when (k.l) {
                        "emo" -> Symbol("mood", 16.sp, fg)
                        "ret" -> Symbol("send", 16.sp, fg)
                        else -> DText(k.l, style = setText(12f, 500, fg))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Symbol(if (selected) "radio_button_checked" else "radio_button_unchecked", 18.sp, SetColors.primary, fill = if (selected) 1 else 0)
            DText(label, style = setText(13f, 500))
        }
    }
}
