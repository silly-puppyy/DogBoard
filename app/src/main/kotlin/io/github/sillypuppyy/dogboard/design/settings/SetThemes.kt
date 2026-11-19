package io.github.sillypuppyy.dogboard.design.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.asImageBitmap
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
    /** The settings follow the keyboard theme's light or dark (user 2026-11-19), else the system's. */
    val appFollows: Boolean = true,
    val onAppFollows: (Boolean) -> Unit = {},
    /** Return key per theme ("id=always;id=actions", ThemeChoice.retAlways) and its setter. */
    val retPref: String = "",
    val onRet: (String, Boolean) -> Unit = { _, _ -> },
    /** The user's packs of saved themes (UserThemes), the one in use, picking one, and editing one. */
    val userPacks: List<io.github.sillypuppyy.dogboard.design.UserThemes.Pack> = emptyList(),
    val userTheme: String? = null,
    val onPickUser: (String) -> Unit = {},
    val onEditUser: (String) -> Unit = {},
    /** A pack's menu: "rename", "up", "down", "delete"; a theme's: "rename", "delete", "move:<pack id>". */
    val onPackAction: (String, String) -> Unit = { _, _ -> },
    val onThemeAction: (String, String) -> Unit = { _, _ -> },
    /** A new photo theme in pack id; a new empty pack. */
    val onNewPhotoIn: (String) -> Unit = {},
    val onNewPack: () -> Unit = {},
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
                    maxLines = 8,
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(SetColors.surfaceContainerHighest))
                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        DText("Follow system dark mode", style = setText(16f).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                        DText(if (c.follow) "Day and night themes switch with the system" else "The same theme all the time", style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                    }
                    PageSwitch(c.follow) { state.onFollow(!c.follow); msg = "" }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(SetColors.surfaceContainerHighest))
                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        DText("Settings match the keyboard", style = setText(16f).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                        DText(
                            if (state.appFollows) "These pages are light or dark like the keyboard theme" else "These pages follow the system, in the default Material colours",
                            style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start), maxLines = 8,
                        )
                    }
                    PageSwitch(state.appFollows) { state.onAppFollows(!state.appFollows) }
                }
            }

            // user 2026-11-19: the return key's colour, a choice per theme in use
            for (id in (if (c.follow) listOf(c.day, c.night) else listOf(c.day)).distinct().filter { it != "dalmatian" }) {
                val always = ThemeChoice.retAlways(state.retPref, id)
                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(SetColors.row, RoundedCornerShape(24.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        DText(ThemeChoice.theme(id).name, style = setText(16f, 600)); DText("Theme options", style = setText(12f, color = SetColors.onSurfaceVariant))
                    }
                    DText("Return key", style = setText(14f, color = SetColors.onSurfaceVariant))
                    Segmented("th:ret:$id", listOf("For actions", "Always coloured"), if (always) 1 else 0) { k -> state.onRet(id, k == 1) }
                    DText(
                        if (always) "The return key takes the theme's colour everywhere, as on Gboard." else "Coloured when it sends or searches, plain for a new line, as on an iPhone.",
                        style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start), maxLines = 8,
                    )
                }
            }
            // :43-61 Dalmatian's options while it is in use
            if (c.day == "dalmatian" || c.night == "dalmatian") {
                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(SetColors.row, RoundedCornerShape(24.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        DText("Dalmatian", style = setText(16f, 600)); DText("Theme options", style = setText(12f, color = SetColors.onSurfaceVariant))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val always = ThemeChoice.retAlways(state.retPref, "dalmatian")
                        DText("Return key", style = setText(14f, color = SetColors.onSurfaceVariant))
                        Segmented("th:ret:dalmatian", listOf("For actions", "Always coloured"), if (always) 1 else 0) { k -> state.onRet("dalmatian", k == 1) }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DText("New spots", style = setText(14f, color = SetColors.onSurfaceVariant))
                        // the shared control: its divider sat inside the second cell, beside "Keep these", leaving an
                        // empty band after the selected fill (user 2026-11-19)
                        val rerolls = listOf("open", "keep")
                        Segmented("th:reroll", listOf("Every time it opens", "Keep these"), rerolls.indexOf(state.reroll).coerceAtLeast(0), listOf(1.45f, 1f)) { k ->
                            state.onReroll(rerolls[k])
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        DText(if (state.reroll == "open") "A new pattern each time the keyboard comes up." else "The same pattern until you shuffle.",
                            Modifier.weight(1f), style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start), maxLines = 8)
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
                        DText("Labels, dimming and the return key are matched to your photo. Change anything.", style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start), maxLines = 8)
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
                if (sec.id == "everyday" && filter == "all") UserPackSections(state, primary)
                if (sec.id in state.hidden) continue
                // with saved photo themes, the built-in Photo tile (the same photo slot) would show "In use" twice
                val ids = sec.ids.filter { ThemeChoice.keep(filter, ThemeChoice.theme(it).mode) && (it != "glass" || state.userPacks.isEmpty()) }
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
                    Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp), style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start, lineHeight = (13 * 1.45f).sp), maxLines = 8)
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


/** The user's packs (UserThemes): a section each, its saved themes as photo tiles. Tap: use it; tap the one in use:
 *  edit it; hold: rename, move to another pack, delete. Each pack: a "+" tile and a menu (rename, move, delete). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun UserPackSections(state: SetThemesState, primary: Color) {
    val context = androidx.compose.ui.platform.LocalContext.current
    for ((pi, pack) in state.userPacks.withIndex()) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    DText(pack.name, style = setText(14f, 500, primary).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                    DText("Your pack, ${pack.themes.size} ${if (pack.themes.size == 1) "theme" else "themes"}", style = setText(12f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                }
                var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                Box {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable { open = true }, contentAlignment = Alignment.Center) {
                        Symbol("more_vert", 22.sp, SetColors.onSurfaceVariant)
                    }
                    androidx.compose.material3.DropdownMenu(open, { open = false }) {
                        val items = listOfNotNull("rename" to "Rename", ("up" to "Move up").takeIf { pi > 0 },
                            ("down" to "Move down").takeIf { pi < state.userPacks.size - 1 }, "delete" to "Delete pack")
                        for ((a, label) in items) androidx.compose.material3.DropdownMenuItem({ androidx.compose.material3.Text(label) }, { open = false; state.onPackAction(pack.id, a) })
                    }
                }
            }
            val cells: List<io.github.sillypuppyy.dogboard.design.UserThemes.Theme?> = pack.themes + listOf(null)
            for (row in cells.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (t in row) {
                        if (t == null) { NewTile("add_photo_alternate", "From a photo") { state.onNewPhotoIn(pack.id) }; continue }
                        val sel = state.userTheme == t.id
                        val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, t.id) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val f = io.github.sillypuppyy.dogboard.design.UserThemes.photo(context, t.id)
                                runCatching {
                                    val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                                    android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)?.asImageBitmap()
                                }.getOrNull()
                            }
                        }
                        var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                        Box {
                            Column(
                                Modifier.width(162.dp).combinedClickable(onLongClick = { menu = true }) { if (sel) state.onEditUser(t.id) else state.onPickUser(t.id) },
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    Modifier.size(162.dp, 128.dp).clip(RoundedCornerShape(16.dp)).background(SetColors.surfaceContainerHighest)
                                        .border(if (sel) 3.dp else 1.dp, if (sel) primary else SetColors.outlineVariant, RoundedCornerShape(16.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    thumb?.let { androidx.compose.foundation.Image(it, t.name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                                    if (sel) Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { Symbol("check_circle", 24.sp, primary, fill = 1) }
                                }
                                Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    DText(t.name, Modifier.weight(1f), style = setText(15f, 600).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                                    if (sel) Symbol("edit", 18.sp, SetColors.onSurfaceVariant)
                                }
                                DText(if (sel) "In use. Tap to edit" else "Photo. Hold for more", Modifier.padding(horizontal = 2.dp), style = setText(13f, color = if (sel) primary else SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                            }
                            androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                                androidx.compose.material3.DropdownMenuItem({ androidx.compose.material3.Text("Rename") }, { menu = false; state.onThemeAction(t.id, "rename") })
                                for (other in state.userPacks) if (other.id != pack.id) {
                                    androidx.compose.material3.DropdownMenuItem({ androidx.compose.material3.Text("Move to ${other.name}") }, { menu = false; state.onThemeAction(t.id, "move:${other.id}") })
                                }
                                androidx.compose.material3.DropdownMenuItem({ androidx.compose.material3.Text("Delete") }, { menu = false; state.onThemeAction(t.id, "delete") })
                            }
                        }
                    }
                }
            }
        }
    }
    // a new pack (the first theme from a photo makes "My themes" when there is none)
    Row(Modifier.padding(horizontal = 16.dp)) {
        Row(
            Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, SetColors.outline, RoundedCornerShape(20.dp)).clickable { state.onNewPack() }.padding(start = 12.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol("add", 18.sp, primary); DText("New pack", style = setText(14f, 500, primary))
        }
    }
}
