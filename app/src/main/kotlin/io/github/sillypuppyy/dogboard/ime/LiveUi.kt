package io.github.sillypuppyy.dogboard.ime

import android.view.KeyEvent
import dev.patrickgold.florisboard.ime.keyboard3.ImeActions
import dev.patrickgold.florisboard.ime.keyboard3.ImeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The design's bar and panel state for the live keyboard (stage 2), and what each tool does.
 * Tools are named by their icon in Kb.dc.html (Kb:663-674).
 */
object LiveUi {
    /** Settings written from bar buttons: off the main thread, so a tap never waits on the disk. */
    private val prefsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The bar shows the tool row instead of suggestions (its left button, Kb:668). */
    val toolRow = MutableStateFlow(false)

    /** The panel shown in place of the keys: "" (none), "tools", "edit" or "clipboard". */
    val panel = MutableStateFlow("")

    /** The space-bar trackpad is in use (the bar fades, Kb:511-512). */
    val trackpad = MutableStateFlow(false)

    /** Selection mode in the text-editing panel: the arrows extend the selection. */
    val selecting = MutableStateFlow(false)
    private var selAnchor = 0
    private var selEnd = 0

    fun closePanel() {
        panel.value = ""
        toolRow.value = false
        selecting.value = false
    }

    /** Runs the tool [icon] (a bar tool, a tools-panel tile, or an editing-panel cell). */
    fun run(icon: String, controller: ImeController) {
        fun action(a: org.k3lp.lib.text.K3Descriptor) = controller.updateStateBlocking { emitDescriptor(a) }
        fun key(code: Int, meta: Int = 0) = controller.updateStateBlocking { state.editor.sendDownUpKeyEvent(code, meta) }
        // in selection mode left/right move the selection's far end from where Select was tapped
        // (fields ignore shift+arrow sent by a keyboard, so the selection is set directly)
        fun arrow(action: org.k3lp.lib.text.K3Descriptor, code: Int) {
            val step = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
            if (!selecting.value || step == 0) return action(action)
            selEnd = (selEnd + step).coerceAtLeast(0)
            controller.updateStateBlocking { state.editor.setSelection(minOf(selAnchor, selEnd), maxOf(selAnchor, selEnd)) }
        }
        when (icon) {
            "undo" -> action(ImeActions.Undo)
            "redo" -> action(ImeActions.Redo)
            "select_all" -> action(ImeActions.SelectAll)
            "content_copy" -> action(ImeActions.ClipboardCopy)
            "content_cut" -> action(ImeActions.ClipboardCut)
            "content_paste" -> if (panel.value == "edit") action(ImeActions.ClipboardPaste) else panel.value = "clipboard"
            "mood" -> { closePanel(); action(ImeActions.ShowMediaPanel) }
            "front_hand" -> { closePanel(); action(ImeActions.ToggleCompactLayout) }
            "picture_in_picture" -> { closePanel(); action(ImeActions.ToggleFloatingWindow) }
            "open_with" -> { closePanel(); action(ImeActions.ToggleResizeMode) }
            "palette" -> { closePanel(); action(ImeActions.Settings) }
            "more_horiz" -> panel.value = "tools"
            "text_select_start" -> panel.value = "edit"
            "keyboard_arrow_up" -> arrow(ImeActions.ArrowUp, KeyEvent.KEYCODE_DPAD_UP)
            "keyboard_arrow_down" -> arrow(ImeActions.ArrowDown, KeyEvent.KEYCODE_DPAD_DOWN)
            "keyboard_arrow_left" -> arrow(ImeActions.ArrowLeft, KeyEvent.KEYCODE_DPAD_LEFT)
            "keyboard_arrow_right" -> arrow(ImeActions.ArrowRight, KeyEvent.KEYCODE_DPAD_RIGHT)
            "Select" -> {
                selecting.value = !selecting.value
                val sel = controller.activeState.value.content.selection
                selAnchor = sel.end
                selEnd = sel.end
            }
            "first_page" -> key(KeyEvent.KEYCODE_MOVE_HOME)
            "last_page" -> key(KeyEvent.KEYCODE_MOVE_END)
            "history_on" -> {
                val prefs by dev.patrickgold.florisboard.app.FlorisPreferenceStore
                prefsScope.launch { prefs.clipboard.historyEnabled.set(true) }
            }
            "domino_mask" -> {
                // incognito: FlorisBoard's "force incognito" setting, and this field at once
                val prefs by dev.patrickgold.florisboard.app.FlorisPreferenceStore
                val on = !controller.activeState.value.flags.isIncognitoMode
                prefsScope.launch { prefs.suggestion.forceIncognitoModeFromDynamic.set(on) }
                controller.updateStateBlocking { state = state.copy(flags = state.flags.withIncognitoMode(on)) }
                closePanel()
            }
            // not wired yet: Dev keys
        }
    }
}
