/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.keyboard3

import android.content.ClipDescription
import android.content.ContentUris
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.ImeOptions
import org.k3lp.runtime.K3Editor
import org.k3lp.runtime.K3SurroundingText
import org.k3lp.runtime.K3TextRange
import java.lang.ref.WeakReference
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

open class ImeEditor(
    val ic: WeakReference<InputConnection>,
    val info: FlorisEditorInfo,
) : K3Editor {
    fun getSurroundingText(charsBefore: Int, charsAfter: Int): K3SurroundingText {
        val ic = ic.get() ?: return K3SurroundingText.Empty
        // TODO maybe use eet for getSurroundingText??
//        val eet = ic.getExtractedText(
//            ExtractedTextRequest().apply {
//                token = -1
//                flags = 0
//                hintMaxLines = 10
//                hintMaxChars = 10000
//            },
//            0,
//        )
//        eet.
        val textBeforeCursor = ic.getTextBeforeCursor(charsBefore, 0) ?: return K3SurroundingText.Empty
        val textAfterCursor = ic.getTextAfterCursor(charsAfter, 0) ?: return K3SurroundingText.Empty
        val textSelected = ic.getSelectedText(0) ?: ""
        //val surroundingText = TextUtils.concat(textBeforeCursor, textSelected, textAfterCursor)
        return K3SurroundingText(
            textBefore = textBeforeCursor.toString(),
            textSelected = textSelected.toString(),
            textAfter = textAfterCursor.toString(),
        )
    }

    // TODO this function is very heavy => we need to optimize k3lp to provide:
    //  insertAtSelection() (for simple appends)
    //  deleteAroundSelection() for bksp/fw delete
    //  in some cases replaceText() is needed due to the transform rules, but we abuse it waaaay too much
    override fun replaceText(
        range: IntRange,
        text: String,
        newSelection: K3TextRange,
        newComposition: K3TextRange?,
    ) {
        val ic = ic.get() ?: return
        val replaced = range.last + 1 - range.first
        // Terminals (Termux, SSH clients) keep no text: a replacement there is appended ("dir" arrived as
        // "ddidir"). Such a field types plainly: raw-input fields from the start, others
        // once we are about to replace text the field says it doesn't have, or a replacement was appended.
        // Only the first few replacements of a field are checked, and never inside a batch: reading the field in
        // the middle of the keyboard's batched edit made the app draw every step of it (a picked suggestion
        // erased the typed word letter by letter before the new one appeared)
        // Not over a selection: the text replaced is then the selection, not the text before the cursor, and a paste
        // over a selected word looked like an appended replacement ("hello [world]" + paste "hello world": the field read
        // "hello hello world", which ends with what was before plus the new text; it was "repaired" with 16 deletes and
        // the field set to plain typing)
        if (!plainMode && replaced > 0 && checksLeft > 0 && batchDepth == 0 && ic.getSelectedText(0).isNullOrEmpty()) {
            val before = ic.getTextBeforeCursor(replaced + CHECK_CONTEXT, 0)?.toString()
            if (before.isNullOrEmpty()) {
                switchToPlain("keeps no text")
            } else if (before.length < replaced) {
                // the field holds less than we meant to replace, but not nothing: the app changed it behind
                // us (a chat empties its field after send; a test clears it with key events), so our copy of
                // the previous word is stale. Only this replacement is skipped: switching the whole field to
                // plain typing turned autocorrect off for the rest of the conversation (emulator 2026-11-19:
                // "the field keeps no text" in an ordinary text field, after which nothing was corrected)
                android.util.Log.i("DogBoardEditor", "${info.packageName}: stale replacement skipped ($replaced chars, the field has ${before.length})")
                return
            } else {
                checksLeft--
                writeRegion(ic, range, text, newSelection, newComposition)
                val expected = before.dropLast(replaced) + text
                val after = ic.getTextBeforeCursor(expected.length + replaced, 0)?.toString()
                if (after != null && !after.endsWith(expected) && after.endsWith(before + text)) {
                    // the old text was kept and the new appended: take the extra back
                    ic.finishComposingText()
                    repeat(replaced + text.length) { sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL) }
                    ic.commitText(text, 1)
                    switchToPlain("ignores replacements")
                }
                remember(range, text)
                return
            }
        }
        if (plainMode) plainReplace(ic, range, replaced, text) else writeRegion(ic, range, text, newSelection, newComposition)
        remember(range, text)
    }

    private fun writeRegion(ic: InputConnection, range: IntRange, text: String, newSelection: K3TextRange, newComposition: K3TextRange?) {
        ic.beginBatchEdit()
        ic.setComposingRegion(range.first, range.last + 1)
        ic.commitText(text, 1)
        ic.setSelection(newSelection.start, newSelection.end)
        setComposition(newComposition)
        ic.endBatchEdit()
    }

    /**
     * DogBoard: plain typing for an editor that cannot replace text: only the difference goes out, the old
     * text's unmatched tail as backspaces (which terminals understand) and the rest of the new text. The old
     * text is what was last sent there (a terminal can't tell us), else what the field reports.
     */
    private fun plainReplace(ic: InputConnection, range: IntRange, replaced: Int, text: String) {
        val old = if (replaced == 0) "" else if (range.first == lastStart && lastText.length == replaced) lastText
            else ic.getTextBeforeCursor(replaced, 0)?.toString().orEmpty()
        var p = 0
        while (p < old.length && p < text.length && old[p] == text[p]) p++
        ic.finishComposingText()
        repeat(replaced - p) { sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL) }
        if (p < text.length) ic.commitText(text.substring(p), 1)
    }

    private fun remember(range: IntRange, text: String) { lastStart = range.first; lastText = text }

    private fun switchToPlain(why: String) {
        plainMode = true
        checksLeft = 0
        android.util.Log.i("DogBoardEditor", "${info.packageName}: the field $why; plain typing (inputType 0x%x)".format(info.base.inputType))
    }

    private var lastStart = -1
    private var lastText = ""

    /** DogBoard: this field can't replace text (see [replaceText]); the word engine stays off in it. */
    @Volatile var plainMode = info.isRawInputEditor
        private set
    private var checksLeft = CHECKS

    /** DogBoard: runs [block]'s edits as one batch, so the app sees one change (batch edits nest). */
    fun <T> batch(block: () -> T): T {
        val ic = ic.get() ?: return block()
        ic.beginBatchEdit()
        batchDepth++
        try {
            return block()
        } finally {
            batchDepth--
            ic.endBatchEdit()
        }
    }

    /** DogBoard: the keyboard's own batched edits in progress (see [batch]). */
    private var batchDepth = 0

    /**
     * DogBoard: tells the app that [old] at [offset] was autocorrected to [new]. Android's text views
     * flash a highlight over it in their own highlight colour, fading over 400 ms (Editor
     * CorrectionHighlighter), as Gboard does; apps without it ignore the call.
     */
    fun commitCorrection(offset: Int, old: String, new: String) {
        val ic = ic.get() ?: return
        runCatching { ic.commitCorrection(android.view.inputmethod.CorrectionInfo(offset, old, new)) }
    }

    /** DogBoard: selects [start]..[end] in the field (the text-editing panel's selection mode). */
    fun setSelection(start: Int, end: Int) {
        val ic = ic.get() ?: return
        ic.setSelection(start, end)
    }

    fun deleteSurroundingText(charsBefore: Int, charsAfter: Int) {
        val ic = ic.get() ?: return
        ic.deleteSurroundingText(charsBefore, charsAfter)
    }

    fun performClipboardCopy(): Boolean {
        val ic = ic.get() ?: return false
        return ic.performContextMenuAction(android.R.id.copy)
    }

    fun performClipboardCut(): Boolean {
        val ic = ic.get() ?: return false
        return ic.performContextMenuAction(android.R.id.cut)
    }

    fun performClipboardPaste(): Boolean {
        val ic = ic.get() ?: return false
        return ic.performContextMenuAction(android.R.id.paste)
    }

    fun performClipboardPasteMedia(item: ClipboardItem): Boolean {
        val ic = ic.get() ?: return false
        val uri = item.uri ?: return false
        val mimeTypes = item.mimeTypes
        val id = ContentUris.parseId(uri)
        val appContext = FlorisApplication.getHack() // TODO this is a hack
        val file = ClipboardFileStorage.getFileForId(appContext, id)
        if (!file.exists()) return false
        val inputContentInfo = InputContentInfoCompat(
            uri,
            ClipDescription("clipboard media file", mimeTypes.toTypedArray()),
            null,
        )
        ic.finishComposingText()
        val flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
        return InputConnectionCompat.commitContent(ic, info.base, inputContentInfo, flags, null)
    }

    /** DogBoard: a file given to the app as content ("Paste as file"), as [performClipboardPasteMedia] does. */
    fun performPasteFile(uri: android.net.Uri, mime: String, name: String): Boolean {
        val ic = ic.get() ?: return false
        val inputContentInfo = InputContentInfoCompat(uri, ClipDescription(name, arrayOf(mime)), null)
        ic.finishComposingText()
        return InputConnectionCompat.commitContent(ic, info.base, inputContentInfo, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
    }

    fun performSelectAll(): Boolean {
        val ic = ic.get() ?: return false
        ic.finishComposingText()
        return if (info.isRawInputEditor) {
            sendDownUpKeyEvent(KeyEvent.KEYCODE_A, meta(ctrl = true))
        } else {
            ic.performContextMenuAction(android.R.id.selectAll)
        }
    }

    fun performUndo(): Boolean {
        return sendDownUpKeyEvent(KeyEvent.KEYCODE_Z, meta(ctrl = true))
    }

    fun performRedo(): Boolean {
        return sendDownUpKeyEvent(KeyEvent.KEYCODE_Z, meta(ctrl = true, shift = true))
    }

    fun performEditorAction(action: ImeOptions.Action) {
        val ic = ic.get() ?: return
        ic.performEditorAction(action.toInt())
    }

    private fun meta(
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ): Int {
        var metaState = 0
        if (ctrl) {
            metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        }
        if (alt) {
            metaState = metaState or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        if (shift) {
            metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }
        return metaState
    }

    private fun InputConnection.sendDownKeyEvent(keyCode: Int, metaState: Int, downTime: Long) {
        sendKeyEvent(
            KeyEvent(
                downTime, downTime,
                KeyEvent.ACTION_DOWN, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    private fun InputConnection.sendUpKeyEvent(keyCode: Int, metaState: Int, downTime: Long, upTime: Long) {
        sendKeyEvent(
            KeyEvent(
                downTime, upTime,
                KeyEvent.ACTION_UP, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    fun sendDownUpKeyEvent(keyCode: Int, metaState: Int = 0): Boolean {
        val ic = ic.get() ?: return false
        val downTime = SystemClock.uptimeMillis()
        ic.sendDownKeyEvent(keyCode, metaState, downTime)
        val upTime = SystemClock.uptimeMillis()
        ic.sendUpKeyEvent(keyCode, metaState, downTime, upTime)
        return true
    }

    override fun setComposition(newComposition: K3TextRange?) {
        val ic = ic.get() ?: return
        // DogBoard: the current word is tracked here, not marked in the app: a composing region
        // makes apps underline the word being typed, which iOS (and Gboard, FUTO) don't show.
        // replaceText still swaps the word through a composing region within one batch edit.
        ic.finishComposingText()
    }

    private inline fun InputConnection.batchEdit(block: () -> Unit) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        requestCursorUpdates(0)
        beginBatchEdit()
        block()
        endBatchEdit()
        requestCursorUpdates(CURSOR_UPDATES)
    }

    companion object {
        /**
         * The input connection update cursor flags.
         *
         * MUST NOT contain filter flags, as these behave strangely with some editors!!
         */
        const val CURSOR_UPDATES = InputConnection.CURSOR_UPDATE_MONITOR or InputConnection.CURSOR_UPDATE_IMMEDIATE

        /** DogBoard: how many replacements per field are checked for an editor that appends instead. */
        private const val CHECKS = 6
        private const val CHECK_CONTEXT = 32

        val Disconnected = ImeEditor(
            ic = WeakReference(null),
            info = FlorisEditorInfo.Unspecified,
        )
    }
}
