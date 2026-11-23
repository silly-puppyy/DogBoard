package io.github.sillypuppyy.dogboard.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import io.github.sillypuppyy.dogboard.design.CssShadow
import io.github.sillypuppyy.dogboard.design.DText
import io.github.sillypuppyy.dogboard.design.KbProps
import io.github.sillypuppyy.dogboard.design.KbSpec
import io.github.sillypuppyy.dogboard.design.KbView
import io.github.sillypuppyy.dogboard.design.Symbol
import io.github.sillypuppyy.dogboard.design.ThemeChoice
import io.github.sillypuppyy.dogboard.design.ThemeModel
import io.github.sillypuppyy.dogboard.design.ThemeModel.Bg
import io.github.sillypuppyy.dogboard.design.ThemeModel.Stop
import io.github.sillypuppyy.dogboard.design.ThemeModel.Version
import io.github.sillypuppyy.dogboard.design.UserThemes
import io.github.sillypuppyy.dogboard.design.css
import io.github.sillypuppyy.dogboard.design.cssShadow
import io.github.sillypuppyy.dogboard.design.settings.Segmented
import io.github.sillypuppyy.dogboard.design.settings.SetColors
import io.github.sillypuppyy.dogboard.design.settings.SetSliderRow
import io.github.sillypuppyy.dogboard.design.settings.setText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The theme editor (design ThemeEditor.dc.html, notes/design/port-map-theme-editor.md): one editor for every
 * background. The name and Save; the versions (bright, dark or both, and which one is being edited); the theme's
 * keyboard, live, with a field to type on it; how well the labels read; then Background, Elements and Details, or the
 * colour picker in their place while a colour is being picked. [id]: a saved editor theme to change; [start]: a built-in
 * theme to copy ("copy:<id>"), "solid" or "gradient" for a new one.
 */
@Composable
fun ThemeEditorScreen(id: String, start: String, pack: String) {
    val prefs by FlorisPreferenceStore
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    io.github.sillypuppyy.dogboard.design.MaterialYouTheme.Remember()

    var t by remember { mutableStateOf(ThemeModel.Theme("Colour 1", "light", Version(Bg(color = "#ededf4")))) }
    var edit by remember { mutableStateOf("light") }
    var oldSpec by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf("el") }
    var picker by remember { mutableStateOf<Target?>(null) }
    var pk by remember { mutableStateOf(ThemeModel.Hsv(0.0, 0.0, 0.5)) }
    var recent by remember { mutableStateOf(listOf<String>()) }
    var sel by remember { mutableIntStateOf(1) }

    LaunchedEffect(id, start) {
        withContext(Dispatchers.IO) {
            val saved = id.takeIf { it.isNotEmpty() }?.let { UserThemes.theme(context, it) }
            val te = saved?.spec?.let { ThemeModel.decode(it) }
            t = when {
                te != null -> { oldSpec = saved.spec; te.copy(name = saved.name) }
                start.startsWith("copy:") -> {
                    val b = start.removePrefix("copy:")
                    ThemeModel.fromBuiltIn("${io.github.sillypuppyy.dogboard.design.KbThemes.nameOf(b)} copy", io.github.sillypuppyy.dogboard.design.KbThemes.raw(b, false), io.github.sillypuppyy.dogboard.design.KbThemes.raw(b, true))
                }
                start == "gradient" -> ThemeModel.Theme("Gradient ${UserThemes.gradientCount(context) + 1}", "light", Version(Bg(type = "gradient")))
                else -> ThemeModel.Theme("Colour ${UserThemes.gradientCount(context) + 1}", "light", Version(Bg(color = "#ededf4")))
            }
            edit = if (t.versions == "dark") "dark" else "light"
        }
    }

    val v = (if (edit == "dark") t.dark else t.light) ?: t.version(edit == "dark")
    fun setV(f: (Version) -> Version) { t = if (edit == "dark") t.copy(dark = f(v)) else t.copy(light = f(v)) }
    val res = ThemeModel.resolve(v)
    val both = t.versions == "both"
    val verName = if (edit == "dark") "Dark" else "Bright"

    fun targetValue(tg: Target): String = when (tg) {
        is Target.Role -> res.vals.getValue(tg.key).let { if (it == "none") res.vals[tg.key.replace("Line", "Fg")] ?: "#888888" else it }
        Target.Background -> v.bg.color
        is Target.StopAt -> v.bg.stops.getOrNull(tg.i)?.c ?: "#888888"
    }
    fun apply(tg: Target, hex: String) = when (tg) {
        is Target.Role -> setV { it.copy(ov = it.ov + (tg.key to hex)) }
        Target.Background -> setV { it.copy(bg = it.bg.copy(color = hex)) }
        is Target.StopAt -> setV { it.copy(bg = it.bg.copy(stops = it.bg.stops.mapIndexed { i, s -> if (i == tg.i) s.copy(c = hex) else s })) }
    }
    fun open(tg: Target) { picker = tg; pk = ThemeModel.toHsv(targetValue(tg)) }
    fun pickerSet(k: ThemeModel.Hsv) { pk = k; picker?.let { apply(it, ThemeModel.fromHsv(k)) } }
    fun closePicker() {
        picker?.let { tg -> val c = ThemeModel.fromHsv(pk.copy(a = 1.0)); recent = (listOf(c) + recent.filter { it != c }).take(8); tg.toString() }
        picker = null
    }

    fun save() {
        val spec = ThemeModel.encode(t)
        val title = t.name.trim().ifEmpty { "Theme" }
        scope.launch {
            val tid = withContext(Dispatchers.IO) {
                if (id.isNotEmpty()) { UserThemes.updateGradient(context, id, title, spec); id }
                else UserThemes.createEditor(context, title, spec, pack.ifEmpty { null })
            }
            ThemeChoice.userNames = ThemeChoice.userNames + (spec to title)
            val c = ThemeChoice.Choice(prefs.keyboard.themeDay.get(), prefs.keyboard.themeNight.get(), prefs.keyboard.themeFollow.get())
            // a theme in use stays in use with its changes; a new one is put to use (as the gradient editor)
            val old = oldSpec
            val next = if (old != null && (c.day == old || c.night == old))
                c.copy(day = if (c.day == old) spec else c.day, night = if (c.night == old) spec else c.night)
            else ThemeChoice.pick(c, spec).first
            prefs.keyboard.themeDay.set(next.day)
            prefs.keyboard.themeNight.set(next.night)
            prefs.keyboard.glassEnabled.set(false)
            prefs.keyboard.userTheme.set(tid)
            nav.popBackStack()
        }
    }

    Column(Modifier.fillMaxSize().background(SetColors.screen).windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)) {
        // ThemeEditor:54-61: back, the name (editable), Save
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable { nav.popBackStack() }, contentAlignment = Alignment.Center) { Symbol("arrow_back", 24.sp, SetColors.onSurface) }
            Row(Modifier.weight(1f).height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicTextField(t.name, { t = t.copy(name = it.take(40)) }, Modifier.weight(1f), singleLine = true, textStyle = setText(22f), cursorBrush = SolidColor(SetColors.primary))
                Symbol("edit", 18.sp, SetColors.onSurfaceVariant)
            }
            FilledPill("Save") { save() }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // ThemeEditor:63-78: versions, which one is being edited, what that means
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val vers = listOf("light", "dark", "both")
                Segmented("te:versions", listOf("Bright", "Dark", "Bright and dark"), vers.indexOf(t.versions)) { i -> t = setVersions(t, vers[i]); edit = if (vers[i] == "dark") "dark" else if (vers[i] == "light") "light" else edit; picker = null }
                if (both) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DText("Editing", Modifier.padding(end = 4.dp), style = setText(14f, color = SetColors.onSurfaceVariant))
                    for ((key, label, icon) in listOf(Triple("light", "Bright", "light_mode"), Triple("dark", "Dark", "dark_mode"))) {
                        val on = edit == key
                        Row(
                            Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(if (on) SetColors.secondaryContainer else Color.Transparent)
                                .then(if (on) Modifier else Modifier.cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, SetColors.outline)), RoundedCornerShape(8.dp)))
                                .clickable { edit = key; picker = null }.padding(start = 8.dp, end = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val fg = if (on) SetColors.onSecondaryContainer else SetColors.onSurfaceVariant
                            Symbol(icon, 18.sp, fg); DText(label, style = setText(14f, 500, fg))
                        }
                    }
                }
                DText(
                    if (both) "Follows the system: Bright by day, Dark at night. Each has its own background and colours; shape, details and sound are shared."
                    else if (t.versions == "dark") "One version, used day and night. Add a bright one any time." else "One version, used day and night. Add a dark one any time.",
                    Modifier.padding(horizontal = 4.dp), style = setText(13f, color = SetColors.onSurfaceVariant).copy(lineHeight = 18.sp), maxLines = 4,
                )
            }
            // ThemeEditor:80-86: a field to type in, then the theme's keyboard
            TryLine()
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val spec = ThemeModel.encode(t.copy(light = if (edit == "light") v else t.light, dark = if (edit == "dark") v else t.dark))
                val props = KbProps(theme = spec, dark = edit == "dark", width = maxWidth.value, bar = "empty", ret = "return")
                val render = remember(props) { KbSpec.render(props) }
                KbView(render)
            }
            // ThemeEditor:88-90 + render status: how the letters read at the weakest spot of the background
            ReadStatus(v, res)
            val tg = picker
            if (tg != null) {
                Picker(tg, both, verName, pk, res, v, recent, onSet = ::pickerSet, onDone = ::closePicker, onUseSource = { fv ->
                    if (tg is Target.Role) { setV { it.copy(ov = it.ov - tg.key) }; pk = ThemeModel.toHsv(if (fv == "none") res.vals[tg.key.replace("Line", "Fg")] else fv) }
                })
            } else {
                Tabs(tab) { tab = it }
                when (tab) {
                    "bg" -> BackgroundTab(v, edit, both, verName, res.base, sel, onSel = { sel = it }, setV = ::setV, open = ::open)
                    "el" -> ElementsTab(t, v, res, both, verName, edit, setV = ::setV, setT = { t = it }, open = ::open)
                    else -> DetailsTab(t) { t = it }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** What the colour picker is changing. */
private sealed interface Target {
    data class Role(val key: String) : Target
    data object Background : Target
    data class StopAt(val i: Int) : Target
}

/** ThemeEditor setVersions: adding a version makes it from the other (the same family, moved over). */
private fun setVersions(t: ThemeModel.Theme, x: String): ThemeModel.Theme = when (x) {
    "light" -> t.copy(versions = x, light = t.light ?: ThemeModel.otherVersion(t.dark ?: Version(), "light"))
    "dark" -> t.copy(versions = x, dark = t.dark ?: ThemeModel.otherVersion(t.light ?: Version(), "dark"))
    else -> t.copy(versions = x, light = t.light ?: ThemeModel.otherVersion(t.dark ?: Version(), "light"), dark = t.dark ?: ThemeModel.otherVersion(t.light ?: Version(), "dark"))
}

@Composable
private fun FilledPill(label: String, onClick: () -> Unit) {
    Box(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(SetColors.primary).clickable(onClick = onClick).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        DText(label, style = setText(14f, 500, SetColors.scheme?.onPrimary ?: Color.White))
    }
}

private val card = RoundedCornerShape(20.dp)

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(card).background(SetColors.row).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

/** ThemeEditor:81-84: a field to try the theme in (the keyboard types into it). */
@Composable
private fun TryLine() {
    var text by remember { mutableStateOf("") }
    Box(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(20.dp)).background(SetColors.row)
            .cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, SetColors.outlineVariant)), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = setText(16f), cursorBrush = SolidColor(SetColors.primary),
            decorationBox = { inner -> Box { if (text.isEmpty()) DText("Type on the keyboard to try it", style = setText(16f, color = SetColors.outline)); inner() } })
    }
}

@Composable
private fun ReadStatus(v: Version, res: ThemeModel.Resolved) {
    val cols = ThemeModel.bgCols(v.bg)
    val worst = cols.minOf { ThemeModel.cr(res.vals.getValue("keyFg"), ThemeModel.over(res.vals.getValue("key"), it)) }
    val light = ThemeModel.lum(res.vals.getValue("keyFg")) > 0.4
    val good = worst >= 4.5
    val (bg, fg) = if (good) css("#e2efc8") to css("#2c3a00") else css("#fff1d6") to css("#4a3200")
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(if (good) "check_circle" else "info", 20.sp, fg)
        DText("${if (light) "Light" else "Dark"} labels, ${"%.1f".format(java.util.Locale.ROOT, worst)}:1 at the weakest spot. ${if (good) "Easy to read." else "Some letters may be hard to read."}",
            Modifier.weight(1f), style = setText(14f, color = fg).copy(lineHeight = 19.sp), maxLines = 3)
    }
}

/** ThemeEditor:142-146: Background, Elements, Details. */
@Composable
private fun Tabs(tab: String, onTab: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().cssShadow(listOf(CssShadow(true, 0f, -1f, 0f, 0f, SetColors.outlineVariant)), RoundedCornerShape(0.dp))) {
        for ((key, label) in listOf("bg" to "Background", "el" to "Elements", "info" to "Details")) {
            val on = tab == key
            Box(Modifier.weight(1f).height(48.dp).clickable { onTab(key) }, contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DText(label, style = setText(14f, 500, if (on) SetColors.primary else SetColors.onSurfaceVariant))
                }
                if (on) Box(Modifier.align(Alignment.BottomCenter).width(72.dp).height(3.dp).background(SetColors.primary, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
            }
        }
    }
}

// ---- Background (ThemeEditor:149-235)

private val PALETTE = listOf(
    "#2c2550", "#6a2c6a", "#9d3b55", "#0b5563", "#23408e", "#1e3b2a", "#3e2a1e", "#1b1c2b", "#4a1f3a", "#12395a", "#dd5a4d", "#f0894a", "#f2c14e", "#7cc47f",
    "#3fb8a8", "#0d9e9e", "#4a90d9", "#8f6fd9", "#d96fb0", "#ffe3d1", "#f4c4d8", "#e0d4f5", "#cfe2f7", "#d4efe4", "#fdf2c7", "#e6e6ea", "#ededf4", "#000000",
)

@Composable
private fun BackgroundTab(v: Version, edit: String, both: Boolean, verName: String, base: String, sel: Int, onSel: (Int) -> Unit, setV: ((Version) -> Version) -> Unit, open: (Target) -> Unit) {
    Card {
        // CHANGED (port map): photo backgrounds are made on the photo theme page (its own photo pipeline)
        val types = listOf("solid", "gradient")
        Segmented("te:bgtype", listOf("Solid colour", "Gradient"), types.indexOf(v.bg.type).coerceAtLeast(0)) { i -> setV { it.copy(bg = it.bg.copy(type = types[i])) } }
        if (v.bg.type == "solid") {
            Row(Modifier.heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Swatch(v.bg.color, 44) { open(Target.Background) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DText("Colour", style = setText(16f)); DText(v.bg.color.uppercase(), style = setText(13f, color = SetColors.onSurfaceVariant))
                }
            }
            // ThemeEditor:164-166: 28 colours, 7 a row
            for (row in PALETTE.chunked(7)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in row) {
                    val on = c == v.bg.color
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).then(if (on) Modifier.cssShadow(listOf(CssShadow(false, 0f, 0f, 0f, 2f, Color.White), CssShadow(false, 0f, 0f, 0f, 4f, SetColors.primary)), CircleShape) else Modifier)
                            .clip(CircleShape).background(css(c)).then(if (on) Modifier else Modifier.cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, Color.Black.copy(alpha = 0.15f))), CircleShape))
                            .clickable { setV { it.copy(bg = it.bg.copy(color = c)) } },
                    )
                }
            }
        } else GradientStops(v, sel, onSel, setV, open)
        val side = if (base == "dark") "A dark background, so it starts from DogBoard’s dark colours and light labels." else "A light background, so it starts from DogBoard’s light colours and dark labels."
        val mismatch = both && ((edit == "dark" && base == "light") || (edit == "light" && base == "dark"))
        DText(if (mismatch) "$side It’s still the $verName version, used ${if (edit == "dark") "at night." else "by day."}" else side, style = setText(13f, color = SetColors.onSurfaceVariant).copy(lineHeight = 18.sp), maxLines = 4)
    }
    if (v.bg.type == "gradient") Card { AngleDial(v.bg.angle) { a -> setV { it.copy(bg = it.bg.copy(angle = a)) } } }
    if (v.bg.type == "gradient") Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 72.dp).clip(card).background(SetColors.row).clickable { setV { it.copy(bg = it.bg.copy(hard = !it.bg.hard)) } }.padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DText("Hard edges", style = setText(16f))
            DText(if (v.bg.hard) "Each colour is a band with a sharp edge, like a flag." else "Off: the colours blend into each other.",
                style = setText(13f, color = SetColors.onSurfaceVariant).copy(lineHeight = 18.sp), maxLines = 4)
        }
        Switch(v.bg.hard)
    }
    // ThemeEditor:228-231: tint keys to match
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 72.dp).clip(card).background(SetColors.row).clickable { setV { it.copy(tint = !it.tint) } }.padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DText("Tint keys to match", style = setText(16f))
            DText(
                if (v.tint) "Keys, labels and the return key are your colour at other tones: lighter and darker shades of it, as rich or as muted as the colour itself. Anything you set yourself stays."
                else "Off: elements keep DogBoard’s default colours until you change them.",
                style = setText(13f, color = SetColors.onSurfaceVariant).copy(lineHeight = 18.sp), maxLines = 6,
            )
        }
        Switch(v.tint)
    }
}

@Composable
private fun Switch(on: Boolean) {
    Box(
        Modifier.size(52.dp, 32.dp).then(if (on) Modifier else Modifier.cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 2f, SetColors.outline)), RoundedCornerShape(16.dp)))
            .background(if (on) SetColors.primary else SetColors.surfaceContainerHighest, RoundedCornerShape(16.dp)),
    ) { Box(Modifier.padding(start = if (on) 24.dp else 8.dp, top = if (on) 4.dp else 8.dp).size(if (on) 24.dp else 16.dp).background(if (on) Color.White else SetColors.outline, CircleShape)) }
}

@Composable
private fun Swatch(c: String, size: Int, onClick: () -> Unit) {
    val none = c == "none"
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (none) Color.Transparent else css(c))
            .cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, if (none) 1.5f else 1f, if (none) SetColors.outline else Color.Black.copy(alpha = 0.18f))), CircleShape)
            .clickable(onClick = onClick),
    )
}

/** ThemeEditor:171-185: the gradient's colours on a bar: tap to add (three at most), drag to move, the chosen one below. */
@Composable
private fun GradientStops(v: Version, sel0: Int, onSel: (Int) -> Unit, setV: ((Version) -> Version) -> Unit, open: (Target) -> Unit) {
    val stops = v.bg.stops
    val sel = sel0.coerceAtMost(stops.size - 1)
    val even = stops.withIndex().all { (i, s) -> kotlin.math.abs(s.p - (i * 100.0 / (stops.size - 1)).roundToInt()) <= 1 }
    Row(Modifier.heightIn(min = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DText(if (stops.size < 7) "Tap the bar to add a colour. Drag to move one." else "Drag to move a colour. Seven is the most.", Modifier.weight(1f), style = setText(13f, color = SetColors.onSurfaceVariant), maxLines = 3)
        Row(
            Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).then(if (even) Modifier else Modifier.clickable { setV { it.copy(bg = it.bg.copy(stops = stops.mapIndexed { i, s -> s.copy(p = (i * 100.0 / (stops.size - 1)).roundToInt()) })) } })
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            val fg = SetColors.primary.copy(alpha = if (even) 0.38f else 1f)
            Symbol("horizontal_distribute", 18.sp, fg); DText("Space equally", style = setText(14f, 500, fg))
        }
    }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp)) {
        val w = with(density) { maxWidth.toPx() }; val pad = with(density) { 20.dp.toPx() }
        fun pAt(x: Float) = (((x - pad) / (w - 2 * pad)) * 100).roundToInt().coerceIn(0, 100)
        var drag by remember { mutableStateOf<Int?>(null) }
        Box(
            Modifier.fillMaxSize().pointerInput(stops.size) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val p = pAt(down.position.x)
                    // the nearest colour within reach is dragged; else a tap adds one (three at most)
                    val near = stops.withIndex().minByOrNull { kotlin.math.abs(it.value.p - p) }
                    var i = if (near != null && kotlin.math.abs(near.value.p - p) <= 6) near.index else null
                    if (i == null && stops.size < 7) {
                        val c = ThemeModel.mix(stops.lastOrNull { it.p <= p }?.c ?: stops.first().c, stops.firstOrNull { it.p >= p }?.c ?: stops.last().c, 0.5)
                        val n = (stops + Stop(c, p)).sortedBy { it.p }
                        setV { it.copy(bg = it.bg.copy(stops = n)) }; i = n.indexOfFirst { it.p == p && it.c == c }
                    }
                    if (i != null) { onSel(i); drag = i }
                    do {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: break
                        val d = drag
                        if (d != null && ch.pressed) { val np = pAt(ch.position.x); setV { it.copy(bg = it.bg.copy(stops = it.bg.stops.mapIndexed { j, s -> if (j == d) s.copy(p = np) else s })) }; ch.consume() }
                    } while (ev.changes.any { it.pressed })
                    drag = null
                }
            },
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(32.dp).clip(RoundedCornerShape(16.dp))
                // blended in OKLab, as the keyboard draws it (Oklab.expand)
                .background(Brush.horizontalGradient(*io.github.sillypuppyy.dogboard.design.Oklab.expand(stops.map { css(it.c) to it.p / 100f }).map { it.second to it.first }.toTypedArray())))
            for ((i, s) in stops.withIndex()) {
                val size = if (i == sel) 32 else 28
                val x = with(density) { (pad + (w - 2 * pad) * s.p / 100f).toDp() } - (size / 2).dp
                Box(
                    Modifier.offset(x = x, y = (28 - size / 2).dp).size(size.dp)
                        .cssShadow(if (i == sel) listOf(CssShadow(false, 0f, 0f, 0f, 3f, Color.White), CssShadow(false, 0f, 0f, 0f, 5f, SetColors.primary)) else listOf(CssShadow(false, 0f, 0f, 0f, 3f, Color.White), CssShadow(false, 0f, 1f, 4f, 0f, Color.Black.copy(alpha = 0.35f))), CircleShape)
                        .clip(CircleShape).background(css(s.c)),
                )
            }
        }
    }
    val cur = stops[sel]
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SetColors.screen).padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Swatch(cur.c, 44) { open(Target.StopAt(sel)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DText("Colour ${sel + 1} of ${stops.size}", style = setText(15f)); DText(cur.c.uppercase(), style = setText(13f, color = SetColors.onSurfaceVariant))
        }
        if (stops.size > 2) Box(Modifier.size(44.dp).clip(CircleShape).clickable { setV { it.copy(bg = it.bg.copy(stops = stops.filterIndexed { j, _ -> j != sel })) }; onSel((sel - 1).coerceAtLeast(0)) }, contentAlignment = Alignment.Center) {
            Symbol("delete", 22.sp, SetColors.onSurfaceVariant)
        }
    }
}

/** ThemeEditor:213-225: the direction: a ring to drag round (catching at each quarter turn) and the angle typed. */
@Composable
private fun AngleDial(angle: Int, onAngle: (Int) -> Unit) {
    val dir = when (angle) { 0 -> "Bottom to top"; 90 -> "Left to right"; 180 -> "Top to bottom"; 270 -> "Right to left"; else -> if (angle < 90) "Towards top right" else if (angle < 180) "Towards bottom right" else if (angle < 270) "Towards bottom left" else "Towards top left" }
    Row(Modifier.heightIn(min = 44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DText("Direction", Modifier.weight(1f), style = setText(16f)); DText(dir, style = setText(14f, color = SetColors.onSurfaceVariant))
        if (angle != 180) Box(Modifier.size(44.dp).clip(CircleShape).background(SetColors.secondaryContainer).clickable { onAngle(180) }, contentAlignment = Alignment.Center) { Symbol("restart_alt", 20.sp, SetColors.primary) }
    }
    val ring = SetColors.secondaryContainer; val prim = SetColors.primary
    var field by remember(angle) { mutableStateOf(angle.toString()) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(240.dp).pointerInput(Unit) {
                awaitEachGesture {
                    fun at(o: Offset) {
                        val c = size.width / 2f
                        var a = ((Math.toDegrees(atan2((o.x - c).toDouble(), -(o.y - c).toDouble())) + 360) % 360).roundToInt()
                        // ThemeEditor dialAt: catches at each quarter turn
                        for (q in listOf(0, 90, 180, 270, 360)) if (kotlin.math.abs(a - q) <= 4) a = q % 360
                        onAngle(a)
                    }
                    val d = awaitFirstDown(); at(d.position)
                    do { val ev = awaitPointerEvent(); ev.changes.firstOrNull()?.let { if (it.pressed) { at(it.position); it.consume() } } } while (ev.changes.any { it.pressed })
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height / 2); val k = size.width / 240f
                // the ring (radius 88-104 of 240) with a gap where the hand is
                drawArc(ring, angle + 5f - 90f, 350f, false, Offset(c.x - 96 * k, c.y - 96 * k), androidx.compose.ui.geometry.Size(192 * k, 192 * k), style = Stroke(16 * k))
                for (q in listOf(0, 90, 180, 270)) if (minOf(kotlin.math.abs(q - angle), 360 - kotlin.math.abs(q - angle)) >= 9) {
                    val r = Math.toRadians(q.toDouble())
                    drawCircle(prim, 2 * k, Offset(c.x + (96 * k * sin(r)).toFloat(), c.y - (96 * k * cos(r)).toFloat()))
                }
                val r = Math.toRadians(angle.toDouble())
                drawLine(prim, Offset(c.x + (74 * k * sin(r)).toFloat(), c.y - (74 * k * cos(r)).toFloat()), Offset(c.x + (118 * k * sin(r)).toFloat(), c.y - (118 * k * cos(r)).toFloat()), 4 * k, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            Row(Modifier.size(100.dp, 48.dp).clip(RoundedCornerShape(12.dp)).background(SetColors.secondaryContainer), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(field, { s -> val d = s.filter { it.isDigit() }.take(3); field = d; if (d.isNotEmpty()) onAngle(d.toInt() % 360) }, Modifier.width(52.dp), singleLine = true,
                    textStyle = setText(28f, 500, SetColors.onSecondaryContainer).copy(textAlign = androidx.compose.ui.text.style.TextAlign.End),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                DText("°", style = setText(28f, 500, SetColors.onSecondaryContainer))
            }
        }
    }
    DText("Drag round the ring or type the angle. It catches at each quarter turn.", Modifier.fillMaxWidth(), style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), maxLines = 3)
}

// ---- Elements (ThemeEditor:237-290)

private val GROUPS = listOf(
    "Letter keys" to listOf("key" to "Fill", "keyFg" to "Letters", "keyLine" to "Border"),
    "Shift, delete, 123 and emoji" to listOf("fn" to "Fill", "fnFg" to "Icons and labels", "fnLine" to "Border"),
    "Return key" to listOf("act" to "Fill", "actFg" to "Label", "actLine" to "Border"),
    "Suggestion bar" to listOf("barFg" to "Words", "barIcon" to "Icons", "chip" to "Highlight", "chipFg" to "Highlight text"),
    "Pop-ups and hints" to listOf("pop" to "Pop-up keys", "popFg" to "Pop-up letters", "hint" to "Long-press hints"),
)
private val SRC = mapOf("default" to "Default", "tinted" to "Tinted to match", "auto" to "Auto, readable on its fill", "yours" to "Yours")

private fun shown(c: String) = when {
    c == "none" -> "None"
    c.startsWith("rgba") -> "${(ThemeModel.parse(c).a * 100).roundToInt()}% white"
    else -> c.uppercase()
}

@Composable
private fun ElementsTab(
    t: ThemeModel.Theme, v: Version, res: ThemeModel.Resolved, both: Boolean, verName: String, edit: String,
    setV: ((Version) -> Version) -> Unit, setT: (ThemeModel.Theme) -> Unit, open: (Target) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DText(
            (if (both) "Colours for the $verName version. " else "") + "Tap a colour to change it. Each one shows where it comes from: Default, Tinted to match, Auto (a label picked to read on a fill you chose) or Yours. Reset puts back the colour it had before you changed it.",
            Modifier.padding(horizontal = 4.dp), style = setText(13f, color = SetColors.onSurfaceVariant).copy(lineHeight = 19.sp), maxLines = 8,
        )
        for ((title, rows) in GROUPS) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DText(title, Modifier.padding(start = 16.dp, top = 4.dp), style = setText(14f, 500, SetColors.primary))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for ((n, r) in rows.withIndex()) {
                    val (key, label) = r
                    val value = res.vals.getValue(key); val src = res.src.getValue(key); val yours = src == "yours"
                    val shape = RoundedCornerShape(topStart = if (n == 0) 20.dp else 4.dp, topEnd = if (n == 0) 20.dp else 4.dp, bottomStart = if (n == rows.lastIndex) 20.dp else 4.dp, bottomEnd = if (n == rows.lastIndex) 20.dp else 4.dp)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape).background(SetColors.row).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(44.dp).clip(CircleShape).clickable { open(Target.Role(key)) }, contentAlignment = Alignment.Center) { Swatch(value, 32) { open(Target.Role(key)) } }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            DText(label, style = setText(16f)); DText(SRC.getValue(src), style = setText(13f, color = SetColors.onSurfaceVariant))
                        }
                        DText(shown(value), style = setText(14f, if (yours) 600 else 400, if (yours) SetColors.primary else SetColors.onSurfaceVariant))
                        if (yours) Box(Modifier.size(44.dp).clip(CircleShape).background(SetColors.secondaryContainer).clickable { setV { it.copy(ov = it.ov - key) } }, contentAlignment = Alignment.Center) {
                            Symbol("restart_alt", 20.sp, SetColors.primary)
                        }
                    }
                }
            }
        }
        // Android's own icons under the keys (hide / switch keyboard): only light or dark can be asked for
        DText("Under the keys", Modifier.padding(start = 16.dp, top = 4.dp), style = setText(14f, 500, SetColors.primary))
        Column(Modifier.fillMaxWidth().clip(card).background(SetColors.row).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            io.github.sillypuppyy.dogboard.design.settings.TitleSub("Bottom icons", "Android’s hide-keyboard and switch-keyboard icons. Auto picks what reads on the colour there.")
            val navs = listOf("auto", "light", "dark")
            Segmented("te:nav", listOf("Auto", "Light", "Dark"), navs.indexOf(v.nav).coerceAtLeast(0)) { i -> setV { it.copy(nav = navs[i]) } }
        }
        // how keys sit on the background (shared by both versions, like the shape)
        DText("Key style", Modifier.padding(start = 16.dp, top = 4.dp), style = setText(14f, 500, SetColors.primary))
        Column(Modifier.fillMaxWidth().clip(card).background(SetColors.row).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val styles = listOf("auto", "solid", "frosted", "under")
            val k = t.shape.keys
            io.github.sillypuppyy.dogboard.design.settings.TitleSub("Keys", when (k) {
                "solid" -> "Solid: nothing shows through the keys."
                "frosted" -> "Frosted: see-through white keys, the background showing through."
                "under" -> "Under: each key takes the colour beneath it, lightened, so a gradient runs across the keys."
                else -> "As set: the key colours above, as they are."
            })
            Segmented("te:keys", listOf("As set", "Solid", "Frosted", "Under"), styles.indexOf(k).coerceAtLeast(0)) { i -> setT(t.copy(shape = t.shape.copy(keys = styles[i]))) }
        }
        // ThemeEditor:256-280: the shape, shared by both versions
        DText(if (both) "Shape, for both versions" else "Shape", Modifier.padding(start = 16.dp, top = 4.dp), style = setText(14f, 500, SetColors.primary))
        val s0 = ThemeModel.Shape(); val sh = t.shape
        Column(Modifier.clip(card), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SetSliderRow("te:radius", "Corners", "", sh.radius.toFloat(), s0.radius.toFloat(), 0f..20f, 1f, { "${it.roundToInt()} dp" }) { setT(t.copy(shape = sh.copy(radius = it.toDouble()))) }
            SetSliderRow("te:size", "Label size", "", sh.size.toFloat(), s0.size.toFloat(), 85f..130f, 1f, { "${it.roundToInt()}%" }) { setT(t.copy(shape = sh.copy(size = it.roundToInt()))) }
            SetSliderRow("te:line", "Border width", "", sh.line.toFloat(), s0.line.toFloat(), 0.5f..3f, 0.5f, { "$it dp" }) { setT(t.copy(shape = sh.copy(line = it.toDouble()))) }
            Column(Modifier.fillMaxWidth().background(SetColors.row).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    DText("Label weight", Modifier.weight(1f), style = setText(16f))
                    if (sh.weight != s0.weight) Box(Modifier.size(44.dp).clip(CircleShape).background(SetColors.secondaryContainer).clickable { setT(t.copy(shape = sh.copy(weight = s0.weight))) }, contentAlignment = Alignment.Center) { Symbol("restart_alt", 20.sp, SetColors.primary) }
                }
                val ws = listOf(400, 500, 600)
                Segmented("te:weight", listOf("Regular", "Medium", "Bold"), ws.indexOf(sh.weight).coerceAtLeast(0)) { i -> setT(t.copy(shape = sh.copy(weight = ws[i]))) }
            }
        }
        // ThemeEditor:282-288: how much is set by hand; reset or start over
        val count = v.ov.size + listOf(sh.radius != s0.radius, sh.weight != s0.weight, sh.size != s0.size, sh.line != s0.line, sh.keys != s0.keys).count { it }
        Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DText(when (count) { 0 -> "Nothing set by hand yet."; 1 -> "1 thing set by hand."; else -> "$count things set by hand." }, style = setText(13f, color = SetColors.onSurfaceVariant))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, SetColors.outline)), RoundedCornerShape(20.dp))
                        .clickable { setT((if (edit == "dark") t.copy(dark = v.copy(ov = emptyMap())) else t.copy(light = v.copy(ov = emptyMap()))).copy(shape = s0)) }.padding(start = 12.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) { Symbol("restart_alt", 18.sp, SetColors.primary); DText("Reset all elements", style = setText(14f, 500, SetColors.primary)) }
                Box(
                    Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).clickable { setV { Version(Bg(color = if (edit == "dark") "#1a1b20" else "#ededf4")) } }.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { DText("Start over from defaults", style = setText(14f, 500, SetColors.primary)) }
            }
        }
    }
}

// ---- Details (ThemeEditor:293-363)

/** Our key sounds (Settings › Sound and vibration), offered with the theme. */
private val SOUNDS = listOf(null to "None", "system" to "Phone click", "tick" to "Tick", "drop" to "Drop", "glass" to "Glass", "thock" to "Thock", "classic" to "Classic",
    "clack" to "Clack", "pen" to "Pen", "mouse" to "Mouse", "mechkeys" to "Mech keys")

@Composable
private fun DetailsTab(t: ThemeModel.Theme, setT: (ThemeModel.Theme) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth().clip(card).background(SetColors.row).padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Outlined("Author", t.author, "Shown when someone long-presses the theme.", 40, icon = "person") { setT(t.copy(author = it)) }
            Outlined("Description", t.desc, "One or two lines about the theme.", 140, multi = true) { setT(t.copy(desc = it)) }
        }
        DText("Sound", Modifier.padding(start = 16.dp, top = 4.dp), style = setText(14f, 500, SetColors.primary))
        Column(Modifier.fillMaxWidth().clip(card).background(SetColors.row).padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var openList by remember { mutableStateOf(false) }
            val shownName = SOUNDS.firstOrNull { it.first == t.sound }?.second ?: "None"
            Box {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(4.dp))
                        .cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, if (openList) 2f else 1f, if (openList) SetColors.primary else SetColors.outline)), RoundedCornerShape(4.dp))
                        .clickable { openList = !openList }.padding(start = 12.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("volume_up", 24.sp, SetColors.onSurfaceVariant)
                    DText(shownName, Modifier.weight(1f), style = setText(16f))
                    Symbol(if (openList) "arrow_drop_up" else "arrow_drop_down", 24.sp, SetColors.onSurfaceVariant)
                }
                DText("Key sound", Modifier.offset { IntOffset(12.dp.roundToPx(), (-8).dp.roundToPx()) }.background(SetColors.row).padding(horizontal = 4.dp), style = setText(12f, color = if (openList) SetColors.primary else SetColors.onSurfaceVariant))
            }
            if (openList) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(SetColors.surfaceContainerHighest).padding(vertical = 8.dp)) {
                for ((sid, name) in SOUNDS) Box(Modifier.fillMaxWidth().height(48.dp).background(if (sid == t.sound) SetColors.secondaryContainer else Color.Transparent).clickable { setT(t.copy(sound = sid)); openList = false }.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    DText(name, style = setText(16f))
                }
            }
            DText("Offered when someone picks this theme. Their own sound settings stay until they say yes.", Modifier.padding(horizontal = 16.dp), style = setText(12f, color = SetColors.onSurfaceVariant), maxLines = 3)
        }
        DText("Image credits appear here when the background is a photo.", Modifier.padding(horizontal = 16.dp), style = setText(13f, color = SetColors.onSurfaceVariant), maxLines = 3)
    }
}

/** ThemeEditor:296-306: an outlined text field with its label in the outline, a supporting line and a counter. */
@Composable
private fun Outlined(label: String, value: String, support: String, max: Int, icon: String? = null, multi: Boolean = false, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            Row(
                Modifier.fillMaxWidth().heightIn(min = if (multi) 112.dp else 56.dp).clip(RoundedCornerShape(4.dp))
                    .cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, if (focused) 2f else 1f, if (focused) SetColors.primary else SetColors.outline)), RoundedCornerShape(4.dp))
                    .padding(start = if (icon != null) 12.dp else 16.dp, end = 16.dp, top = if (multi) 16.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = if (multi) Alignment.Top else Alignment.CenterVertically,
            ) {
                if (icon != null) Symbol(icon, 24.sp, SetColors.onSurfaceVariant)
                BasicTextField(value, { onChange(it.take(max)) }, Modifier.weight(1f).then(if (multi) Modifier.heightIn(min = 80.dp) else Modifier)
                    .onFocusChangedCompat { focused = it }, singleLine = !multi, textStyle = setText(16f).copy(lineHeight = 24.sp), cursorBrush = SolidColor(SetColors.primary))
            }
            DText(label, Modifier.offset { IntOffset(12.dp.roundToPx(), (-8).dp.roundToPx()) }.background(SetColors.row).padding(horizontal = 4.dp), style = setText(12f, color = if (focused) SetColors.primary else SetColors.onSurfaceVariant))
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DText(support, Modifier.weight(1f), style = setText(12f, color = SetColors.onSurfaceVariant), maxLines = 3)
            DText("${value.length}/$max", style = setText(12f, color = SetColors.onSurfaceVariant))
        }
    }
}

private fun Modifier.onFocusChangedCompat(f: (Boolean) -> Unit): Modifier = this.then(Modifier.onFocusChanged { f(it.isFocused) })

// ---- the colour picker (ThemeEditor:92-139)

private val ROLE_NAMES = mapOf(
    "key" to "Letter keys, fill", "keyFg" to "Letters", "keyLine" to "Letter keys, border", "fn" to "Shift, delete, 123, fill", "fnFg" to "Their icons and labels",
    "fnLine" to "Their border", "act" to "Return key, fill", "actFg" to "Return key, label", "actLine" to "Return key, border", "barFg" to "Suggestion words",
    "chip" to "Suggestion highlight", "chipFg" to "Highlight text", "pop" to "Pop-up keys", "popFg" to "Pop-up letters", "hint" to "Long-press hints",
    "barIcon" to "Suggestion bar icons",
)

@Composable
private fun Picker(
    tg: Target, both: Boolean, verName: String, k: ThemeModel.Hsv, res: ThemeModel.Resolved, v: Version, recent: List<String>,
    onSet: (ThemeModel.Hsv) -> Unit, onDone: () -> Unit, onUseSource: (String) -> Unit,
) {
    val title = (when (tg) { Target.Background -> "Background"; is Target.StopAt -> "Gradient, colour ${tg.i + 1}"; is Target.Role -> ROLE_NAMES.getValue(tg.key) }) + if (both) " ($verName)" else ""
    val cur = ThemeModel.fromHsv(k); val opaque = ThemeModel.fromHsv(k.copy(a = 1.0))
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(SetColors.row).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(css(cur)).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, Color.Black.copy(alpha = 0.18f))), CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DText(title, style = setText(18f), maxLines = 2)
                DText((if (k.a < 0.999) "${(k.a * 100).roundToInt()}% of " else "") + opaque.uppercase(), style = setText(13f, color = SetColors.onSurfaceVariant))
            }
            FilledPill("Done", onDone)
        }
        // ThemeEditor:99-101: back to what it would be without you (default, tinted or auto)
        if (tg is Target.Role) {
            val without = ThemeModel.resolve(v.copy(ov = v.ov - tg.key))
            val fv = without.vals.getValue(tg.key); val fs = without.src.getValue(tg.key); val on = res.src[tg.key] != "yours"
            Row(
                Modifier.height(36.dp).clip(RoundedCornerShape(8.dp)).background(if (on) SetColors.secondaryContainer else Color.Transparent)
                    .then(if (on) Modifier else Modifier.cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, SetColors.outline)), RoundedCornerShape(8.dp)))
                    .clickable { onUseSource(fv) }.padding(start = 8.dp, end = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Swatch(fv, 20) { onUseSource(fv) }
                DText("Use " + (if (fs == "tinted") "tinted" else if (fs == "auto") "auto" else "default"), style = setText(14f, 500))
                DText(shown(fv), style = setText(14f, color = SetColors.onSurfaceVariant))
            }
        }
        // ThemeEditor:102-104: saturation across, brightness down
        BoxWithConstraints(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp))) {
            val hueC = css(ThemeModel.fromHsv(ThemeModel.Hsv(k.h, 1.0, 1.0)))
            val density = LocalDensity.current
            val w = with(density) { maxWidth.toPx() }; val h = with(density) { 180.dp.toPx() }
            Box(
                Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.White, hueC))).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    .pointerInput(k.h, k.a) {
                        awaitEachGesture {
                            fun at(o: Offset) = onSet(k.copy(s = (o.x / w).toDouble().coerceIn(0.0, 1.0), v = (1 - o.y / h).toDouble().coerceIn(0.0, 1.0)))
                            val d = awaitFirstDown(); at(d.position)
                            do { val ev = awaitPointerEvent(); ev.changes.firstOrNull()?.let { if (it.pressed) { at(it.position); it.consume() } } } while (ev.changes.any { it.pressed })
                        }
                    },
            )
            Box(
                Modifier.offset(x = maxWidth * k.s.toFloat() - 12.dp, y = 180.dp * (1 - k.v).toFloat() - 12.dp).size(24.dp)
                    .cssShadow(listOf(CssShadow(false, 0f, 1f, 4f, 0f, Color.Black.copy(alpha = 0.5f))), CircleShape)
                    .clip(CircleShape).background(Color.White).padding(3.dp).clip(CircleShape).background(css(opaque)),
            )
        }
        PickerSlider("Hue", "${k.h.roundToInt()}°", (k.h / 360).toFloat(), Brush.horizontalGradient(listOf("#ff0000", "#ffff00", "#00ff00", "#00ffff", "#0000ff", "#ff00ff", "#ff0000").map(::css))) { onSet(k.copy(h = (it * 359.0))) }
        val hasAlpha = tg is Target.Role && !tg.key.endsWith("Fg") && !tg.key.endsWith("Line") && tg.key != "hint"
        if (hasAlpha) PickerSlider("Opacity", "${(k.a * 100).roundToInt()}%", k.a.toFloat(), Brush.horizontalGradient(listOf(Color.Transparent, css(opaque)))) { onSet(k.copy(a = (it * 100).roundToInt() / 100.0)) }
        // ThemeEditor:123: hex
        var draft by remember(opaque) { mutableStateOf(opaque.uppercase()) }
        Row(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, SetColors.outline)), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            DText("Hex", style = setText(14f, color = SetColors.onSurfaceVariant))
            BasicTextField(draft, { s -> draft = s; Regex("^#?([0-9a-fA-F]{6})$").find(s.trim())?.let { m -> onSet(ThemeModel.toHsv("#" + m.groupValues[1]).copy(a = k.a)) } },
                Modifier.weight(1f), singleLine = true, textStyle = setText(16f), cursorBrush = SolidColor(SetColors.primary))
        }
        // ThemeEditor:124-137: this theme's colours, then recent ones
        val inTheme = (listOf("key", "keyFg", "fn", "act", "chip", "hint").map { res.vals.getValue(it) } + ThemeModel.bgCols(v.bg))
            .filter { it != "none" && !it.startsWith("rgba") }.distinct().take(8)
        ChipRow("In this theme", inTheme) { c -> onSet(ThemeModel.toHsv(c).copy(a = k.a)) }
        if (recent.isNotEmpty()) ChipRow("Recent", recent) { c -> onSet(ThemeModel.toHsv(c).copy(a = k.a)) }
    }
}

@Composable
private fun PickerSlider(label: String, value: String, p: Float, track: Brush, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { DText(label, style = setText(14f)); DText(value, style = setText(14f, color = SetColors.onSurfaceVariant)) }
        BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp)) {
            val density = LocalDensity.current
            val w = with(density) { maxWidth.toPx() }
            val mw = maxWidth
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val d = awaitFirstDown(); onChange((d.position.x / w).coerceIn(0f, 1f))
                        do { val ev = awaitPointerEvent(); ev.changes.firstOrNull()?.let { if (it.pressed) { onChange((it.position.x / w).coerceIn(0f, 1f)); it.consume() } } } while (ev.changes.any { it.pressed })
                    }
                },
            ) {
                Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).background(track))
                Box(Modifier.offset(x = (mw - 4.dp) * p).width(4.dp).height(44.dp).cssShadow(listOf(CssShadow(false, 0f, 0f, 0f, 2f, Color.White)), RoundedCornerShape(2.dp)).background(SetColors.onSurface, RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
private fun ChipRow(label: String, colours: List<String>, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DText(label, style = setText(14f, 500, SetColors.primary))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { for (c in colours) Swatch(c, 36) { onPick(c) } }
    }
}
