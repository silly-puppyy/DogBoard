package io.github.sillypuppyy.dogboard.design

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
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    BoxWithConstraints(modifier.fillMaxSize()) {
        // nothing to draw until the key area has a real size (the first measure can be empty)
        if (!maxWidth.value.isFinite() || maxWidth.value < 100f || maxHeight.value < 100f) return@BoxWithConstraints
        val plane = LiveKb.plane(layerId)
        val pressed = pressedKey?.let { LiveKb.keyId(it, plane) }.orEmpty()
        val extended = longPress?.extendedKeys?.isNotEmpty() == true
        val props = KbProps(
            dark = dark,
            plane = plane,
            field = LiveKb.field(layerId),
            shift = LiveKb.shift(shiftState),
            ret = if (enterAction in RET_WORDS) enterAction else "return",
            pressed = pressed,
            // the key preview while a character key is held (FlorisBoard's simple popup), the
            // long-press bar once its extended keys are out
            preview = if (longPress?.shouldShowSimplePopup() == true && !extended) pressed else "",
            longpress = if (extended) pressed else "",
            lpSel = longPress?.extendedFocusedIndex ?: 0,
            width = maxWidth.value,
            keyH = maxHeight.value,
            only = "live",
            panel = panel,
            clips = clips,
            panelVariant = panelVariant,
            fnKey = fnKey,
        )
        val render = remember(props) { KbSpec.render(props) }
        androidx.compose.runtime.CompositionLocalProvider(LocalKbActions provides actions) { KbView(render) }
    }
}

private val RET_WORDS = setOf("send", "search", "go", "next", "done", "join", "route", "continue")

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
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
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
        val props = KbProps(
            dark = dark, slots = if (toolRow || incognitoNote) null else slots, bar = if (toolRow) "tools" else "empty",
            panel = panel, width = maxWidth.value, only = "bar", incognito = incognito,
        )
        val render = remember(props) { KbSpec.render(props) }
        val actions = remember(strip, onPick, onLeft, onTool, onBack, onMic) {
            KbActions(onSlot = { i -> strip.getOrNull(i)?.let(onPick) }, onLeft = onLeft, onTool = onTool, onBack = onBack, onMic = onMic)
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalKbActions provides actions) { KbView(render) }
    }
}
