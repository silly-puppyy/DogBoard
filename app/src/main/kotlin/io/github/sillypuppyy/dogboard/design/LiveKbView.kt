package io.github.sillypuppyy.dogboard.design

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.patrickgold.florisboard.ime.keyboard3.interaction.LongPress
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
    actionLabel: String? = null,
    shiftState: InputShiftState,
    enterAction: String,
    pressedKey: TouchKey?,
    longPress: LongPress?,
    panel: String = "",
    actions: KbActions? = null,
    clips: List<KbClipData>? = null,
    panelVariant: String = "default",
    /** "emoji" or "globe": what the fn key does (FlorisBoard's fn key setting). */
    fnKey: String = "emoji",
    /** The space-bar trackpad's finger x (dp), or null when it isn't in use. */
    trackpadX: Float? = null,
    modifier: Modifier = Modifier,
) {
    val dark = liveDark()
    BoxWithConstraints(modifier.fillMaxSize()) {
        // nothing to draw until the key area has a real size (the first measure can be empty)
        if (!maxWidth.value.isFinite() || maxWidth.value < 100f || maxHeight.value < 100f) return@BoxWithConstraints
        val plane = LiveKb.plane(layerId)
        val pressed = pressedKey?.let { LiveKb.keyId(it, plane) }.orEmpty()
        // the bar is out only once the long-press delay has passed (extendedKeys is filled at touch-down)
        val extended = longPress?.shouldShowExtendedPopup() == true
        val props = KbProps(
            dark = dark,
            plane = plane,
            field = field,
            numberRow = numberRow && plane == "base",
            actionStyle = if (actionWords) "word" else "icon",
            actionLabel = actionLabel,
            shift = LiveKb.shift(shiftState),
            ret = if (enterAction in RET_WORDS) enterAction else "return",
            pressed = pressed,
            // the key preview while a character key is held (FlorisBoard's simple popup), the
            // long-press bar once its extended keys are out
            preview = if (longPress?.shouldShowSimplePopup() == true && !extended) pressed else "",
            longpress = if (extended) pressed else "",
            lpSel = longPress?.extendedFocusedIndex ?: 0,
            // what FlorisBoard will commit, in its order (the static design table differs for
            // shifted S and I, and for keys the design has no list for)
            lpItems = if (extended) longPress!!.extendedKeys.map { k ->
                (k.data.output as? org.k3lp.lib.text.K3String)?.toText() ?: ""
            } else emptyList(),
            width = maxWidth.value,
            keyH = maxHeight.value,
            only = "live",
            panel = panel,
            clips = clips,
            panelVariant = panelVariant,
            fnKey = fnKey,
            trackpad = trackpadX != null,
            trackpadX = trackpadX,
        )
        val themed = liveTheme().apply(props)
        val render = remember(themed) { KbSpec.render(themed) }
        androidx.compose.runtime.CompositionLocalProvider(LocalKbActions provides actions) { KbView(render) }
    }
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
    onClip: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val dark = liveDark()
    BoxWithConstraints(modifier.fillMaxWidth().height(48.dp)) {
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
        val clip = clipLabel != null && !toolRow && removed == null && strip.all { it.kind == io.github.sillypuppyy.dogboard.engine.Suggestion.Kind.PREDICTION }
        val trackpad by io.github.sillypuppyy.dogboard.ime.LiveUi.trackpad.collectAsState()
        val props = KbProps(
            dark = dark, slots = if (toolRow || incognitoNote || removed != null || clip) null else slots,
            bar = if (toolRow) "tools" else if (removed != null) "removed" else if (clip) "clip" else "empty",
            clipLabel = clipLabel ?: "", clipMedia = clipMedia,
            panel = panel, width = maxWidth.value, only = "bar", incognito = incognito, trackpad = trackpad,
            removedWord = removed ?: "",
        )
        val themed = liveTheme().apply(props)
        val render = remember(themed) { KbSpec.render(themed) }
        val actions = remember(strip, onPick, onLeft, onTool, onBack, onMic, onLongPick, onUndo, onClip) {
            KbActions(
                onSlot = { i -> strip.getOrNull(i)?.let(onPick) }, onSlotLong = { i -> strip.getOrNull(i)?.let(onLongPick) },
                onUndo = onUndo, onRecentClip = onClip, onLeft = onLeft, onTool = onTool, onBack = onBack, onMic = onMic,
            )
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalKbActions provides actions) { KbView(render) }
    }
}
