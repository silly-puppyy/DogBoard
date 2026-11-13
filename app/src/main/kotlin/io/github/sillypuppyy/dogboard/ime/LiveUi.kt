package io.github.sillypuppyy.dogboard.ime

import android.view.KeyEvent
import dev.patrickgold.florisboard.ime.keyboard3.ImeActions
import dev.patrickgold.florisboard.ime.keyboard3.ImeController
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The design's bar and panel state for the live keyboard (stage 2), and what each tool does.
 * Tools are named by their icon in Kb.dc.html (Kb:663-674).
 */
object LiveUi {
    /** The bar shows the tool row instead of suggestions (its left button, Kb:668). */
    val toolRow = MutableStateFlow(false)

    /** The panel shown in place of the keys: "" (none), "tools", "edit" or "clipboard". */
    val panel = MutableStateFlow("")

    fun closePanel() {
        panel.value = ""
        toolRow.value = false
    }

    /** Runs the tool [icon] (a bar tool, a tools-panel tile, or an editing-panel cell). */
    fun run(icon: String, controller: ImeController) {
        fun action(a: org.k3lp.lib.text.K3Descriptor) = controller.updateStateBlocking { emitDescriptor(a) }
        fun key(code: Int) = controller.updateStateBlocking { state.editor.sendDownUpKeyEvent(code) }
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
            "keyboard_arrow_up" -> action(ImeActions.ArrowUp)
            "keyboard_arrow_down" -> action(ImeActions.ArrowDown)
            "keyboard_arrow_left" -> action(ImeActions.ArrowLeft)
            "keyboard_arrow_right" -> action(ImeActions.ArrowRight)
            "first_page" -> key(KeyEvent.KEYCODE_MOVE_HOME)
            "last_page" -> key(KeyEvent.KEYCODE_MOVE_END)
            "history_on" -> {
                val prefs by dev.patrickgold.florisboard.app.FlorisPreferenceStore
                kotlinx.coroutines.runBlocking { prefs.clipboard.historyEnabled.set(true) }
            }
            "domino_mask" -> {
                // incognito: FlorisBoard's "force incognito" setting, and this field at once
                val prefs by dev.patrickgold.florisboard.app.FlorisPreferenceStore
                val on = !controller.activeState.value.flags.isIncognitoMode
                kotlinx.coroutines.runBlocking { prefs.suggestion.forceIncognitoModeFromDynamic.set(on) }
                controller.updateStateBlocking { state = state.copy(flags = state.flags.withIncognitoMode(on)) }
                closePanel()
            }
            // not wired yet: Select (selection mode), Dev keys
        }
    }
}
