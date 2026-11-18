package io.github.sillypuppyy.dogboard.design

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.patrickgold.florisboard.ime.keyboard3.interaction.LongPress
import dev.patrickgold.florisboard.ime.keyboard3.interaction.barLabel
import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchKey

/**
 * Stage 2: the live key area drawn by the ported Kb.dc.html view. FlorisBoard still decides
 * which key a touch hits; [pressedKey] and [longPress] come from its pointer tracker.
 */
@Composable
fun LiveKbView(
    layerId: String,
    /** The field variant ("text", "email", "url"): the touch model keeps the layer id "base". */
    field: String,
    /** FlorisBoard's number row setting: the touch model adds the row to the letter planes. */
    numberRow: Boolean,
    actionWords: Boolean = false,
    /** Layout elements (Settings › Layout): number row on long-press, hint kind, space label, language, capitals. */
    numberRowLongPress: Boolean = false,
    hintKind: String = "off",
    spaceLabel: String = "auto",
    langName: String = "English",
    capsLabels: Boolean = false,
    /** Symbol pages ("iphone" / "android") and ?123 / action keys as pills (Settings › Layout). */
    symbolPages: String = "iphone",
    pillKeys: Boolean = false,
    /** The Classic bottom row (?123, comma, emoji, space, period, action): the design's "android" key style. */
    bottomClassic: Boolean = false,
    actionLabel: String? = null,
    shiftState: InputShiftState,
    enterAction: String,
    pressedKey: TouchKey?,
    longPress: LongPress?,
    panel: String = "",
    actions: KbActions? = null,
    clips: List<KbClipData>? = null,
    clipFilter: Int = 0,
    panelVariant: String = "default",
    /** "emoji" or "globe": what the fn key does (FlorisBoard's fn key setting). */
    fnKey: String = "emoji",
    /** The space-bar trackpad's finger x (dp), or null when it isn't in use. */
    trackpadX: Float? = null,
    /** The active layout's letter rows ([LiveKb.letterRows]); null = English. */
    letterRows: List<List<KbLetterKey>>? = null,
    modifier: Modifier = Modifier,
) {
    val dark = liveDark()
    // the default theme in the system's Material You colours (neutral greys before Android 14), as Gboard
    val context = androidx.compose.ui.platform.LocalContext.current
    remember(context) { MaterialYouTheme.override = MaterialYouTheme.forDevice(context); DesignFonts.preferSystemGoogleSans = true; 0 }
    BoxWithConstraints(modifier.fillMaxSize()) {
        // nothing to draw until the key area has a real size (the first measure can be empty)
        if (!maxWidth.value.isFinite() || maxWidth.value < 100f || maxHeight.value < 100f) return@BoxWithConstraints
        val plane = LiveKb.plane(layerId)
        val pressed = pressedKey?.let { LiveKb.keyId(it, plane) }.orEmpty()
        // the key preview stays 40 ms after the finger lifts, then fades out in 70 ms (designer
        // answers 2026-11-14); a new press shows its own at once
        val livePreview = if (longPress?.shouldShowSimplePopup() == true && longPress.shouldShowExtendedPopup() != true) pressed else ""
        var lingering by remember { mutableStateOf("") }
        val fade = remember { androidx.compose.animation.core.Animatable(1f) }
        LaunchedEffect(livePreview) {
            if (livePreview.isNotEmpty()) {
                lingering = livePreview
                fade.snapTo(1f)
            } else if (lingering.isNotEmpty()) {
                kotlinx.coroutines.delay(PREVIEW_HOLD_MS)
                fade.animateTo(0f, androidx.compose.animation.core.tween(PREVIEW_FADE_MS))
                lingering = ""
            }
        }
        // the bar is out only once the long-press delay has passed (extendedKeys is filled at touch-down)
        val extended = longPress?.shouldShowExtendedPopup() == true
        val props = KbProps(
            dark = dark,
            plane = plane,
            field = field,
            numberRow = numberRow && plane == "base",
            letterRows = letterRows,
            numRow = if (numberRow) "on" else if (numberRowLongPress) "hints" else "off",
            hintKind = hintKind,
            spaceLabel = spaceLabel,
            langName = langName,
            caseMode = if (capsLabels) "caps" else "shift",
            kstyle = if (bottomClassic) "android" else "ios",
            actionStyle = if (actionWords) "word" else "icon",
            symbols = symbolPages, pillKeys = pillKeys,
            actionLabel = actionLabel,
            shift = LiveKb.shift(shiftState),
            ret = if (enterAction in RET_WORDS) enterAction else "return",
            pressed = pressed,
            // the key preview while a character key is held (FlorisBoard's simple popup), the
            // long-press bar once its extended keys are out
            preview = livePreview.ifEmpty { lingering },
            previewFade = if (livePreview.isEmpty() && lingering.isNotEmpty()) fade.value else null,
            longpress = if (extended) pressed else "",
            lpSel = longPress?.extendedFocusedIndex ?: 0,
            // what FlorisBoard will commit, in its order (the static design table differs for
            // shifted S and I, and for keys the design has no list for)
            lpItems = if (extended) longPress!!.extendedKeys.map { k -> k.barLabel() } else emptyList(),
            width = maxWidth.value,
            keyH = maxHeight.value,
            only = "live",
            panel = panel,
            clips = clips, clipFilter = clipFilter,
            panelVariant = panelVariant,
            fnKey = fnKey,
            trackpad = trackpadX != null,
            trackpadX = trackpadX,
        )
        val themed = liveTheme().apply(props)
        // one layout per keyboard state and pressed key; the preview (and its fade frames), the long-press
        // bar and the trackpad are laid over it (KbSpec.overlays) without rebuilding it
        val layoutProps = themed.copy(
            pressed = themed.pressed.ifEmpty { if (themed.previewFade != null) "" else themed.preview },
            preview = "", previewFade = null, longpress = "", lpSel = 0, lpItems = emptyList(), trackpadX = null,
        )
        val layout = remember(layoutProps) { KbSpec.layout(layoutProps) }
        val render = KbSpec.overlays(layout, themed)
        androidx.compose.runtime.CompositionLocalProvider(LocalKbActions provides actions) { KbView(render) }
    }
}

/** The chosen theme's accent (the return key's colour): the swipe trail. */
@androidx.compose.runtime.Composable
fun liveAccent(): androidx.compose.ui.graphics.Color {
    val p = liveTheme().apply(KbProps(dark = liveDark()))
    return KbThemes.tokens(p.theme, p.dark, p.dim, p.keyOpacity, p.photoLuminance).act
}

/**
 * The keyboard window's own background (the strip around the navigation handle too): the design
 * theme's body colour, so it matches the keys' surroundings (it was the older stopgap theme's grey).
 */
@Composable
fun liveWindowBg(): androidx.compose.ui.graphics.Color {
    val context = androidx.compose.ui.platform.LocalContext.current
    remember(context) { if (MaterialYouTheme.override == null) MaterialYouTheme.override = MaterialYouTheme.forDevice(context); 0 }
    // the chosen theme's body colour (Themes page), in its day or night version
    val p = liveTheme().apply(KbProps(dark = liveDark()))
    return KbThemes.tokens(p.theme, p.dark, p.dim, p.keyOpacity, p.photoLuminance).bg
}

/**
 * Dark or light as the keyboard's theme setting decides (FlorisBoard's theme mode: always day,
 * always night, follow the system, or follow the time of day).
 */
@Composable
fun liveDark(): Boolean {
    val system = isSystemInDarkTheme()
    val mode by dev.patrickgold.florisboard.ime.theme.LocalThemeController.current.effectiveThemeMode
        .collectAsState(if (system) dev.patrickgold.florisboard.ime.theme.EffectiveThemeMode.NIGHT else dev.patrickgold.florisboard.ime.theme.EffectiveThemeMode.DAY)
    return mode == dev.patrickgold.florisboard.ime.theme.EffectiveThemeMode.NIGHT
}

private const val PREVIEW_HOLD_MS = 40L
private const val PREVIEW_FADE_MS = 70

private val RET_WORDS = setOf("send", "search", "go", "next", "previous", "done", "join", "route", "continue")

/**
 * Stage 2: the suggestion bar drawn by the ported view from the live strip ([strip]); a tap on a
 * slot calls [onPick] with that suggestion.
 */
@Composable
fun LiveKbBar(
    strip: List<io.github.sillypuppyy.dogboard.engine.Suggestion>,
    onPick: (io.github.sillypuppyy.dogboard.engine.Suggestion) -> Unit,
    onLeft: () -> Unit,
    toolRow: Boolean = false,
    panel: String = "",
    onTool: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onMic: () -> Unit = {},
    incognito: Boolean = false,
    /** A long-press on a suggestion (remove it). */
    onLongPick: (io.github.sillypuppyy.dogboard.engine.Suggestion) -> Unit = {},
    /** The word just removed: the bar shows it with Undo instead of suggestions. */
    removed: String? = null,
    onUndo: () -> Unit = {},
    /** The recent-copy chip's text, shown in place of predictions while nothing is typed (null: none). */
    clipLabel: String? = null,
    clipMedia: Boolean = false,
    /** Smart chips in place of the suggestions (Kb:840-851), and a tap on one. */
    chips: List<KbChipData> = emptyList(),
    onChip: (Int) -> Unit = {},
    onClip: () -> Unit = {},
    /** The emoji panel's search state and its header's Search pill. */
    emojiSearch: Boolean = false,
    onEmojiSearch: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val dark = liveDark()
    // Gboard's 44 dp strip with the default Material You look, the canvas's 48 otherwise
    BoxWithConstraints(modifier.fillMaxWidth().height(if (MaterialYouTheme.override != null) 44.dp else 48.dp)) {
        if (!maxWidth.value.isFinite() || maxWidth.value < 100f) return@BoxWithConstraints
        val slots = strip.map { s ->
            when (s.kind) {
                io.github.sillypuppyy.dogboard.engine.Suggestion.Kind.LITERAL -> "“${s.word}”" to "lit"
                io.github.sillypuppyy.dogboard.engine.Suggestion.Kind.AUTOCORRECT -> s.word to "ac"
                io.github.sillypuppyy.dogboard.engine.Suggestion.Kind.PREDICTION -> s.word to "p"
                io.github.sillypuppyy.dogboard.engine.Suggestion.Kind.EMOJI -> s.word to "emo"
                else -> s.word to "w"
            }
        }
        // the tool row replaces the suggestions while open; a panel shows its header instead
        // incognito with nothing typed: the design's incognito note instead of predictions (Kb:131)
        val incognitoNote = incognito && !toolRow && strip.none { it.kind != io.github.sillypuppyy.dogboard.engine.Suggestion.Kind.PREDICTION }
        val chipBar = chips.isNotEmpty() && !toolRow && removed == null
        val clip = !chipBar && clipLabel != null && !toolRow && removed == null && strip.all { it.kind == io.github.sillypuppyy.dogboard.engine.Suggestion.Kind.PREDICTION }
        val trackpad by io.github.sillypuppyy.dogboard.ime.LiveUi.trackpad.collectAsState()
        val clipFilter by io.github.sillypuppyy.dogboard.ime.LiveUi.clipFilter.collectAsState()
        val props = KbProps(
            dark = dark, slots = if (toolRow || incognitoNote || removed != null || clip || chipBar) null else slots,
            bar = if (toolRow) "tools" else if (removed != null) "removed" else if (chipBar) "contact" else if (clip) "clip" else "empty",
            chips = if (chipBar) chips else null,
            clipLabel = clipLabel ?: "", clipMedia = clipMedia,
            panel = panel, width = maxWidth.value, only = "bar", incognito = incognito, trackpad = trackpad,
            removedWord = removed ?: "", clipFilter = clipFilter, emojiSearch = emojiSearch,
        )
        val themed = liveTheme().apply(props)
        val render = remember(themed) { KbSpec.render(themed) }
        val actions = remember(strip, onPick, onLeft, onTool, onBack, onMic, onLongPick, onUndo, onClip, onChip, onEmojiSearch) {
            KbActions(
                onSlot = { i -> strip.getOrNull(i)?.let(onPick) }, onSlotLong = { i -> strip.getOrNull(i)?.let(onLongPick) },
                onUndo = onUndo, onRecentClip = onClip, onChip = onChip, onLeft = onLeft, onTool = onTool, onBack = onBack, onMic = onMic,
                onClipFilter = { i -> io.github.sillypuppyy.dogboard.ime.LiveUi.clipFilter.value = i },
                onEmojiSearch = onEmojiSearch,
            )
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalKbActions provides actions) { KbView(render) }
    }
}

/**
 * Landscape side strip (Kb:160-166, States: "Side areas (11 % each) hold undo / redo and cursor
 * arrows instead of stretched keys"): the left one undo and redo, the right one the cursor arrows.
 * [onAction] gets "undo", "redo", "left" or "right".
 */
@Composable
fun LiveSideStrip(left: Boolean, onAction: (String) -> Unit, modifier: Modifier = Modifier) {
    val dark = liveDark()
    val themed = liveTheme().apply(KbProps(dark = dark, only = "bar"))
    val t = remember(themed) { KbSpec.render(themed).t }
    val items = if (left) listOf("undo" to "undo", "redo" to "redo") else listOf("arrow_back" to "left", "arrow_forward" to "right")
    androidx.compose.foundation.layout.Column(
        modifier,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.CenterVertically),
    ) {
        for ((icon, action) in items) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(48.dp)
                    .background(t.fn, androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                    .tap { onAction(action) },
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) { Symbol(icon, 22.sp, t.fnFg) }
        }
    }
}
