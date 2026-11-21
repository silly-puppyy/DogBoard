package io.github.sillypuppyy.dogboard.design.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.imePadding
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
import dev.patrickgold.jetpref.datastore.model.collectAsState
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
    /** The settings app's colours: "keyboard", "wallpaper" or "none". */
    val appColours: String = "keyboard",
    val onAppColours: (String) -> Unit = {},
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
    /** The gradient creator, for a new gradient in pack [String] (null: the default pack). */
    val onNewGradientIn: (String?) -> Unit = {},
    /** A solid colour theme (a one-colour gradient) in pack [String] (null: the default pack). */
    val onNewSolidIn: (String?) -> Unit = {},
    /** The theme ids in use now (day and night), so a gradient tile knows it is chosen. */
    val inUse: Set<String> = emptySet(),
    /** Settings › Themes › Manage packs. */
    val onManagePacks: () -> Unit = {},
    /** Import a shared .dogtheme pack. */
    val onImportPack: () -> Unit = {},
)

/** Settings › Look › Themes (SetThemes.dc.html, 390 wide). */
@Composable
fun SetThemes(state: SetThemesState) {
    val c = state.choice
    var filter by remember { mutableStateOf("all") } // :136 the page's filter, not stored
    var msg by remember { mutableStateOf("") } // :36 the status line
    val primary = SetColors.primary
    // New theme (user 2026-11-19: one button for photo, gradient and solid colour): the pack it goes in, "" the default
    var newIn by remember { mutableStateOf<String?>(null) }
    // press and hold a theme: its sheet, the keyboard test-driving it meanwhile
    var sheetFor by remember { mutableStateOf<String?>(null) }
    sheetFor?.let { id -> ThemeSheet(id, state.seed, primary, onUse = { val (_, m) = ThemeChoice.pick(c, id); state.onPick(id); msg = m }) { sheetFor = null } }
    newIn?.let { pack -> NewThemeSheet({ newIn = null }) { kind ->
        val p = pack.ifEmpty { null }
        when (kind) {
            "photo" -> if (p == null) state.onEditPhoto() else state.onNewPhotoIn(p)
            "gradient" -> state.onNewGradientIn(p)
            else -> state.onNewSolidIn(p)
        }
    } }
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
                            // the slot's half of the card, edge to edge (a fixed 162 dp left a gap; user 2026-11-19)
                            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val w = maxWidth.value
                                Preview(id, dark, state.seed, w, w * 128f / 162f, w / 375f, CssShadow(true, 0f, 0f, 0f, 1f, SetColors.surfaceContainerHighest))
                            }
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
                // App colours (user 2026-11-19: "match keyboard | match wallpaper | none", keyboard by default)
                Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DText("App colours", style = setText(16f).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                    DText(
                        when (state.appColours) {
                            "wallpaper" -> "Your wallpaper's colours; light or dark as the system"
                            "none" -> "DogBoard's own colours; light or dark as the system"
                            else -> "Light or dark and colours from the keyboard theme"
                        },
                        style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start), maxLines = 8,
                    )
                    val opts = listOf("keyboard" to "Keyboard", "wallpaper" to "Wallpaper", "none" to "None")
                    Segmented("themes:appcolours", opts.map { it.second }, opts.indexOfFirst { it.first == state.appColours }.coerceAtLeast(0)) { state.onAppColours(opts[it].first) }
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
                        DText("A bright and a dark version, each matched to your photo. Change anything.", style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start), maxLines = 8)
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
                if (sec.id == "everyday" && filter == "all") UserPackSections(state, primary) { newIn = it }
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
                                if (id == null) NewTile("add", "New theme") { newIn = "" }
                                else ThemeTile(id, c, state.seed, primary, onLongPress = { sheetFor = id }) { val (_, m) = ThemeChoice.pick(c, id); state.onPick(id); msg = m }
                            }
                        }
                    }
                }
            }

            // theme packs: one place to order, show or hide, share and import them (user 2026-11-19: the show / hide
            // switches here "seem a bit pointless"; chose a Manage packs page)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DText("Theme packs", Modifier.padding(start = 4.dp, top = 8.dp), style = setText(14f, 500, primary))
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(20.dp)).background(SetColors.row).clickable { state.onNewPack() }.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(SetColors.surfaceContainerHighest), contentAlignment = Alignment.Center) { Symbol("add", 22.sp, primary) }
                    DText("New pack", Modifier.weight(1f), style = setText(16f).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(20.dp)).background(SetColors.row).clickable { state.onManagePacks() }.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(SetColors.surfaceContainerHighest), contentAlignment = Alignment.Center) { Symbol("palette", 22.sp, SetColors.onSurfaceVariant) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        DText("Manage packs", style = setText(16f).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                        DText("Order, show or hide, share, import", style = setText(14f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start), maxLines = 3)
                    }
                    Symbol("chevron_right", 22.sp, SetColors.onSurfaceVariant)
                }
            }
        }
        StatusBarBand()
    }
}

/** :86-108 a theme tile: the keyboard (a pair split diagonally into its night version), check, name, mode, where it is used. */
@Composable
private fun ThemeTile(id: String, c: ThemeChoice.Choice, seed: Int, primary: Color, onLongPress: () -> Unit = {}, onPick: () -> Unit) {
    val t = ThemeChoice.theme(id)
    val where = ThemeChoice.where(c, id)
    val sel = where.isNotEmpty()
    val w = tileW(); val h = tileH(w)
    Column(Modifier.width(w.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(w.dp, h.dp)
                .then(if (sel) Modifier.cssShadow(listOf(CssShadow(false, 0f, 0f, 0f, 3f, SetColors.screen), CssShadow(false, 0f, 0f, 0f, 5f, primary)), RoundedCornerShape(TILE_R)) else Modifier)
                .clip(RoundedCornerShape(TILE_R)).combinedClickable(onLongClick = onLongPress, onClick = onPick),
        ) {
            Preview(id, t.mode == "dark", seed, w, h, w / 375f, null)
            // :90-92 a pair shows its night version in the lower-right triangle
            if (t.mode == "pair") Box(Modifier.size(w.dp, h.dp).clip(GenericShape { s, _ -> moveTo(s.width, 0f); lineTo(s.width, s.height); lineTo(0f, s.height); close() })) {
                Preview(id, true, seed, w, h, w / 375f, null)
            }
            // the outline over the keyboard (drawn under it, a pair's dark half hid it on two edges)
            if (!sel) TileOutline()
            if (sel) InUseBadge(primary, Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp))
        }
        // the name only (user 2026-11-22: no day/night icon, the tile shows it; the description moved into the press-and-hold
        // sheet); a status line only for the theme in use
        DText(t.name, Modifier.padding(horizontal = 2.dp), style = setText(15f, if (sel) 600 else 400).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start), maxLines = 1)
        if (sel) DText(where, Modifier.padding(horizontal = 2.dp),
            style = setText(13f, color = primary).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start, lineHeight = (13 * 1.35f).sp), maxLines = 2)
    }
}

/** Theme tiles' corners (user 2026-11-19: 12 dp, Material's card shape; were the canvas's 14). */
private val TILE_R = 12.dp

/** A tile's 1 dp outline, drawn over its content. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.TileOutline() {
    Box(Modifier.matchParentSize().border(1.dp, SetColors.outlineVariant, RoundedCornerShape(TILE_R)))
}

/**
 * A tile's width: two to a row across the screen, 16 dp margins, 12 dp apart (user 2026-11-19: previews to the
 * screen's width, "even just a little bit bigger provides a better view"; were a fixed 173 dp). Height keeps the
 * canvas's 173 x 137 shape; the keyboard (drawn 375 wide) scales to the width.
 */
@Composable
private fun tileW(): Float = ((androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 32 - 12) / 2f).coerceAtLeast(150f)
private fun tileH(w: Float) = w * 137f / 173f

/** The "in use" badge: a white check on the accent colour, ringed in white so it reads on any theme (was grey). */
@Composable
private fun InUseBadge(primary: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(24.dp).cssShadow(listOf(CssShadow(false, 0f, 0f, 0f, 2f, Color.White)), RoundedCornerShape(12.dp)).background(primary, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Symbol("check", 18.sp, Color.White)
    }
}

/**
 * Press and hold a theme (user 2026-11-22: "tap and hold to test drive a theme + title, description, credits"): the
 * keyboard draws it while this sheet is open (LiveUi.themePreview), a field to try it in, what it is, where it comes from.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSheet(id: String, seed: Int, primary: Color, onUse: () -> Unit, onDismiss: () -> Unit) {
    val t = ThemeChoice.theme(id)
    androidx.compose.runtime.DisposableEffect(id) {
        io.github.sillypuppyy.dogboard.ime.LiveUi.themePreview.value = id
        onDispose { io.github.sillypuppyy.dogboard.ime.LiveUi.themePreview.value = null }
    }
    val from = ThemeChoice.SECTIONS.firstOrNull { id in it.ids }?.let { if (it.pack) "${it.title} pack, built in" else "Built into DogBoard" } ?: "Your theme"
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SetColors.screen) {
        // above the keyboard while trying the theme (the field and the buttons were under it)
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val w = 320f; val h = tileH(w)
            Box(Modifier.align(Alignment.CenterHorizontally).size(w.dp, h.dp).clip(RoundedCornerShape(16.dp))) {
                Preview(id, t.mode == "dark", seed, w, h, w / 375f, null)
                if (t.mode == "pair") Box(Modifier.size(w.dp, h.dp).clip(GenericShape { s, _ -> moveTo(s.width, 0f); lineTo(s.width, s.height); lineTo(0f, s.height); close() })) {
                    Preview(id, true, seed, w, h, w / 375f, null)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DText(t.name, style = setText(24f).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                DText(t.sub, style = setText(15f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
            }
            SetRows {
                for ((icon, text) in listOf(ThemeChoice.MODE.getValue(t.mode).first to ThemeChoice.MODE.getValue(t.mode).second, "info" to from)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(SetColors.row).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Symbol(icon, 20.sp, SetColors.onSurfaceVariant)
                        DText(text, Modifier.weight(1f), style = setText(15f).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                    }
                }
            }
            // the test drive: type here and the keyboard shows this theme
            var tryText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
            androidx.compose.foundation.text.BasicTextField(
                tryText, { tryText = it },
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(SetColors.surfaceContainerHighest).padding(horizontal = 20.dp, vertical = 15.dp),
                textStyle = setText(16f).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start),
                decorationBox = { inner -> Box { if (tryText.isEmpty()) DText("Type here to try it", style = setText(16f, color = SetColors.onSurfaceVariant)); inner() } },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Box(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onDismiss).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    DText("Close", style = setText(14f, 500, primary))
                }
                Box(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(primary).clickable { onUse(); onDismiss() }.padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    DText("Use this theme", style = setText(14f, 500, SetColors.screen))
                }
            }
        }
    }
}

/** New theme: a photo, a gradient or a solid colour (user 2026-11-19), as a bottom sheet in the page's colours. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun NewThemeSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SetColors.screen) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DText("New theme", Modifier.padding(start = 8.dp, bottom = 8.dp), style = setText(22f))
            SetRows {
                for ((kind, icon, title, sub) in listOf(
                    listOf("photo", "add_photo_alternate", "Photo", "Your photo behind the keys, a bright and a dark version"),
                    listOf("gradient", "gradient", "Gradient", "Two or three colours blending across the keyboard"),
                    listOf("solid", "format_color_fill", "Solid colour", "One colour, light and dark"),
                )) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 72.dp).background(SetColors.row).clickable { onDismiss(); onPick(kind) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(SetColors.secondaryContainer), contentAlignment = Alignment.Center) {
                            Symbol(icon, 22.sp, SetColors.onSecondaryContainer)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            DText(title, style = setText(16f))
                            DText(sub, style = setText(14f, color = SetColors.onSurfaceVariant), maxLines = 3)
                        }
                    }
                }
            }
        }
    }
}

/** :93-95 the "add" tile at the end of a section (From a photo, New gradient). */
@Composable
private fun NewTile(icon: String, label: String, onClick: () -> Unit) {
    val w = tileW()
    Column(Modifier.width(w.dp)) {
        Column(
            Modifier.size(w.dp, tileH(w).dp).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1.5f, SetColors.outline)), RoundedCornerShape(TILE_R)).clip(RoundedCornerShape(TILE_R)).clickable(onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Symbol(icon, 28.sp, SetColors.primary)
            DText(label, style = setText(13f, 500, SetColors.primary))
        }
    }
}

/** The keyboard's photo slot as a preview: its photo and the bright or dark version's look. */
@Composable
private fun SlotPreview(dark: Boolean, w: Float, h: Float, scale: Float, ring: CssShadow?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs by dev.patrickgold.florisboard.app.FlorisPreferenceStore
    val stamp by prefs.keyboard.glassPhotoStamp.collectAsState()
    val looks by prefs.keyboard.glassLooks.collectAsState()
    val data by androidx.compose.runtime.produceState<Pair<androidx.compose.ui.graphics.ImageBitmap, io.github.sillypuppyy.dogboard.design.UserThemes.PhotoLook>?>(null, stamp, looks, dark) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val v = io.github.sillypuppyy.dogboard.design.PhotoSlot.versions(context, prefs)
            val useDark = if (v.hasBright && v.hasDark) dark else v.hasDark
            val own = useDark && v.darkPhoto
            val l = v.look(useDark)
            val bmp = runCatching {
                android.graphics.BitmapFactory.decodeFile(io.github.sillypuppyy.dogboard.design.GlassPhoto.file(context, own).absolutePath,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
            }.getOrNull() ?: return@withContext null
            val a = io.github.sillypuppyy.dogboard.design.PhotoReadability.accent(l, io.github.sillypuppyy.dogboard.design.GlassPhoto.analysis(context, own)?.accents)
            bmp to io.github.sillypuppyy.dogboard.design.UserThemes.PhotoLook(l.dim.toFloat(), l.tint.toFloat(),
                if (own) v.darkLuminance.toFloat() else prefs.keyboard.glassPhotoLuminance.get() / 1000f, l.labels, l.halo, a?.first, a?.second,
                prefs.keyboard.glassPhotoPos.get(), l.wash.toFloat(), l.sat.toFloat(), l.contrast.toFloat(), l.keyDark)
        }
    }
    Box(Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(TILE_R)).then(if (ring != null) Modifier.cssShadow(listOf(ring), RoundedCornerShape(TILE_R)) else Modifier)) {
        data?.let { (img, l) -> PhotoPreview(img, l, "Photo", w, scale) }
    }
}

/** A saved photo theme's tile: its photo, dimmed, and the keyboard over it as the theme sets it (162 x 128). */
@Composable
private fun PhotoPreview(img: androidx.compose.ui.graphics.ImageBitmap, look: io.github.sillypuppyy.dogboard.design.UserThemes.PhotoLook?, name: String, width: Float = 173f, scale: Float = 0.4613f) {
    val l = look ?: io.github.sillypuppyy.dogboard.design.UserThemes.PhotoLook(0.35f, 0.3f, 0.3f, "auto", true, null, null, "center")
    val render = remember(l) {
        KbSpec.render(KbProps(theme = "glass", width = 375f, bar = "bal", ret = "send", dim = l.dim, wash = l.wash, keyDark = l.keyDark, keyOpacity = l.keyOpacity, photoLuminance = l.luminance,
            glassFg = l.labels, halo = l.halo, actColor = l.actColor, actFg = l.actFg, backdrop = false))
    }

    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(TILE_R))) {
        // the photo behind the keyboard's height only, as the keyboard crops it
        Box(Modifier.size(width.dp, (render.H * scale).dp)) {
            androidx.compose.foundation.Image(img, name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop, colorFilter = io.github.sillypuppyy.dogboard.design.photoFilter(l.sat, l.contrast),
                alignment = when (l.pos) { "center top" -> Alignment.TopCenter; "center bottom" -> Alignment.BottomCenter; else -> Alignment.Center })
            Box(Modifier.fillMaxSize().background(Color(0f, 0f, 0f, l.dim)))
            if (l.wash > 0f) Box(Modifier.fillMaxSize().background(Color(1f, 1f, 1f, l.wash)))
            Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }) {
                KbView(render)
            }
        }
    }
}

/** A keyboard preview: the theme drawn at 375 wide, scaled into a [w] x [h] box. */
@Composable
private fun Preview(id: String, dark: Boolean, seed: Int, w: Float, h: Float, scale: Float, ring: CssShadow?) {
    // the Photo theme shows the user's photo and the version it draws (it showed the design's stand-in photo)
    if (id == "glass") { SlotPreview(dark, w, h, scale, ring); return }
    val render = remember(id, dark, seed) { KbSpec.render(KbProps(theme = id, dark = dark, width = 375f, bar = "bal", ret = "send", spotSeed = seed)) }
    Box(Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(TILE_R)).then(if (ring != null) Modifier.cssShadow(listOf(ring), RoundedCornerShape(TILE_R)) else Modifier)) {
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
private fun UserPackSections(state: SetThemesState, primary: Color, onNew: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    for ((pi, pack) in state.userPacks.withIndex()) {
        if (pack.hidden) continue
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    DText(pack.name, style = setText(14f, 500, primary).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                    DText((if (pack.shared) "Shared with you, " else "Your pack, ") + "${pack.themes.size} ${if (pack.themes.size == 1) "theme" else "themes"}", style = setText(12f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                }
                var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                Box {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable { open = true }, contentAlignment = Alignment.Center) {
                        Symbol("more_vert", 22.sp, SetColors.onSurfaceVariant)
                    }
                    // a shared pack is read-only: copy it to change it (user 2026-11-19)
                    val items = if (pack.shared) listOf("copy" to "Copy to my themes", "share" to "Share pack", "hide" to "Hide", "delete" to "Remove pack")
                    else listOfNotNull("rename" to "Rename", ("up" to "Move up").takeIf { pi > 0 },
                        ("down" to "Move down").takeIf { pi < state.userPacks.size - 1 }, "share" to "Share pack", "hide" to "Hide", "delete" to "Delete pack")
                    SetMenu(open, { open = false }, items.map { (a, label) -> label to { state.onPackAction(pack.id, a) } })
                }
            }
            // the pack's themes, then its two "new" tiles
            val cells: List<Any> = pack.themes + listOf("new")
            for (row in cells.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (cell in row) {
                        if (cell == "new") { NewTile("add", "New theme") { onNew(pack.id) }; continue }
                        val t = cell as io.github.sillypuppyy.dogboard.design.UserThemes.Theme
                        val sel = if (t.spec != null) t.spec in state.inUse else state.userTheme == t.id
                        // a photo tile: the photo and its look (as saved; the version counter reloads it after an edit)
                        val thumb by androidx.compose.runtime.produceState<Pair<List<androidx.compose.ui.graphics.ImageBitmap?>, io.github.sillypuppyy.dogboard.design.UserThemes.PhotoTile?>?>(null, t.id, state.userPacks) {
                            if (t.spec == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                fun bmp(f: java.io.File) = runCatching {
                                    val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                                    android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)?.asImageBitmap()
                                }.getOrNull()
                                val main = io.github.sillypuppyy.dogboard.design.UserThemes.photo(context, t.id)
                                listOf(bmp(main), bmp(java.io.File(main.parentFile, "photo-dark.jpg"))) to io.github.sillypuppyy.dogboard.design.UserThemes.photoTile(context, t.id)
                            }
                        }
                        var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                        val tw = tileW()
                        Box {
                            Column(
                                Modifier.width(tw.dp).combinedClickable(onLongClick = { menu = true }) { if (sel) state.onEditUser(t.id) else state.onPickUser(t.id) },
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    // the built-in tile's size, selection ring and corners (the saved tiles were 162 x 128 with a
                                    // thicker inner frame, out of line with the "new" tiles beside them; user 2026-11-19 borders)
                                    Modifier.size(tw.dp, tileH(tw).dp)
                                        .then(if (sel) Modifier.cssShadow(listOf(CssShadow(false, 0f, 0f, 0f, 3f, SetColors.screen), CssShadow(false, 0f, 0f, 0f, 5f, primary)), RoundedCornerShape(TILE_R)) else Modifier)
                                        .clip(RoundedCornerShape(TILE_R)).background(SetColors.surfaceContainerHighest),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    // a gradient shows its keyboard, a photo its picture
                                    if (t.spec != null) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(TILE_R))) {
                                        Preview(t.spec, io.github.sillypuppyy.dogboard.design.GradientSpec.parse(t.spec)?.white == true, 0, tw, tileH(tw), tw / 375f, null)
                                        // a bright-and-dark gradient: its dark version in the lower-right triangle
                                        if (io.github.sillypuppyy.dogboard.design.GradientSpec.isPair(t.spec)) Box(Modifier.fillMaxSize().clip(GenericShape { s, _ -> moveTo(s.width, 0f); lineTo(s.width, s.height); lineTo(0f, s.height); close() })) {
                                            Preview(t.spec, true, 0, tw, tileH(tw), tw / 375f, null)
                                        }
                                    }
                                    else thumb?.let { (imgs, tile) ->
                                        val vs = tile?.versions.orEmpty()
                                        val first = vs.firstOrNull()
                                        imgs[0]?.let { PhotoPreview(if (first?.second == true) imgs[1] ?: it else it, first?.first, t.name, tw, tw / 375f) }
                                        // bright and dark: the dark version in the lower-right triangle, as the built-in pairs
                                        if (vs.size == 2) Box(Modifier.fillMaxSize().clip(GenericShape { s, _ -> moveTo(s.width, 0f); lineTo(s.width, s.height); lineTo(0f, s.height); close() })) {
                                            (if (vs[1].second) imgs[1] else imgs[0])?.let { PhotoPreview(it, vs[1].first, t.name, tw, tw / 375f) }
                                        }
                                    }
                                    if (!sel) TileOutline()
                                    if (sel) InUseBadge(primary, Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp))
                                }
                                Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    DText(t.name, Modifier.weight(1f), style = setText(15f, 600).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                                    if (sel) Symbol("edit", 18.sp, SetColors.onSurfaceVariant)
                                }
                                DText(if (sel) "In use. Tap to edit" else if (t.spec != null) "Gradient. Hold for more" else t.credit?.let { "Photo: $it" } ?: "Photo. Hold for more", Modifier.fillMaxWidth().padding(horizontal = 2.dp), style = setText(13f, color = if (sel) primary else SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start, lineHeight = (13 * 1.35f).sp), maxLines = 3)
                            }
                            SetMenu(menu, { menu = false }, if (pack.shared) listOf("Copy to my themes" to { state.onThemeAction(t.id, "copy") }) else listOf<Pair<String, () -> Unit>>("Rename" to { state.onThemeAction(t.id, "rename") }) +
                                state.userPacks.filter { it.id != pack.id }.map { other -> "Move to ${other.name}" to { state.onThemeAction(t.id, "move:${other.id}") } } +
                                listOf("Delete" to { state.onThemeAction(t.id, "delete") }))

                        }
                    }
                }
            }
        }
    }
    // "New pack" lives with Manage packs at the end of the page (user 2026-11-22: a button before Built in was confusing)
}
