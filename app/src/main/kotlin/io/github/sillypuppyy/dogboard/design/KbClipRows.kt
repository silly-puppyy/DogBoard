package io.github.sillypuppyy.dogboard.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The clipboard as rows (canvas "Clipboard: columns vs rows", Final page, 2026-11-20; port map
 * notes/design/port-map-clipboard.md, decisions notes/design/clipboard-rows.md). One clip per row in
 * one group of rows, newest first with the pinned on top; the right-hand controls in one group 8 dp apart.
 */
data class KbClipRow(
    val title: String,
    val sub: String = "",
    /** P:127: two lines for long text and link titles. */
    val titleLines: Int = 1,
    val titleWeight: Int = 400,
    /** Codes 1 sp, a hidden password 2 sp (C: letter-spacing). */
    val titleSpacing: Float = 0f,
    /** P:151-153: a 32 dp circle with this icon (password, pin, mail, call, location_on, draft). */
    val leadIcon: String = "",
    /** A link's initial in the lead circle (previews off). */
    val leadLetter: String = "",
    /** The site's icon in the lead circle (previews on; the initial shows until it loads). */
    val leadImage: (suspend (sizePx: Int) -> ImageBitmap?)? = null,
    /** P:166: a colour's swatch. */
    val swatch: Color? = null,
    /** P:163-165: an 80 x 45 thumbnail (a link's banner or a video link's frame), with [duration] on it. */
    val thumb: Boolean = false,
    val duration: String = "",
    /** P:157-161: the phone's own photo or video fills the row, label and pin on chips. */
    val window: Boolean = false,
    val video: Boolean = false,
    val image: (suspend (sizePx: Int) -> ImageBitmap?)? = null,
    /** Stand-in fill while the image loads, and in the gallery (the canvas's drawn photos). */
    val placeholder: Brush? = null,
    /** Link marks: null = the step didn't change the link; true = done (tap to undo); false = undone (tap to redo). */
    val expanded: Boolean? = null,
    val cleaned: Boolean? = null,
    /** P:62-67: a colour's formats, the picked one filled. */
    val formats: List<String> = emptyList(),
    val format: Int = 0,
    /** A password's eye (C: "eye to show"). */
    val reveal: Boolean = false,
    val pinned: Boolean = false,
    /** H:38: the hold menu's item for this kind (Open link, Open in Maps, Call, Send email, Show), or "". */
    val action: String = "",
    val actionIcon: String = "",
    /** The hold menu's "Paste as file" (the app takes this kind of file) and "Share as file" (settings). */
    val pasteFile: Boolean = false,
    val shareFile: Boolean = false,
)

/** Taps on a row's parts, by the row's position in the list. */
class KbClipActions(
    val onPaste: (Int) -> Unit = {},
    val onPin: (Int) -> Unit = {},
    /** "expanded" or "cleaned": undo or redo that step for this clip. */
    val onMark: (Int, String) -> Unit = { _, _ -> },
    val onFormat: (Int, Int) -> Unit = { _, _ -> },
    val onReveal: (Int) -> Unit = {},
    /** H:31-36: "paste", "pastefile", "action", "edit", "share", "sharefile", "delete". */
    val onMenu: (Int, String) -> Unit = { _, _ -> },
    /** The hold menu's Edit (an editor above the keyboard); hidden while there is none. */
    val canEdit: Boolean = true,
)

val LocalKbClipActions = androidx.compose.runtime.staticCompositionLocalOf<KbClipActions?> { null }

private const val ROW_H = 60
private val PILL = RoundedCornerShape(50)

/** P:126: one group: 12 dp on its outside, 4 dp where rows meet. */
private fun rowShape(i: Int, n: Int) = RoundedCornerShape(
    topStart = if (i == 0) 12.dp else 4.dp, topEnd = if (i == 0) 12.dp else 4.dp,
    bottomStart = if (i == n - 1) 12.dp else 4.dp, bottomEnd = if (i == n - 1) 12.dp else 4.dp,
)

private fun text(size: Int, weight: Int, color: Color) = designTextStyle(size.sp, weight, color)

/** P:30, P:182: the list, rows 2 dp apart, 8 dp from the sides; the hold menu opens under a held row (H). */
@Composable
fun KbClipList(t: KbTokens, rows: List<KbClipRow>, modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState(), initialHeld: Int = -1) {
    var held by remember(initialHeld) { mutableStateOf(initialHeld) }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(), state = state,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(rows) { i, r ->
                ClipRow(t, r, i, rows.size, state, held == i) { held = if (held == i) -1 else i }
            }
        }
        // H:30: the menu under the held row, over the list (it is taller than the list's rows); moved up as
        // Material menus are when it would leave the panel. A tap outside it closes it.
        rows.getOrNull(held)?.let { r ->
            var menuH by remember { mutableStateOf(0) }
            val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == held }
            val maxH = constraints.maxHeight
            val y = ((item?.let { it.offset + it.size } ?: 0)).coerceAtMost(maxH - menuH).coerceAtLeast(0)
            Box(Modifier.matchParentSize().tap({ held = -1 }))
            Box(
                Modifier.fillMaxWidth().offset { androidx.compose.ui.unit.IntOffset(0, y) }
                    .onSizeChanged { menuH = it.height }.padding(end = 8.dp),
                contentAlignment = Alignment.TopEnd,
            ) { HoldMenu(t, r, held) { held = -1 } }
        }
    }
}

@Composable
private fun ClipRow(t: KbTokens, r: KbClipRow, i: Int, n: Int, list: LazyListState, held: Boolean, onHold: () -> Unit) {
    val actions = LocalKbClipActions.current
    val shape = rowShape(i, n)
    val density = LocalDensity.current
    val img by produceState<ImageBitmap?>(null, r.image) {
        value = r.image?.let { load -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { load(with(density) { (if (r.window) 400 else 80).dp.roundToPx() }) } }
    }
    // clipboard-rows.md: a picture too tall, too wide or too small for a window falls back to a thumbnail
    val window = r.window && img.let { b -> b == null || (b.width.toFloat() / b.height in 0.5f..3f) }
    val id = "cliprow:$i"
    Box(
        Modifier.fillMaxWidth().height(ROW_H.dp).clip(shape)
            .background(if (held) t.pressKey else t.key)
            .tap({ actions?.onPaste?.invoke(i) }, onHold).lint(id),
    ) { LintParent(id) {
        if (window) WindowImage(r, img, list, i)
        Row(
            // P:173: gap 12, padding 12 / 8 (6 before a thumbnail, 0 + the label's 12 on a window)
            Modifier.matchParentSize().padding(start = if (r.thumb && !window) 6.dp else 12.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                window -> {}
                r.thumb || r.window -> Thumb(r, img)
                r.swatch != null -> Box(Modifier.size(28.dp).background(r.swatch, RoundedCornerShape(8.dp)))
                r.leadIcon.isNotEmpty() || r.leadLetter.isNotEmpty() -> {
                    val icon by produceState<ImageBitmap?>(null, r.leadImage) {
                        value = r.leadImage?.let { load -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { load(with(density) { 32.dp.roundToPx() }) } }
                    }
                    val i = icon
                    // the site's icon fills the whole 32 dp lead, 8 dp corners (it sat small in a circle;
                    // the canvas had 18 dp inside the pill); the pill with a symbol or letter until it loads
                    if (i != null) Image(i, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                    else Box(Modifier.size(32.dp).background(t.fn, PILL), contentAlignment = Alignment.Center) {
                        if (r.leadIcon.isNotEmpty()) Symbol(r.leadIcon, 18.sp, t.fnFg, weight = 500)
                        else DText(r.leadLetter, style = text(14, 600, t.fnFg))
                    }
                }
            }
            Box(Modifier.weight(1f)) { Label(t, r, window) }
            Controls(t, r, i, window)
        }
    } }
}

/**
 * P:157-158: the image fills the row's width at its own height, and the row is a window onto it: as the row travels
 * up the list the visible slice moves through the whole picture, from its bottom (row at the list's bottom) to its
 * top ("the image segment ... scroll with the user's scrolling"; the first version slid at a third
 * of the scroll inside a 1.4x zoom, about 11 dp of travel, which read as still).
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.WindowImage(r: KbClipRow, img: ImageBitmap?, list: LazyListState, i: Int) {
    val m = Modifier.matchParentSize()
    if (img == null) r.placeholder?.let { Box(m.background(it)) }
    else {
        var rowH by remember { mutableStateOf(0) }
        Box(m.clip(RoundedCornerShape(0.dp)).onSizeChanged { rowH = it.height }) {
            Image(
                img, contentDescription = null, contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer {
                    val info = list.layoutInfo
                    val item = info.visibleItemsInfo.firstOrNull { it.index == i } ?: return@graphicsLayer
                    // where the row sits in the list: 0 at the top, 1 at the bottom
                    val travel = (info.viewportEndOffset - info.viewportStartOffset - item.size).toFloat()
                    val frac = if (travel <= 0f) 0.5f else ((item.offset - info.viewportStartOffset) / travel).coerceIn(0f, 1f)
                    val overflow = (size.height - rowH).coerceAtLeast(0f)
                    translationY = -overflow * frac
                },
            )
        }
    }
    // P:159: the scrim
    Box(m.background(Brush.horizontalGradient(0f to Color(0x73000000), 0.55f to Color(0x00000000), 1f to Color(0x59000000))))
}

@Composable
private fun Thumb(r: KbClipRow, img: ImageBitmap?) {
    // P:154: h = min(45, row - 12), w = h * 16 / 9
    val h = minOf(45, ROW_H - 12); val w = Math.round(h * 16 / 9f)
    Box(Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(8.dp)).then(r.placeholder?.let { Modifier.background(it) } ?: Modifier)) {
        img?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
        if (r.duration.isNotEmpty()) {
            // P:39
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 3.dp).background(Color(0xB3000000), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp)) {
                DText(r.duration, style = text(10, 500, Color.White))
            }
        }
    }
}

/** P:48-60: title and sub-line; on a window, on an opaque chip in the key colour (P:161, 142). */
@Composable
private fun Label(t: KbTokens, r: KbClipRow, window: Boolean) {
    val titleStyle = text(14, r.titleWeight, t.keyFg).copy(lineHeight = (14 * 1.3).sp, letterSpacing = r.titleSpacing.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Start)
    val col: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            DText(r.title, style = titleStyle, maxLines = r.titleLines, overflow = TextOverflow.Ellipsis)
            if (r.sub.isNotEmpty()) DText(r.sub, style = text(12, 400, t.hint).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (!window) { col(); return }
    Row(
        Modifier.background(t.key, RoundedCornerShape(12.dp)).padding(start = if (r.video) 6.dp else 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (r.video) Symbol("play_circle", 20.sp, t.keyFg, fill = 1, weight = 500)
        col()
    }
}

/**
 * P:61-85: every control on the right in one row of 36 dp slots: the 28 dp circles sit 8 dp apart and
 * each slot is the row's full height to touch (the colour chips get 4 dp on each side, the same 8 dp).
 */
@Composable
private fun RowScope.Controls(t: KbTokens, r: KbClipRow, i: Int, window: Boolean) {
    val actions = LocalKbClipActions.current
    val circle = if (window) t.key else t.fn
    // An unpinned pin is a bare icon in an unfilled circle: after filled marks or chips its gap looks 5 dp wider
    // than theirs (8 dp + the circle's empty 5 dp), so they move 5 dp towards it; the pin stays in line with
    // the other rows' pins (equal, but not optically)
    val bare = !r.pinned && !window && (r.expanded != null || r.cleaned != null || r.formats.isNotEmpty() || r.reveal)
    Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
      Row(Modifier.fillMaxHeight().offset(x = if (bare) 5.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        r.formats.forEachIndexed { k, f ->
            val on = k == r.format
            Box(Modifier.fillMaxHeight().tap({ actions?.onFormat?.invoke(i, k) }).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                // P:130
                Box(
                    Modifier.height(28.dp).then(if (on) Modifier.background(t.fn, PILL) else Modifier.border(1.dp, t.hint, PILL)).padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center,
                ) { DText(f, style = text(12, 500, if (on) t.fnFg else t.barMuted)) }
            }
        }
        if (r.reveal) Slot("Show the password", { actions?.onReveal?.invoke(i) }) { Mark(null, t, "visibility", false) }
        r.expanded?.let { on ->
            Slot(if (on) "Expanded from a short link. Tap to undo" else "Short link kept. Tap to expand", { actions?.onMark?.invoke(i, "expanded") }) {
                Mark(if (on) circle else null, t, "open_in_full", false, ring = !on)
            }
        }
        r.cleaned?.let { on ->
            Slot(if (on) "Trackers removed. Tap to undo" else "Trackers kept. Tap to remove", { actions?.onMark?.invoke(i, "cleaned") }) {
                Mark(if (on) circle else null, t, "bug_report", on, ring = !on)
            }
        }
      }
        // P:145-149
        Slot(if (r.pinned) "Pinned. Tap to unpin" else "Pin", { actions?.onPin?.invoke(i) }) {
            Box(Modifier.size(28.dp).background(if (r.pinned) t.act else if (window) t.key else Color.Transparent, PILL), contentAlignment = Alignment.Center) {
                Symbol("keep", 18.sp, if (r.pinned) t.actFg else t.hint, fill = if (r.pinned) 1 else 0, weight = 500)
            }
        }
    }
}

@Composable
private fun Slot(label: String, onTap: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.width(36.dp).fillMaxHeight().semantics { contentDescription = label }.tap(onTap), contentAlignment = Alignment.Center) { content() }
}

/** P:136-139: a 28 dp mark; [bg] null = no fill (undone: a 1 dp ring); [slash] = the trackers' crossed bug (P:138). */
@Composable
private fun Mark(bg: Color?, t: KbTokens, icon: String, slash: Boolean, ring: Boolean = false) {
    val fg = if (bg == null && ring) t.hint else t.fnFg
    val behind = bg ?: t.key
    Box(
        Modifier.size(28.dp).then(if (bg != null) Modifier.background(bg, PILL) else Modifier).then(if (ring) Modifier.border(1.dp, t.hint, PILL) else Modifier)
            .drawWithContent {
                drawContent()
                if (!slash) return@drawWithContent
                // 16 x 1.25 dp, rotated -45 degrees about the circle's centre, with a 1 dp ring in the circle's colour
                val half = 8.dp.toPx(); val a = Math.toRadians(-45.0)
                val c = Offset(size.width / 2, size.height / 2)
                val d = Offset((cos(a) * half).toFloat(), (sin(a) * half).toFloat())
                drawLine(behind, c - d, c + d, strokeWidth = 1.25.dp.toPx() + 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(fg, c - d, c + d, strokeWidth = 1.25.dp.toPx(), cap = StrokeCap.Round)
            },
        contentAlignment = Alignment.Center,
    ) { Symbol(icon, 18.sp, fg, weight = 500) }
}

/** H:30-37: a Material menu under the held row, right-aligned 8 dp; Delete in the error colour. */
@Composable
private fun HoldMenu(t: KbTokens, r: KbClipRow, i: Int, close: () -> Unit) {
    val actions = LocalKbClipActions.current
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
        Column(
            Modifier.width(232.dp)
                .cssShadow(CssShadow.parse("0 2px 6px rgba(0,0,0,0.15), 0 8px 24px rgba(0,0,0,0.12)"), shape)
                .background(t.pop, shape).padding(vertical = 8.dp),
        ) {
            @Composable fun item(icon: String, label: String, what: String, fg: Color = t.popFg) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp).tap({ close(); actions?.onMenu?.invoke(i, what) }).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol(icon, 20.sp, fg, weight = 500)
                    DText(label, style = text(14, 400, fg))
                }
            }
            item("content_paste", "Paste", "paste")
            if (r.pasteFile) item("attach_file", "Paste as file", "pastefile")
            if (r.action.isNotEmpty()) item(r.actionIcon, r.action, "action")
            if (actions?.canEdit != false) item("edit", "Edit", "edit")
            item("share", "Share", "share")
            if (r.shareFile) item("draft", "Share as file", "sharefile")
            Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(t.div))
            item("delete", "Delete", "delete", if (t.pop.luminance() > 0.5f) Color(0xFF93000A) else Color(0xFFFFB4AB))
        }
    }
}

/** The canvas's rows (P:155-171, then the catalogue's other kinds, C), for the gallery and the design's own frames. */
fun demoClipRows(): List<KbClipRow> {
    val sunset = Brush.verticalGradient(
        0f to Color(0xFF6FA8DC), 0.30f to Color(0xFFB9D7EE), 0.44f to Color(0xFFF6C98A), 0.50f to Color(0xFFE98C5F),
        0.52f to Color(0xFF5B3A52), 0.70f to Color(0xFF3B2A40), 0.72f to Color(0xFF2A2F2A), 1f to Color(0xFF1C241D),
    )
    val fridge = Brush.verticalGradient(0f to Color(0xFF8A9BB0), 0.45f to Color(0xFFC9CED6), 0.46f to Color(0xFF6D5A4A), 1f to Color(0xFF3A2F28))
    return listOf(
        KbClipRow("221 Baker Street, London NW1 6XE", "Address", leadIcon = "location_on", pinned = true, action = "Open in Maps", actionIcon = "map"),
        KbClipRow("Photo", window = true, placeholder = sunset),
        KbClipRow("Dog learns to open the fridge by himself", "shortvideo.example", titleLines = 2, thumb = true, duration = "0:42", placeholder = fridge,
            expanded = true, cleaned = true, action = "Open link", actionIcon = "open_in_new", pasteFile = true, shareFile = true),
        KbClipRow("#3D7BF5", "Colour", titleWeight = 500, swatch = Color(0xFF3D7BF5), formats = listOf("HEX", "RGB", "HSL")),
        KbClipRow("export.bin", "some/path, 2.4 MB", titleWeight = 500, leadIcon = "draft"),
        // the catalogue's "pin" icon for a code (the Panel frame drew "password", which the catalogue gives passwords)
        KbClipRow("482 913", "One-time code, gone in 4 min", titleWeight = 500, titleSpacing = 1f, leadIcon = "pin"),
        KbClipRow("Meeting moved to Thursday. Bring the slides from last week and the budget sheet.", titleLines = 2),
        // C: the catalogue's other kinds
        KbClipRow("Night trains are coming back", "news.example", leadLetter = "n", cleaned = true, action = "Open link", actionIcon = "open_in_new"),
        KbClipRow("Video", "0:42, 12 MB", window = true, video = true, placeholder = Brush.linearGradient(listOf(Color(0xFF3F6E8C), Color(0xFF9CCFA6)))),
        KbClipRow("\u2022".repeat(10), "Password, cleared in 1 min", titleSpacing = 2f, leadIcon = "password", reveal = true, action = "Show", actionIcon = "visibility"),
        KbClipRow("sam@example.com", "Email address", leadIcon = "mail", action = "Send email", actionIcon = "mail"),
        KbClipRow("+44 20 7946 0958", "Phone number", leadIcon = "call", action = "Call", actionIcon = "call"),
        KbClipRow("See you at 7!"),
    )
}

/**
 * A live clip as a row (catalogue kinds, C). Links: the site's initial and a page name read from the
 * address (previews, which need the site, are off: clipboard-rows.md). Not yet live: the password's eye
 * (the clip is stored masked here), one-time codes' expiry, files (no clip kind yet).
 */
fun clipRow(d: KbClipData): KbClipRow = clipRowKind(d).copy(pasteFile = d.pasteFile, shareFile = d.shareFile)

private fun clipRowKind(d: KbClipData): KbClipRow {
    val s = d.text.trim()
    return when {
        d.media -> if (d.icon == "play_circle") KbClipRow("Video", d.label.takeIf { it != "Video" }.orEmpty(), window = true, video = true, image = d.thumbnail, pinned = d.pinned)
            else KbClipRow("Photo", window = true, image = d.thumbnail, pinned = d.pinned)
        d.sensitive -> KbClipRow(s, d.clearsInSec?.let { "Password, cleared in " + if (it >= 60) "${(it + 59) / 60} min" else "$it s" } ?: "Password",
            titleSpacing = 2f, leadIcon = "password", pinned = d.pinned)
        else -> io.github.sillypuppyy.dogboard.ime.ClipKinds.color(s)?.let { c ->
            val formats = listOf("HEX", "RGB", "HSL")
            val copied = s.lowercase().let { if (it.startsWith("rgb")) 1 else if (it.startsWith("hsl")) 2 else 0 }
            KbClipRow(s, "Colour", titleWeight = 500, swatch = Color(c.argb or 0xFF000000), formats = formats, format = copied, pinned = d.pinned)
        } ?: LINK.matchEntire(s)?.let { m ->
            val host = m.groupValues[2].lowercase()
            val title = d.linkTitle ?: pageName(m.groupValues[3]) ?: (host + m.groupValues[3].trimEnd('/'))
            val sub = d.linkSite ?: host
            // C: with a banner (or a video's frame) the thumbnail leads, else the site's icon (or initial)
            if (d.linkImage != null) KbClipRow(title, sub, titleLines = 2, thumb = true, image = d.linkImage, duration = d.linkDuration,
                expanded = d.expanded, cleaned = d.cleaned, pinned = d.pinned, action = "Open link", actionIcon = "open_in_new")
            else KbClipRow(title, sub, leadLetter = host.take(1), leadImage = d.linkIcon,
                expanded = d.expanded, cleaned = d.cleaned, pinned = d.pinned, action = "Open link", actionIcon = "open_in_new")
        } ?: when {
            // a type only where it is certain: an email by its form; phone numbers, addresses
            // and one-time codes only when Android's classifier gives the whole clip that type (ClipTypes)
            EMAIL.matches(s) -> KbClipRow(s, "Email address", leadIcon = "mail", pinned = d.pinned, action = "Send email", actionIcon = "mail")
            d.systemType == "otp" -> KbClipRow(s, "One-time code", titleWeight = 500, titleSpacing = 1f, leadIcon = "pin", pinned = d.pinned)
            d.systemType == "phone" -> KbClipRow(s, "Phone number", leadIcon = "call", pinned = d.pinned, action = "Call", actionIcon = "call")
            d.systemType == "address" -> KbClipRow(s.replace(Regex("\\s+"), " "), "Address", leadIcon = "location_on", pinned = d.pinned, action = "Open in Maps", actionIcon = "map")
            else -> KbClipRow(s.replace(Regex("\\s+"), " "), titleLines = 2, pinned = d.pinned)
        }
    }
}

/** "/recipes/lemon-drizzle-cake.html" -> "Lemon drizzle cake"; null when the address has no readable name. */
internal fun pageName(path: String): String? {
    val seg = path.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        .substringBeforeLast('.').replace(Regex("[-_+]+"), " ").trim()
    // a slug of words ("night-trains-are-coming-back"); one word is a path part ("watch", "status"), not a name
    if (seg.count { it.isLetter() } < 4 || ' ' !in seg) return null
    return seg.replaceFirstChar { it.uppercase() }
}

private val LINK = Regex("(?i)(https?://)?(?:www\\.)?((?:[a-z0-9-]+\\.)+[a-z]{2,})(/\\S*)?")
private val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[A-Za-z]{2,}")
