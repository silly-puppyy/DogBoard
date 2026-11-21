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

package dev.patrickgold.florisboard.ime.keyboard3.interaction

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.ime.keyboard3.ImeActions
import dev.patrickgold.florisboard.ime.keyboard3.interaction.InteractionController.ToastHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import io.github.sillypuppyy.dogboard.feel.HapticPlayer
import io.github.sillypuppyy.dogboard.feel.KeyFeedbackClass
import io.github.sillypuppyy.dogboard.feel.Timings
import org.florisboard.lib.android.AndroidVersion
import org.florisboard.lib.android.systemServiceOrNull
import org.k3lp.lib.text.K3String
import org.k3lp.lib.text.K3StringOrDescriptor
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.milliseconds

private class AndroidInteractionController(
    val context: WeakReference<Context>,
    val composeView: WeakReference<View>,
    val audioManager: WeakReference<AudioManager>,
    scope: CoroutineScope,
    private val prefs: FlorisPreferenceModel,
    systemTimingOptionsFlow: StateFlow<InteractionTimingOptions>,
) : InteractionController {
    override val activeSystemTimingOptions: StateFlow<InteractionTimingOptions> = systemTimingOptionsFlow

    override val activeTimingOptions = combine(
        activeSystemTimingOptions,
        prefs.keyboard.longPressTimeoutUseSystem.asFlow(),
        prefs.keyboard.longPressTimeout.asFlow(),
        prefs.keyboard.multiTapTimeoutUseSystem.asFlow(),
        prefs.keyboard.multiTapTimeout.asFlow(),
    ) { system, longPressTimeoutUseSystem, longPressTimeout, multiTapTimeOutUseSystem, multiTapTimeout ->
        InteractionTimingOptions(
            keyRepeatTimeout = system.keyRepeatTimeout,
            keyRepeatDelay = system.keyRepeatDelay,
            longPressTimeout = when {
                longPressTimeoutUseSystem -> system.longPressTimeout
                else -> longPressTimeout.milliseconds
            },
            multiTapTimeout = when {
                multiTapTimeOutUseSystem -> system.multiTapTimeout
                else -> multiTapTimeout.milliseconds
            },
            multiPressTimeout = system.multiPressTimeout,
            doubleTapTimeout = system.doubleTapTimeout,
        )
    }.combine(prefs.typing.capsLockDoubleTapMs.asFlow()) { options, capsLockMs ->
        // DogBoard: the caps-lock double-tap window is a setting (Feel › Advanced)
        options.copy(doubleTapTimeout = capsLockMs.milliseconds)
    }.combine(prefs.typing.deleteFirstMs.asFlow()) { options, ms ->
        // DogBoard: Fine-tuning "Start repeating after" (a held key's first repeat)
        options.copy(keyRepeatTimeout = ms.milliseconds)
    }.combine(prefs.typing.deleteCharMs.asFlow()) { options, ms ->
        options.copy(keyRepeatDelay = ms.milliseconds)
    }.stateIn(scope, SharingStarted.Eagerly, InteractionTimingOptions.Default)

    override val activeFeedbackOptions = combine<Any, InteractionFeedbackOptions>(
        // Audio
        prefs.inputFeedback.audioEnabled.asFlow(),
        prefs.inputFeedback.audioVolume.asFlow(),
        prefs.inputFeedback.audioFeatKeyPress.asFlow(),
        prefs.inputFeedback.audioFeatKeyPress.asFlow(), // TODO should be release
        prefs.inputFeedback.audioFeatKeyRepeatedAction.asFlow(),
        prefs.inputFeedback.audioFeatKeyLongPress.asFlow(),
        prefs.inputFeedback.audioFeatGestureSwipe.asFlow(),
        prefs.inputFeedback.audioFeatGestureMovingSwipe.asFlow(),
        // Haptic
        prefs.inputFeedback.hapticEnabled.asFlow(),
        prefs.inputFeedback.hapticFeatKeyPress.asFlow(),
        prefs.inputFeedback.hapticFeatKeyPress.asFlow(), // TODO should be release
        prefs.inputFeedback.hapticFeatKeyRepeatedAction.asFlow(),
        prefs.inputFeedback.hapticFeatKeyLongPress.asFlow(),
        prefs.inputFeedback.hapticFeatGestureSwipe.asFlow(),
        prefs.inputFeedback.hapticFeatGestureMovingSwipe.asFlow(),
    ) { values ->
        val audioEnabled = values[0] as Boolean
        val audioVolume = values[1] as Int
        val audioKeyPress = values[2] as Boolean
        val audioKeyRelease = values[3] as Boolean
        val audioKeyRepeat = values[4] as Boolean
        val audioLongPress = values[5] as Boolean
        val audioGestureSwipe = values[6] as Boolean
        val audioTextHandleMove = values[7] as Boolean
        val hapticEnabled = values[8] as Boolean
        val hapticKeyPress = values[9] as Boolean
        val hapticKeyRelease = values[10] as Boolean
        val hapticKeyRepeat = values[11] as Boolean
        val hapticLongPress = values[12] as Boolean
        val hapticGestureSwipe = values[13] as Boolean
        val hapticTextHandleMove = values[14] as Boolean

        buildInteractionFeedbackOptions {
            if (audioEnabled) {
                setAudioVolume(audioVolume)
                if (audioKeyPress) enableAudioFeedback(InteractionKind.KeyPress)
                if (audioKeyRelease) enableAudioFeedback(InteractionKind.KeyRelease)
                if (audioKeyRepeat) enableAudioFeedback(InteractionKind.KeyRepeat)
                if (audioLongPress) enableAudioFeedback(InteractionKind.LongPress)
                if (audioGestureSwipe) enableAudioFeedback(InteractionKind.GestureSwipe)
                if (audioTextHandleMove) enableAudioFeedback(InteractionKind.TextHandleMove)
            }
            if (hapticEnabled) {
                if (hapticKeyPress) enableHapticFeedback(InteractionKind.KeyPress)
                if (hapticKeyRelease) enableHapticFeedback(InteractionKind.KeyRelease)
                if (hapticKeyRepeat) enableHapticFeedback(InteractionKind.KeyRepeat)
                if (hapticLongPress) enableHapticFeedback(InteractionKind.LongPress)
                if (hapticGestureSwipe) enableHapticFeedback(InteractionKind.GestureSwipe)
                if (hapticTextHandleMove) enableHapticFeedback(InteractionKind.TextHandleMove)
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, InteractionFeedbackOptions.Fallback)

    override fun performAudioFeedback(kind: InteractionKind, output: K3StringOrDescriptor?) {
        val feedbackOptions = activeFeedbackOptions.value
        val composeView = composeView.get() ?: return
        if (!composeView.isSoundEffectsEnabled) return
        val audioManager = audioManager.get() ?: return
        val effect = when (output) {
            ImeActions.Backspace -> AudioManager.FX_KEYPRESS_DELETE
            ImeActions.Enter -> AudioManager.FX_KEYPRESS_RETURN
            InteractionTimingOptions.ASCII_SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        val factor = when (kind) {
            InteractionKind.KeyPress -> 1.0
            InteractionKind.KeyRelease -> 0.7
            InteractionKind.KeyRepeat -> 0.4
            InteractionKind.LongPress -> 0.7
            InteractionKind.GestureSwipe -> 0.7
            InteractionKind.TextHandleMove -> 0.4
        }
        val volume = (feedbackOptions.getAudioVolume() * factor) / 100.0
        if (volume in 0.01..1.00) {
            // DogBoard: our own key sounds (Settings › Sound and vibration › Key sound), else Android's
            val style = prefs.inputFeedback.audioStyle.get()
            if (style != "system") context.get()?.let { ctx ->
                io.github.sillypuppyy.dogboard.feel.KeySounds.prepare(ctx, style)
                val soundKind = when (feedbackClassFor(kind, output)) {
                    io.github.sillypuppyy.dogboard.feel.KeyFeedbackClass.DELETE, io.github.sillypuppyy.dogboard.feel.KeyFeedbackClass.DELETE_REPEAT,
                    io.github.sillypuppyy.dogboard.feel.KeyFeedbackClass.DELETE_RAPID -> io.github.sillypuppyy.dogboard.feel.KeySounds.Kind.DELETE
                    io.github.sillypuppyy.dogboard.feel.KeyFeedbackClass.SEPARATOR -> io.github.sillypuppyy.dogboard.feel.KeySounds.Kind.SPACE
                    io.github.sillypuppyy.dogboard.feel.KeyFeedbackClass.OTHER -> io.github.sillypuppyy.dogboard.feel.KeySounds.Kind.MODIFIER
                    else -> io.github.sillypuppyy.dogboard.feel.KeySounds.Kind.KEY
                }
                io.github.sillypuppyy.dogboard.feel.KeySounds.play(soundKind, volume.toFloat(), preview = prefs.inputFeedback.audioBypassSilent.get())
                return
            }
            audioManager.playSoundEffect(effect, volume.toFloat())
        }
    }

    // DogBoard: key presses use DogBoard's haptic profile
    private val hapticPlayer by lazy { context.get()?.let { HapticPlayer(it) } }

    private fun feedbackClassFor(kind: InteractionKind, output: K3StringOrDescriptor?): KeyFeedbackClass? {
        return when (kind) {
            InteractionKind.KeyPress -> when (output) {
                null -> KeyFeedbackClass.OTHER
                ImeActions.Backspace, ImeActions.BackspaceWord -> KeyFeedbackClass.DELETE
                ImeActions.Enter, InteractionTimingOptions.ASCII_SPACE -> KeyFeedbackClass.SEPARATOR
                is K3String -> KeyFeedbackClass.TEXT
                else -> KeyFeedbackClass.OTHER
            }
            InteractionKind.KeyRepeat -> when (output) {
                ImeActions.Backspace -> KeyFeedbackClass.DELETE_REPEAT
                ImeActions.BackspaceWord -> KeyFeedbackClass.DELETE_RAPID
                else -> KeyFeedbackClass.OTHER
            }
            else -> null
        }
    }

    override fun performHapticFeedback(kind: InteractionKind, output: K3StringOrDescriptor?) {
        val composeView = composeView.get() ?: return
        if (!composeView.isHapticFeedbackEnabled) return
        // DogBoard: no haptic on key release; presses and repeats use the DogBoard profile
        if (kind == InteractionKind.KeyRelease) return
        val feedbackClass = feedbackClassFor(kind, output)
        val style = prefs.inputFeedback.hapticStyle.get()
        val own = style == io.github.sillypuppyy.dogboard.feel.HapticStyle.SOFT || style == io.github.sillypuppyy.dogboard.feel.HapticStyle.CRISP
        if (own && feedbackClass != null && hapticPlayer?.play(feedbackClass, crisp = style == io.github.sillypuppyy.dogboard.feel.HapticStyle.CRISP) == true) return
        val hfc = when (kind) {
            InteractionKind.KeyPress -> HFC_KEYBOARD_PRESS
            InteractionKind.KeyRelease -> HFC_KEYBOARD_RELEASE
            InteractionKind.KeyRepeat -> HFC_TEXT_HANDLE_MOVE
            InteractionKind.LongPress -> HFC_LONG_PRESS
            InteractionKind.GestureSwipe -> 0
            InteractionKind.TextHandleMove -> HFC_TEXT_HANDLE_MOVE
        }
        composeView.performHapticFeedback(hfc)
    }

    override suspend fun showToast(text: String, type: InteractionController.ToastType): ToastHandle {
        val context = context.get() ?: return ToastHandleImpl.NoToast
        val duration = when (type) {
            InteractionController.ToastType.SHORT -> Toast.LENGTH_SHORT
            InteractionController.ToastType.LONG -> Toast.LENGTH_LONG
        }
        return withContext(Dispatchers.Main.immediate) {
            val toast = Toast.makeText(context, text, duration)
            toast.show()
            ToastHandleImpl(toast)
        }
    }

    companion object {
        private val HFC_KEYBOARD_PRESS: Int = when {
            AndroidVersion.ATLEAST_API27_O_MR1 -> HapticFeedbackConstants.KEYBOARD_PRESS
            else -> HapticFeedbackConstants.KEYBOARD_TAP
        }

        private val HFC_KEYBOARD_RELEASE: Int = when {
            AndroidVersion.ATLEAST_API27_O_MR1 -> HapticFeedbackConstants.KEYBOARD_RELEASE
            else -> HapticFeedbackConstants.KEYBOARD_TAP
        }

        private const val HFC_LONG_PRESS: Int = HapticFeedbackConstants.LONG_PRESS

        // TODO TEXT_HANDLE_MOVE is system-wise bound to normal touch haptics, not keyboard haptics
        private val HFC_TEXT_HANDLE_MOVE: Int = when {
            AndroidVersion.ATLEAST_API27_O_MR1 -> HapticFeedbackConstants.TEXT_HANDLE_MOVE
            else -> HapticFeedbackConstants.KEYBOARD_TAP
        }
    }

    private class ToastHandleImpl(val toast: Toast?) : ToastHandle {
        override suspend fun hide() {
            toast?.cancel()
        }

        companion object {
            val NoToast = ToastHandleImpl(null)
        }
    }
}

@Composable
fun rememberAndroidInteractionController(
    prefs: FlorisPreferenceModel,
): InteractionController {
    val composeView = LocalView.current
    val context = LocalContext.current
    val audioManager = context.systemServiceOrNull(AudioManager::class)
    val scope = rememberCoroutineScope()

    val systemTimingOptionsFlow = MutableStateFlow(androidTimingOptions())
    LifecycleResumeEffect(Unit) {
        systemTimingOptionsFlow.value = androidTimingOptions()
        onPauseOrDispose { }
    }

    return remember {
        AndroidInteractionController(
            WeakReference(context),
            WeakReference(composeView),
            WeakReference(audioManager),
            scope,
            prefs,
            systemTimingOptionsFlow.asStateFlow(),
        )
    }
}

// DogBoard: key repeat, long-press and double-tap timings come from DogBoard's feel profile
// instead of Android's ViewConfiguration defaults.
private fun androidTimingOptions(): InteractionTimingOptions {
    return InteractionTimingOptions(
        keyRepeatTimeout = Timings.DeleteFirstRepeat,
        keyRepeatDelay = Timings.DeleteCharInterval,
        longPressTimeout = Timings.LongPress,
        // no system constant available for multi tap timeout on Android
        multiTapTimeout = InteractionTimingOptions.Default.multiTapTimeout,
        multiPressTimeout = when {
            AndroidVersion.ATLEAST_API31_S -> ViewConfiguration.getMultiPressTimeout()
            else -> 300
        }.milliseconds,
        doubleTapTimeout = Timings.CapsLockDoubleTap,
    )
}
