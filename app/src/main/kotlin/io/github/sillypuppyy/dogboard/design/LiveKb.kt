package io.github.sillypuppyy.dogboard.design

import dev.patrickgold.florisboard.ime.keyboard3.ImeActions
import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchKey
import dev.patrickgold.florisboard.ime.keyboard3.touch.isTextKey
import org.k3lp.lib.text.K3String

/**
 * Stage 2 of the design port: the live keyboard's state as design props ([KbProps]), so the
 * ported Kb.dc.html view draws the real keyboard while FlorisBoard keeps touch handling. Layer ids
 * come from tools/layouts/gen_qwerty_en.py (base, shift, caps, symbol-1, symbol-2, numeric, and
 * field variants such as base-email).
 */
object LiveKb {
    /** The design's plane for a layout layer. */
    fun plane(layerId: String): String = when {
        layerId.startsWith("symbol-1") -> "symbol-1"
        layerId.startsWith("symbol-2") -> "symbol-2"
        layerId.startsWith("numeric") -> "numeric"
        // number and phone fields: FlorisBoard's pads (k3 foundation layers-touch.xml), drawn
        // key for key so the drawing matches the touch targets
        layerId == "numpad" -> "numpad"
        layerId == "telpad" -> "telpad"
        layerId == "telpad2" -> "telpad2"
        else -> "base"
    }

    fun shift(state: InputShiftState): String = when (state) {
        InputShiftState.UNSHIFTED -> "off"
        InputShiftState.SHIFTED_MANUAL -> "on"
        InputShiftState.SHIFTED_AUTOMATIC -> "auto"
        InputShiftState.CAPS_LOCK -> "lock"
    }

    /**
     * The design's id for a live key (KbSpec: letters by their lowercase letter, other characters
     * by themselves, shift, bksp, mode-<label>, fn, space, ret), or null if the design has none.
     */
    fun keyId(key: TouchKey, plane: String): String? {
        val output = key.attrs.output
        val target = key.attrs.layerId?.toString()
        return when {
            key.isShiftKey -> "shift"
            output == ImeActions.Backspace -> "bksp"
            output == ImeActions.Enter -> "ret"
            key.attrs.id == TouchKey.FnKeyId -> "fn"
            key.isSuitableForSpaceBarDisplayOverride -> "space"
            target != null && target.startsWith("telpad") -> "mode-$target"
            target != null -> "mode-" + when {
                target.startsWith("symbol-2") -> "#+="
                target.startsWith("symbol-1") -> "123"
                else -> "ABC"
            }
            key.isTextKey() && output is K3String -> output.toText().let { if (plane == "base") it.lowercase() else it }
            else -> null
        }
    }
}
