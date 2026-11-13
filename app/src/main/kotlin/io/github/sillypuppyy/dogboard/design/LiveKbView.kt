package io.github.sillypuppyy.dogboard.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
        )
        val render = remember(props) { KbSpec.render(props) }
        KbView(render)
    }
}

private val RET_WORDS = setOf("send", "search", "go", "next", "done", "join", "route", "continue")
