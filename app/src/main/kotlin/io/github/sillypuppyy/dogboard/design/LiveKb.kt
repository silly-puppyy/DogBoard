package io.github.sillypuppyy.dogboard.design

import dev.patrickgold.florisboard.ime.keyboard3.ImeActions
import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchKey
import dev.patrickgold.florisboard.ime.keyboard3.touch.isFnKeyId
import dev.patrickgold.florisboard.ime.keyboard3.touch.isTextKey
import org.k3lp.lib.text.K3String

/**
 * Stage 2 of the design port: the live keyboard's state as design props ([KbProps]), so the
 * ported Kb.dc.html view draws the real keyboard while FlorisBoard keeps touch handling. Layer ids
 * come from tools/layouts/gen_layouts.py (base, shift, caps, symbol-1, symbol-2, numeric, and
 * field variants such as base-email).
 */
object LiveKb {
    /** The symbol keys whose output the layout changes (Arabic ١ ، ؛ ؟: gen_layouts.py native_punct), as Latin -> native. */
    private val SYMBOL_IDS = ("1234567890".associate { "s-$it" to it.toString() } +
        mapOf("s-semicolon" to ";", "s-question" to "?", "comma-classic" to ",", "period-classic" to "."))

    fun symbols(model: org.k3lp.model.K3Model): Map<String, String> = buildMap<String, String> {
        for ((id, latin) in SYMBOL_IDS) {
            val out = (model.keys.byKeyId[org.k3lp.model.key.K3KeyId(id)]?.output as? K3String)?.toText() ?: continue
            if (out != latin) put(latin, out)
        }
    }

    /**
     * The layout's letter rows (three, or four: Pashto, Armenian) as the touch layout defines them (keys, widths, gaps, shift and
     * delete), for [KbProps.letterRows]: what is drawn is what is hit, for every layout. Rows narrower
     * than the widest are centred, as the touch layout centres them. Null when the layout has no
     * letter planes or a key the drawing doesn't know (the English rows are drawn then).
     */
    fun letterRows(model: org.k3lp.model.K3Model): List<List<KbLetterKey>>? {
        val layers = model.layersByForm.touch.firstOrNull()?.layers ?: return null
        val base = layers[dev.patrickgold.florisboard.ime.keyboard3.ImeLayerIds.Base]?.rows ?: return null
        val shift = layers[dev.patrickgold.florisboard.ime.keyboard3.ImeLayerIds.Shift]?.rows
        if (base.size < 4) return null
        // every row of the base layer but the bottom one (take(3) dropped a fourth letter row: drawn 5 dp off)
        val rows = base.dropLast(1).mapIndexed { i, row ->
            row.mapIndexed { j, id ->
                val key = model.keys.byKeyId[id] ?: return null
                val w = key.width.toFloat()
                when {
                    key.gap -> KbLetterKey("gap", w = w)
                    key.layerId != null -> KbLetterKey("shift", w = w)
                    key.output == ImeActions.Backspace -> KbLetterKey("bksp", w = w)
                    else -> {
                        // composed (the layout library keeps "ü" as u + U+0308)
                        fun nfc(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC)
                        val text = nfc((key.output as? K3String)?.toText() ?: return null)
                        val shifted = nfc(shift?.getOrNull(i)?.getOrNull(j)?.let { model.keys.byKeyId[it]?.output as? K3String }?.toText().orEmpty())
                        KbLetterKey("char", text, shifted, w)
                    }
                }
            }
        }
        val full = rows.maxOf { r -> r.sumOf { it.w.toDouble() } }.toFloat()
        return rows.map { r ->
            val side = (full - r.sumOf { it.w.toDouble() }.toFloat()) / 2
            if (side < 0.005f) r else listOf(KbLetterKey("gap", w = side)) + r + listOf(KbLetterKey("gap", w = side))
        }
    }

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
            key.attrs.id.isFnKeyId() -> "fn"
            key.attrs.id.value == "to-symbol-1-classic" -> "mode-?123"
            // the space bar by what it types too: its display flag isn't set on every layout, and it then came out as
            // " ", which no drawn key has, so a held space bar showed no press on any theme (2026-11-24)
            key.isSuitableForSpaceBarDisplayOverride || (output is K3String && output.toText() == " ") -> "space"
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
