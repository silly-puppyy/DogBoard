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

package dev.patrickgold.florisboard.ime.keyboard3.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowRightAlt
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPasteGo
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowUp
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.SentimentSatisfiedAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.editor.ImeOptions
import dev.patrickgold.florisboard.ime.keyboard3.ImeIcons
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState
import dev.patrickgold.florisboard.ime.window.ImeWindowMode
import dev.patrickgold.florisboard.ime.window.LocalWindowController
import org.florisboard.lib.compose.icons.ForwardDelete
import org.florisboard.lib.snygg.SnyggQueryAttributes
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.k3lp.lib.text.K3Descriptor

@Composable
fun Icon3(
    value: K3Descriptor,
    modifier: Modifier = Modifier,
    elementName: String? = null,
    attributes: SnyggQueryAttributes = emptyMap(),
) {
    val context = LocalContext.current
    val imeController = LocalImeController.current
    val windowController = LocalWindowController.current

    val imeState by imeController.activeState.collectAsState()
    val imeOptions by remember { derivedStateOf { imeState.editor.info.imeOptions } }
    val inputAttributes by remember { derivedStateOf { imeState.editor.info.inputAttributes } }
    val inputShiftState by remember { derivedStateOf { imeState.flags.inputShiftState } }
    val debugShowDragAndDropHelpers by remember {
        derivedStateOf { imeState.flags.debugShowDragAndDropHelpers }
    }

    val windowConfig by windowController.activeWindowConfig.collectAsState()
    val windowMode by remember { derivedStateOf { windowConfig.mode } }

    val imageVector = remember(value, imeOptions, inputAttributes, inputShiftState, windowMode) {
        when (value) {
            ImeIcons.DragMarker -> {
                if (debugShowDragAndDropHelpers) Icons.Rounded.Close else null
            }
            ImeIcons.Enter -> {
                if (imeOptions.flagNoEnterAction || inputAttributes.flagTextMultiLine) {
                    Icons.AutoMirrored.Rounded.KeyboardReturn
                } else {
                    when (imeOptions.action) {
                        ImeOptions.Action.DONE -> Icons.Rounded.Done
                        ImeOptions.Action.GO -> Icons.AutoMirrored.Rounded.ArrowRightAlt
                        ImeOptions.Action.NEXT -> Icons.AutoMirrored.Rounded.ArrowRightAlt
                        ImeOptions.Action.NONE -> Icons.AutoMirrored.Rounded.KeyboardReturn
                        ImeOptions.Action.PREVIOUS -> Icons.AutoMirrored.Rounded.ArrowRightAlt
                        ImeOptions.Action.SEARCH -> Icons.Rounded.Search
                        ImeOptions.Action.SEND -> Icons.AutoMirrored.Rounded.Send
                        ImeOptions.Action.UNSPECIFIED -> Icons.AutoMirrored.Rounded.KeyboardReturn
                    }
                }
            }
            ImeIcons.Shift -> when (inputShiftState) {
                InputShiftState.UNSHIFTED -> context.vectorResource(R.drawable.ic_shift_off)
                InputShiftState.SHIFTED_MANUAL, InputShiftState.SHIFTED_AUTOMATIC -> {
                    context.vectorResource(R.drawable.ic_shift_on)
                }
                InputShiftState.CAPS_LOCK -> context.vectorResource(R.drawable.ic_shift_lock)
            }
            ImeIcons.ToggleFloatingWindow -> when (windowMode) {
                ImeWindowMode.FIXED -> context.vectorResource(R.drawable.ic_floating_keyboard)
                ImeWindowMode.FLOATING -> context.vectorResource(R.drawable.ic_floating_keyboard_disable)
            }
            // TODO incognito mode???
            // TODO char width/kata/hira icons???
            else -> staticIcon3(value, context)
        }
    }

    if (imageVector != null) {
        SnyggIcon(
            imageVector = imageVector,
            modifier = modifier,
            elementName = elementName,
            attributes = attributes,
        )
    }
}

fun staticIcon3(value: K3Descriptor, context: Context): ImageVector? {
    return when (value) {
        ImeIcons.ArrowDown -> Icons.Rounded.KeyboardArrowDown
        ImeIcons.ArrowLeft -> Icons.AutoMirrored.Rounded.KeyboardArrowLeft
        ImeIcons.ArrowRight -> Icons.AutoMirrored.Rounded.KeyboardArrowRight
        ImeIcons.ArrowUp -> Icons.Rounded.KeyboardArrowUp
        ImeIcons.Backspace -> Icons.AutoMirrored.Outlined.Backspace
        ImeIcons.ClipboardClearPrimaryClip -> Icons.Rounded.DeleteSweep
        ImeIcons.ClipboardCopy -> Icons.Rounded.ContentCopy
        ImeIcons.ClipboardCut -> Icons.Rounded.ContentCut
        ImeIcons.ClipboardPaste -> Icons.Rounded.ContentPasteGo
        ImeIcons.Close -> Icons.Rounded.Close
        ImeIcons.Delete -> Icons.AutoMirrored.Default.ForwardDelete
        ImeIcons.DragMarker -> Icons.Rounded.Close
        ImeIcons.Enter -> Icons.AutoMirrored.Rounded.KeyboardReturn
        ImeIcons.HideKeyboard -> Icons.Rounded.KeyboardHide
        ImeIcons.LanguageSwitch -> Icons.Rounded.Language
        ImeIcons.ClipboardPanel -> Icons.AutoMirrored.Outlined.Assignment
        ImeIcons.MediaPanel -> Icons.Rounded.SentimentSatisfiedAlt
        ImeIcons.TextPanel -> context.vectorResource(R.drawable.ic_abc)
        ImeIcons.Noop -> Icons.Rounded.Close
        ImeIcons.Redo -> Icons.AutoMirrored.Rounded.Redo
        ImeIcons.Shift -> context.vectorResource(R.drawable.ic_shift_off)
        ImeIcons.ShiftLock -> context.vectorResource(R.drawable.ic_shift_lock)
        ImeIcons.ShiftOff -> context.vectorResource(R.drawable.ic_shift_off)
        ImeIcons.ShiftOn -> context.vectorResource(R.drawable.ic_shift_on)
        ImeIcons.ShowKeyboard -> Icons.Rounded.KeyboardDoubleArrowUp // TODO
        ImeIcons.SelectAll -> Icons.Rounded.SelectAll
        ImeIcons.Settings -> Icons.Rounded.Settings
        ImeIcons.SpaceBar -> Icons.Rounded.SpaceBar
        ImeIcons.ToggleActionsOverflow -> Icons.Rounded.MoreHoriz
        ImeIcons.ToggleAutocorrect -> Icons.Rounded.FontDownload
        ImeIcons.ToggleCompactLayout -> context.vectorResource(R.drawable.ic_accessibility_one_handed)
        ImeIcons.ToggleFloatingWindow -> context.vectorResource(R.drawable.ic_floating_keyboard)
        ImeIcons.ToggleResizeMode -> context.vectorResource(R.drawable.ic_resize)
        ImeIcons.Undo -> Icons.AutoMirrored.Rounded.Undo
        ImeIcons.Voice -> Icons.Rounded.KeyboardVoice
        else -> null
    }
}

private fun Context.vectorResource(@DrawableRes id: Int): ImageVector? {
    val theme = this.theme
    return try {
        ImageVector.vectorResource(theme = theme, resId = id, res = this.resources)
    } catch (_: Exception) {
        null
    }
}
