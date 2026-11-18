package io.github.sillypuppyy.dogboard.design.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sillypuppyy.dogboard.design.CssShadow
import io.github.sillypuppyy.dogboard.design.DText
import io.github.sillypuppyy.dogboard.design.KbProps
import io.github.sillypuppyy.dogboard.design.KbSpec
import io.github.sillypuppyy.dogboard.design.KbView
import io.github.sillypuppyy.dogboard.design.Symbol
import io.github.sillypuppyy.dogboard.design.ThemeChoice
import io.github.sillypuppyy.dogboard.design.cssShadow

/** What the Themes page shows and does; the screen stores each change. */
class SetThemesState(
    val choice: ThemeChoice.Choice,
    /** Dalmatian: "open" (new spots each time) or "keep", and the kept coat's seed. */
    val reroll: String,
    val seed: Int,
    /** Section ids of the hidden theme packs. */
    val hidden: Set<String>,
    val onPick: (String) -> Unit,
    val onFollow: (Boolean) -> Unit,
    val onReroll: (String) -> Unit,
    val onShuffle: () -> Unit,
    val onHidePack: (String, Boolean) -> Unit,
    val onEditPhoto: () -> Unit,
    val onBack: () -> Unit,
)

/** Settings › Look › Themes (SetThemes.dc.html, 390 wide). */
@Composable
fun SetThemes(state: SetThemesState) {
    val c = state.choice
    var filter by remember { mutableStateOf("all") } // :136 the page's filter, not stored
    var msg by remember { mutableStateOf("") } // :36 the status line
    val primary = SetColors.primary
    Box(Modifier.fillMaxSize().background(SetColors.screen)) {
        Column(
            // below the real status bar (the canvas's fixed 40 dp let the content run under it)
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars).padding(top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // :18-22 header: back to Look, title, import a theme pack
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable { state.onBack() }, contentAlignment = Alignment.Center) { Symbol("arrow_back", 24.sp, SetColors.onSurface) }
                DText("Themes", Modifier.weight(1f), style = setText(22f).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                // the canvas's import button (file_open) is left out until theme packs can be imported
            }

            // :24-41 the day and night slots, the status line and Follow system dark mode
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(SetColors.row, RoundedCornerShape(24.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // :201-203
                    val slots = if (c.follow) listOf(
                        Triple(c.day, "light_mode", "Day"),
                        Triple(c.night, "dark_mode", "Night"),
                    ) else listOf(Triple(c.day, "palette", "Always"))
                    for ((id, icon, label) in slots) {
                        val t = ThemeChoice.theme(id)
                        val dark = if (label == "Night") t.mode != "light" else t.mode == "dark"
                        val name = if (label == "Night" && t.mode == "pair") t.nightName ?: (t.name + " dark") else t.name
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Preview(id, dark, state.seed, 162f, 128f, 0.432f, CssShadow(true, 0f, 0f, 0f, 1f, SetColors.surfaceContainerHighest))
                            Row(Modifier.padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Symbol(icon, 18.sp, SetColors.onSurfaceVariant); DText(label, style = setText(13f, color = SetColors.onSurfaceVariant))
                            }
                            DText(name, Modifier.padding(horizontal = 2.dp), style = setText(15f, 600).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                        }
                    }
                }
                // :211 the message, or what picking does
                DText(
                    msg.ifEmpty { if (c.follow) "Pick any theme: a light one sets Day, a dark one sets Night, a pair sets both." else "One theme all the time." },
                    Modifier.fillMaxWidth().heightIn(min = 20.dp).padding(horizontal = 2.dp),
                    style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start, lineHeight = (13 * 1.45f).sp),
                    maxLines = 4,
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(SetColors.surfaceContainerHighest))
                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        DText("Follow system dark mode", style = setText(16f).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                        DText(if (c.follow) "Day and night themes switch with the system" else "The same theme all the time", style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                    }
                    PageSwitch(c.follow) { state.onFollow(!c.follow); msg = "" }
                }
            }

            // :43-61 Dalmatian's options while it is in use
            if (c.day == "dalmatian" || c.night == "dalmatian") {
                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(SetColors.row, RoundedCornerShape(24.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        DText("Dalmatian", style = setText(16f, 600)); DText("Theme options", style = setText(12f, color = SetColors.onSurfaceVariant))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DText("New spots", style = setText(14f, color = SetColors.onSurfaceVariant))
                        Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(20.dp)).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, SetColors.outline)), RoundedCornerShape(20.dp))) {
                            for ((i, o) in listOf("open" to "Every time it opens", "keep" to "Keep these").withIndex()) {
                                val sel = state.reroll == o.first
                                Row(
                                    Modifier.weight(1f).height(40.dp).background(if (sel) SetColors.secondaryContainer else Color.Transparent).clickable { state.onReroll(o.first) },
                                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (i > 0) Box(Modifier.width(1.dp).height(40.dp).background(SetColors.outline))
                                    if (sel) Symbol("check", 18.sp, SetColors.onSecondaryContainer)
                                    DText(o.second, style = setText(14f, 500, if (sel) SetColors.onSecondaryContainer else SetColors.onSurface))
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        DText(if (state.reroll == "open") "A new pattern each time the keyboard comes up." else "The same pattern until you shuffle.",
                            Modifier.weight(1f), style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start), maxLines = 3)
                        Row(
                            Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, SetColors.outline)), RoundedCornerShape(20.dp))
                                .clickable { state.onShuffle(); msg = if (state.reroll == "open") "Opened again: new spots." else "New spots. They stay until you shuffle again." }
                                .padding(start = 10.dp, end = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Symbol(if (state.reroll == "open") "keyboard" else "shuffle", 18.sp, primary)
                            DText(if (state.reroll == "open") "Open again" else "Shuffle now", style = setText(14f, 500, primary))
                        }
                    }
                }
            }

            // :63-69 the photo theme's editor while it is in use
            if (c.day == "glass" || c.night == "glass") {
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(SetColors.row).clickable { state.onEditPhoto() }.padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(SetColors.surfaceContainerHighest), contentAlignment = Alignment.Center) { Symbol("photo", 24.sp, SetColors.onSurfaceVariant) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        DText("Edit photo theme", style = setText(16f).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                        DText("Labels, dimming and the return key are matched to your photo. Change anything.", style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start), maxLines = 3)
                    }
                    Symbol("chevron_right", 24.sp, SetColors.onSurfaceVariant)
                }
            }

            // :71-75 filter chips
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((id, label) in listOf("all" to "All", "light" to "Light", "dark" to "Dark", "pair" to "Day and night")) {
                    val on = filter == id
                    Row(
                        Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(if (on) SetColors.secondaryContainer else Color.Transparent)
                            .then(if (on) Modifier else Modifier.cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, SetColors.outline)), RoundedCornerShape(8.dp)))
                            .clickable { filter = id }.padding(start = if (on) 8.dp else 12.dp, end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (on) Symbol("check", 18.sp, SetColors.onSecondaryContainer)
                        DText(label, style = setText(14f, 500, if (on) SetColors.onSecondaryContainer else SetColors.onSurfaceVariant))
                    }
                }
            }

            // :77-113 the sections of theme tiles
            for (sec in ThemeChoice.SECTIONS) {
                if (sec.id in state.hidden) continue
                val ids = sec.ids.filter { ThemeChoice.keep(filter, ThemeChoice.theme(it).mode) }
                val add = sec.add?.takeIf { filter == "all" }
                if (ids.isEmpty() && add == null) continue
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        DText(sec.title, style = setText(14f, 500, primary)); DText(sec.sub, style = setText(12f, color = SetColors.onSurfaceVariant))
                    }
                    // the section's tiles, then its "add" tile (null) when the filter shows all
                    val cells: List<String?> = ids + (if (add != null) listOf(null) else emptyList())
                    for (row in cells.chunked(2)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (id in row) {
                                if (id == null) NewTile(add!!.first, add.second) { if (sec.id == "yours") state.onEditPhoto() }
                                else ThemeTile(id, c, state.seed, primary) { val (_, m) = ThemeChoice.pick(c, id); state.onPick(id); msg = m }
                            }
                        }
                    }
                }
            }

            // :115-130 theme packs: show or hide each in the gallery, import one
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DText("Theme packs", Modifier.padding(start = 4.dp, top = 8.dp), style = setText(14f, 500, primary))
                SetRows {
                    for ((id, icon, name, sub) in ThemeChoice.PACKS) {
                        val shown = id !in state.hidden
                        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).background(SetColors.row).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(SetColors.surfaceContainerHighest), contentAlignment = Alignment.Center) { Symbol(icon, 22.sp, SetColors.onSurfaceVariant) }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                DText(name, style = setText(16f).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                                DText(sub, style = setText(14f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                            }
                            PageSwitch(shown) { state.onHidePack(id, shown) }
                        }
                    }
                    // "Import a theme pack" (canvas) waits for pack import to exist: a row that did nothing
                }
                DText("A hidden pack disappears from this list but stays installed, and the theme you are using keeps working.",
                    Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp), style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start, lineHeight = (13 * 1.45f).sp), maxLines = 4)
            }
        }
        StatusBarBand()
    }
}

/** :86-108 a theme tile: the keyboard (a pair split diagonally into its night version), check, name, mode, where it is used. */
@Composable
private fun ThemeTile(id: String, c: ThemeChoice.Choice, seed: Int, primary: Color, onPick: () -> Unit) {
    val t = ThemeChoice.theme(id)
    val where = ThemeChoice.where(c, id)
    val sel = where.isNotEmpty()
    Column(Modifier.width(173.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(173.dp, 137.dp)
                .cssShadow(if (sel) listOf(CssShadow(false, 0f, 0f, 0f, 3f, SetColors.screen), CssShadow(false, 0f, 0f, 0f, 5f, primary)) else listOf(CssShadow(true, 0f, 0f, 0f, 1f, SetColors.outlineVariant)), RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp)).clickable(onClick = onPick),
        ) {
            Preview(id, t.mode == "dark", seed, 173f, 137f, 0.4613f, null)
            // :90-92 a pair shows its night version in the lower-right triangle
            if (t.mode == "pair") Box(Modifier.size(173.dp, 137.dp).clip(GenericShape { s, _ -> moveTo(s.width, 0f); lineTo(s.width, s.height); lineTo(0f, s.height); close() })) {
                Preview(id, true, seed, 173f, 137f, 0.4613f, null)
            }
            if (sel) Box(Modifier.offset(x = 143.dp, y = 6.dp).size(24.dp).cssShadow(listOf(CssShadow(false, 0f, 0f, 0f, 2f, Color.White)), RoundedCornerShape(12.dp)).background(primary, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Symbol("check", 18.sp, Color.White)
            }
        }
        Row(Modifier.padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            DText(t.name, Modifier.weight(1f), style = setText(15f, if (sel) 600 else 400).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start), maxLines = 1)
            Symbol(ThemeChoice.MODE.getValue(t.mode).first, 18.sp, SetColors.onSurfaceVariant)
        }
        DText(if (sel) where else t.sub, Modifier.padding(horizontal = 2.dp).heightIn(min = 18.dp),
            style = setText(13f, color = if (sel) primary else SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start, lineHeight = (13 * 1.35f).sp), maxLines = 3)
    }
}

/** :93-95 the "add" tile at the end of a section (From a photo, New gradient). */
@Composable
private fun NewTile(icon: String, label: String, onClick: () -> Unit) {
    Column(Modifier.width(173.dp)) {
        Column(
            Modifier.size(173.dp, 137.dp).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1.5f, SetColors.outline)), RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Symbol(icon, 28.sp, SetColors.primary)
            DText(label, style = setText(13f, 500, SetColors.primary))
        }
    }
}

/** A keyboard preview: the theme drawn at 375 wide, scaled into a [w] x [h] box. */
@Composable
private fun Preview(id: String, dark: Boolean, seed: Int, w: Float, h: Float, scale: Float, ring: CssShadow?) {
    val render = remember(id, dark, seed) { KbSpec.render(KbProps(theme = id, dark = dark, width = 375f, bar = "bal", ret = "send", spotSeed = seed)) }
    Box(Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(14.dp)).then(if (ring != null) Modifier.cssShadow(listOf(ring), RoundedCornerShape(14.dp)) else Modifier)) {
        Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }) {
            KbView(render)
        }
    }
}

/** :39 the canvas's switch: 52 x 32 track, the thumb grows when on. */
@Composable
private fun PageSwitch(on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp, 32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) SetColors.primary else SetColors.surfaceContainerHighest)
            .then(if (on) Modifier else Modifier.cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 2f, SetColors.outline)), RoundedCornerShape(16.dp)))
            .clickable(onClick = onClick),
    ) {
        val size = if (on) 24 else 16
        Box(Modifier.offset(x = (if (on) 24 else 8).dp, y = (if (on) 4 else 8).dp).size(size.dp).clip(RoundedCornerShape(50)).background(if (on) Color.White else SetColors.outline))
    }
}
