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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFold
import androidx.compose.ui.util.fastForEach
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard3.ImeActions
import dev.patrickgold.florisboard.ime.keyboard3.ImeController
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import dev.patrickgold.florisboard.ime.keyboard3.touch.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchKey
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchKeyboard
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.sillypuppyy.dogboard.feel.DeleteRepeat
import io.github.sillypuppyy.dogboard.engine.Tap
import io.github.sillypuppyy.dogboard.feel.Trackpad
import dev.patrickgold.florisboard.ime.keyboard3.touch.isTextKey
import io.github.sillypuppyy.dogboard.touch.TypingSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.k3lp.lib.text.asK3String
import org.k3lp.model.K3Model
import org.k3lp.model.layer.K3LayerId
import kotlin.math.abs
import kotlin.math.pow
import kotlin.time.Duration

private val SPACE = " ".asK3String()

// DogBoard: how far (key widths) a press on a letter has to move to become a swipe
private const val GLIDE_START_KEYS = 0.6f

/** DogBoard: how long a point of the swipe trail stays visible (ms), shrinking from the tail (Gboard-like). */
const val TRAIL_MS = 700L

/** DogBoard: a point of the swipe trail: position (px) and when the finger was there (uptime ms). */
class TrailPoint(val x: Float, val y: Float, val t: Long)

sealed interface TrackedPointer {
    val id: PointerId
    val down: PointerInputChange
    val downKey: TouchKey

    data class Peek(
        override val id: PointerId,
        override val down: PointerInputChange,
        override val downKey: TouchKey,
        val downLayerId: K3LayerId,
        val downInputShiftState: InputShiftState,
        val peekLayerId: K3LayerId,
        val peekKey: TouchKey?,
        val peekLine: PeekLine?,
        val peekMustSwitchBack: Boolean,
    ) : TrackedPointer

    data class Output(
        override val id: PointerId,
        override val down: PointerInputChange,
        override val downKey: TouchKey,
        val longPress: LongPress,
        val repeatJob: Job?,
        // DogBoard: space-bar trackpad; the accelerated distance (dp) not yet turned into cursor
        // steps, null until active
        val cursorAnchorDp: Float? = null,
        // DogBoard: the finger's x (dp) at the last move, for the per-move acceleration
        val trackpadLastXDp: Float? = null,
        // DogBoard: the finger moved too far before the trackpad hold time; this stays a space
        val trackpadCancelled: Boolean = false,
    ) : TrackedPointer {
        /** DogBoard: the space bar was held long enough to be a trackpad, even if never moved. */
        fun isTrackpad(nowUptime: Long): Boolean = cursorAnchorDp != null ||
            (downKey.attrs.output == SPACE && !trackpadCancelled && io.github.sillypuppyy.dogboard.feel.FeelSettings.trackpadEnabled && nowUptime - down.uptimeMillis >= io.github.sillypuppyy.dogboard.feel.FeelSettings.trackpadHoldMs)
    }
}

data class TrackedMultiTapSeq(
    val touchKey: TouchKey,
    val multiTapIndex: Int,
    val commitJob: Job?,
    val commit: suspend () -> Unit,
    val cycleNext: suspend () -> Unit,
)

data class PeekLine(
    val start: Offset,
    val end: Offset,
)

private data class LastUpKey(
    val touchKey: TouchKey,
    val uptimeMillis: Long,
)

// TODO the sync of this class is a bit messy
class PointerTracker(
    val touchKeyboard: TouchKeyboard,
    val imeController: ImeController,
    val interactionController: InteractionController,
    val showSimplePopups: Boolean,
    val peekDistanceSqMin: Float,
    // DogBoard: pixels per dp, for the touch model
    val density: Float = 1f,
) {
    // DogBoard: smoothed press interval, widens the touch spread when typing fast
    private val typingSpeed = TypingSpeed()

    private val mutationGuard = Mutex()

    val trackedPeekPointer: StateFlow<TrackedPointer.Peek?>
        field = MutableStateFlow(null)

    val trackedOutputPointer: StateFlow<TrackedPointer.Output?>
        field = MutableStateFlow(null)

    val trackedMultiTapSeq: StateFlow<TrackedMultiTapSeq?>
        field = MutableStateFlow(null)

    /** DogBoard: the text before the cursor when the current delete hold began (null: no hold). */

    private var heldDeleteStart: String? = null

    private var lastUpKeyState: LastUpKey? = null

    /** DogBoard: swipe typing: the finger's path (dp) from a letter key's press; active once it moved far enough. */
    private class Glide(val id: PointerId, val layerId: K3LayerId, val startX: Float, val startY: Float, startT: Long) {
        val xs = ArrayList<Float>().apply { add(startX) }
        val ys = ArrayList<Float>().apply { add(startY) }
        val ts = ArrayList<Long>().apply { add(startT) }
        var active = false
    }
    private var glide: Glide? = null

    /** DogBoard: the trail of the swipe in progress (pixels), drawn over the keys; null when none. */
    val glideTrail: StateFlow<List<TrailPoint>?>
        field = MutableStateFlow(null)
    // DogBoard: caps lock timing is measured from the first shift press, not its release
    private var lastShiftDownUptime: Long = Long.MIN_VALUE

    suspend fun mutateLocked(action: suspend PointerTracker.() -> Unit) {
        mutationGuard.withLock { action() }
    }

    context(scope: CoroutineScope)
    suspend fun handleDown(down: PointerInputChange, size: IntSize) {
        if (dev.patrickgold.florisboard.BuildConfig.DEBUG) {
            android.util.Log.i("DogBoardTouch", "down id=${down.id.value} at ${down.position} tracker=${this@PointerTracker.hashCode()}")
        }
        val lastUpKey = lastUpKeyState

        trackedPeekPointer.value?.let { trackedPointer ->
            if (trackedPointer.peekKey != null) {
                handleUp(down.copy(id = trackedPointer.id), size)
            }
        }
        trackedOutputPointer.value?.let { trackedPointer ->
            handleUp(down.copy(id = trackedPointer.id), size)
        }

        val downLayerId = imeController.snapshotState().touchLayerId
        // DogBoard: text keys are resolved with the Gaussian touch model
        typingSpeed.onPress(down.uptimeMillis)
        val downKey = touchKeyboard.findKeyForTap(
            layerId = downLayerId,
            position = down.position.normalized(size),
            keyboardWidthDp = size.width / density,
            keyboardHeightDp = size.height / density,
            typingInterval = typingSpeed.interval,
            letterPrior = imeController.letterPrior(),
        ) ?: return
        // DogBoard: a press on a letter may become a swipe
        glide = null
        glideTrail.value = null
        if (io.github.sillypuppyy.dogboard.feel.FeelSettings.swipeEnabled &&
            io.github.sillypuppyy.dogboard.engine.TypingEngine.hasSwipe &&
            (downKey.attrs.output as? org.k3lp.lib.text.K3String)?.toText()?.let { it.length == 1 && it[0].isLetter() } == true
        ) {
            glide = Glide(down.id, downLayerId, down.position.x / density, down.position.y / density, down.uptimeMillis)
        }
        // DogBoard: remember where letter keys were tapped, for the word decoder
        if (downKey.isTextKey()) {
            io.github.sillypuppyy.dogboard.ime.BarTapGuard.lastKeyDownUptime = down.uptimeMillis
            val widthDp = size.width / density
            val heightDp = size.height / density
            val (letterKeys, params) = touchKeyboard.letterTargets(downLayerId, widthDp, heightDp, typingSpeed.interval)
            val normalized = down.position.normalized(size)
            imeController.notePendingTap(Tap(normalized.x * widthDp, normalized.y * heightDp, down.uptimeMillis), letterKeys, params)
        } else {
            imeController.notePendingTap(null, emptyMap(), null)
            // DogBoard: where the space bar was tapped (a word split by a stray space may be rejoined)
            if ((downKey.attrs.output as? org.k3lp.lib.text.K3String)?.toText() == " ") {
                io.github.sillypuppyy.dogboard.ime.BarTapGuard.lastKeyDownUptime = down.uptimeMillis
                val normalized = down.position.normalized(size)
                imeController.noteSpaceTap(Tap(normalized.x * size.width / density, normalized.y * size.height / density, down.uptimeMillis))
            }
        }

        val timingOptions = interactionController.activeTimingOptions.value
        val keyRepeatTimeout = timingOptions.getKeyRepeatTimeout(downKey.attrs.output)
        val keyRepeatDelay = timingOptions.getKeyRepeatDelay(downKey.attrs.output)
        val longPressTimeout = timingOptions.getLongPressTimeout(downKey.attrs.output)
        val doubleTapTimeout = timingOptions.doubleTapTimeout

        val peekLayerId = downKey.attrs.layerId
        if (peekLayerId != null) {
            // this is a peek key action
            trackedMultiTapSeq.value?.let { multiTapSeq ->
                multiTapSeq.commit()
            }
            val downInputShiftState: InputShiftState
            imeController.updateState {
                downInputShiftState = state.flags.inputShiftState
                if (downKey.isShiftKey) {
                    val isDoubleTap = lastUpKey != null && lastUpKey.touchKey.isShiftKey &&
                        lastShiftDownUptime != Long.MIN_VALUE &&
                        (down.uptimeMillis - lastShiftDownUptime) < doubleTapTimeout.inWholeMilliseconds
                    lastShiftDownUptime = if (isDoubleTap) Long.MIN_VALUE else down.uptimeMillis
                    cycleInputShiftState(isDoubleTap)
                } else {
                    switchTouchLayer(peekLayerId)
                }
            }
            trackedPeekPointer.value = TrackedPointer.Peek(
                id = down.id,
                down = down,
                downKey = downKey,
                downLayerId = downLayerId,
                downInputShiftState = downInputShiftState,
                peekLayerId = peekLayerId,
                peekKey = null,
                peekLine = null,
                peekMustSwitchBack = false,
            )
            interactionController.performFeedback(InteractionKind.KeyPress)
        } else {
            trackedOutputPointer.value = TrackedPointer.Output(
                id = down.id,
                down = down,
                downKey = downKey,
                longPress = if (downKey.isSuitableForPopup) {
                    val anchorBounds = downKey.bounds.let { bounds ->
                        // TODO this is only tested for PHONE_PORTRAIT, test+fix other form factors!
                        val w = 0.1f
                        val h = bounds.height * 0.9f
                        val x = bounds.bottomCenter.x - w / 2f
                        val y = bounds.bottom - h * 2.3f
                        Rect(
                            offset = Offset(x, y),
                            size = Size(w, h),
                        )
                    }
                    LongPress(
                        anchorBounds = anchorBounds,
                        simpleBounds = if (showSimplePopups && downKey.isSuitableForSimplePopup) {
                            anchorBounds.copy(
                                bottom = anchorBounds.bottom + anchorBounds.height * 1.2f,
                            )
                        } else Rect.Zero,
                        simpleLabel = downKey.display,
                        simpleIndicateExtended = downKey.isSuitableForExtendedPopup,
                        extendedBounds = Rect.Zero,
                        extendedKeys = downKey.extendedPopupKeys,
                        extendedFocusedIndex = 0,
                        keyBounds = downKey.bounds,
                        keyboardWidthDp = size.width / density,
                        extendedJob = if (downKey.isSuitableForExtendedPopup) {
                            launchExtendedLongPressJob(longPressTimeout)
                        } else null,
                    )
                } else LongPress.None,
                repeatJob = if (downKey.isRepeatable) {
                    scope.launch {
                        // DogBoard: the text before the hold, so Undo can bring back all it deletes
                        val holdStart = imeController.snapshotState().content.surroundingText.textBefore
                        delay(keyRepeatTimeout)
                        heldDeleteStart = holdStart
                        // DogBoard: a held delete accelerates from characters to whole words
                        val isBackspace = downKey.attrs.output == ImeActions.Backspace
                        var repeatIndex = 0
                        while (isActive) {
                            val step = if (isBackspace) DeleteRepeat.step(repeatIndex++) else null
                            val output = when (step?.unit) {
                                DeleteRepeat.Unit.WORD -> ImeActions.BackspaceWord
                                else -> downKey.attrs.output
                            }
                            // DogBoard: a held delete at the start of the text deletes nothing, so it
                            // also doesn't vibrate (FlorisBoard upstream #2823); raw editors (terminals)
                            // hide their text, so there it keeps sending
                            val snapshot = imeController.snapshotState()
                            val nothingToDelete = isBackspace && !snapshot.editor.info.isRawInputEditor &&
                                snapshot.content.selection.isCollapsed() && snapshot.content.surroundingText.textBefore.isEmpty()
                            if (!nothingToDelete) {
                                imeController.updateState {
                                    output?.let { emit(it) }
                                    if (isBackspace) recordHeldDelete(holdStart)
                                }
                                interactionController.performFeedback(InteractionKind.KeyRepeat, output)
                            }
                            delay(step?.delayAfter ?: keyRepeatDelay)
                        }
                    }
                } else null,
            )
            // DogBoard: iOS turns a held space into the trackpad after the hold time even if the
            // finger hasn't moved (the keys go blank at once); moves then continue from here
            if (downKey.attrs.output == SPACE && io.github.sillypuppyy.dogboard.feel.FeelSettings.trackpadEnabled) {
                val downId = down.id
                val downXDp = down.position.x / density
                scope.launch {
                    delay(io.github.sillypuppyy.dogboard.feel.FeelSettings.trackpadHoldMs)
                    mutateLocked {
                        val tp = trackedOutputPointer.value ?: return@mutateLocked
                        if (tp.id != downId || tp.cursorAnchorDp != null || tp.trackpadCancelled) return@mutateLocked
                        tp.repeatJob?.cancel()
                        tp.longPress.extendedJob?.cancel()
                        trackedOutputPointer.value = tp.copy(cursorAnchorDp = 0f, trackpadLastXDp = downXDp)
                    }
                }
            }
            trackedMultiTapSeq.value.let { multiTapSeq ->
                if (multiTapSeq != null) {
                    if (multiTapSeq.touchKey !== downKey) {
                        multiTapSeq.commit()
                    } else if (downKey.multiTapKeys.isNotEmpty()) {
                        multiTapSeq.cycleNext()
                    }
                } else if (downKey.multiTapKeys.isNotEmpty()) {
                    val commit = suspend commit@{
                        val multiTapSeq = trackedMultiTapSeq.value ?: return@commit
                        val multiTapKey = multiTapSeq.touchKey.multiTapKeys.getOrNull(multiTapSeq.multiTapIndex)
                            ?: return@commit
                        val output = multiTapKey.data.output ?: return@commit
                        imeController.updateState {
                            emit(output)
                        }
                        trackedMultiTapSeq.value = null
                    }
                    val cycleNext = suspend cycleNext@{
                        val multiTapSeq = trackedMultiTapSeq.value ?: return@cycleNext
                        multiTapSeq.commitJob?.cancel()
                        val newIndex = (multiTapSeq.multiTapIndex + 1) % multiTapSeq.touchKey.multiTapKeys.size.fastCoerceAtLeast(1)
                        trackedMultiTapSeq.value = multiTapSeq.copy(
                            multiTapIndex = newIndex,
                            commitJob = null,
                        )
                    }
                    trackedMultiTapSeq.value = TrackedMultiTapSeq(
                        touchKey = downKey,
                        multiTapIndex = 0,
                        commitJob = null,
                        commit = commit,
                        cycleNext = cycleNext,
                    )
                }
            }
            interactionController.performFeedback(InteractionKind.KeyPress, downKey.attrs.output)
        }
    }

    /**
     * DogBoard ([io.github.sillypuppyy.dogboard.ime.BarTapGuard]): where in the key area ([size] px)
     * a suggestion-bar tap at key-area x [xPx] lands as a top-row key tap (straight down into the
     * top row), or null if it stays the bar's: too long after the last key, or a letter the
     * language doesn't expect there.
     */
    fun barTapPosition(xPx: Float, size: IntSize, uptime: Long): Offset? {
        val guard = io.github.sillypuppyy.dogboard.ime.BarTapGuard
        if (uptime - guard.lastKeyDownUptime > guard.windowMs || xPx < 0f || xPx > size.width) return null
        val layerId = imeController.snapshotState().touchLayerId
        val letters = touchKeyboard.letterTargets(layerId, size.width / density, size.height / density, typingSpeed.interval).first
        if (letters.isEmpty()) return null
        val top = letters.values.minOf { it.centerY }
        val (letter, target) = letters.entries.filter { it.value.centerY - top < 1f }
            .minByOrNull { kotlin.math.abs(it.value.centerX - xPx / density) } ?: return null
        val prior = imeController.letterPrior() ?: return null
        if (prior(letter) < io.github.sillypuppyy.dogboard.engine.TypingEngine.keyPriorOf(guard.MIN_LETTER_PROB)) return null
        return Offset(xPx, target.centerY * density)
    }

    suspend fun handleMove(move: PointerInputChange, size: IntSize) {
        // DogBoard: swipe typing: past ~0.6 of a key width the press becomes a swipe (its key,
        // long press and preview are cancelled); from then on the path is drawn
        glide?.takeIf { it.id == move.id }?.let { g ->
            val xDp = move.position.x / density
            val yDp = move.position.y / density
            g.xs.add(xDp); g.ys.add(yDp); g.ts.add(move.uptimeMillis)
            if (!g.active) {
                val keyWidthDp = size.width / density / 10f
                val out = trackedOutputPointer.value?.takeIf { it.id == move.id }
                if (out?.longPress?.shouldShowExtendedPopup() == true) {
                    glide = null
                } else if (kotlin.math.hypot(xDp - g.startX, yDp - g.startY) > GLIDE_START_KEYS * keyWidthDp) {
                    g.active = true
                    out?.longPress?.extendedJob?.cancel()
                    out?.repeatJob?.cancel()
                    trackedOutputPointer.value = null
                    imeController.notePendingTap(null, emptyMap(), null)
                }
            }
            if (g.active) {
                if (io.github.sillypuppyy.dogboard.feel.FeelSettings.swipeTrail) {
                    // only what the trail can still show (TRAIL_MS)
                    val from = g.ts.indexOfFirst { move.uptimeMillis - it <= TRAIL_MS + 50 }.coerceAtLeast(0)
                    glideTrail.value = (from until g.xs.size).map { TrailPoint(g.xs[it] * density, g.ys[it] * density, g.ts[it]) }
                }
                return
            }
        }
        trackedPeekPointer.value?.takeIf { it.id == move.id }?.let { trackedPointer ->
            val distanceSq = (move.position - trackedPointer.down.position).getDistanceSquared()
            if (trackedPointer.peekLine != null || distanceSq >= peekDistanceSqMin) {
                val newPeekKey = touchKeyboard.findKey(trackedPointer.peekLayerId, move.position.normalized(size))
                trackedPeekPointer.value = trackedPointer.copy(
                    peekKey = newPeekKey,
                    peekLine = PeekLine(trackedPointer.down.position, move.position),
                    peekMustSwitchBack = true,
                )
            }
        }
        trackedOutputPointer.value?.takeIf { it.id == move.id }?.let { trackedPointer ->
            if (trackedPointer.downKey.attrs.output == SPACE) {
                moveCursorWithSpaceBar(trackedPointer, move)
                return
            }
            val longPress = trackedPointer.longPress
            if (longPress.shouldShowExtendedPopup()) {
                val position = move.position.normalized(size).let { position ->
                    Offset(
                        x = (position.x)
                            .fastCoerceIn(longPress.extendedBounds.left, longPress.extendedBounds.right),
                        y = (position.y - longPress.anchorBounds.height)
                            .fastCoerceIn(longPress.extendedBounds.top, longPress.extendedBounds.bottom - 0.01f),
                    )
                }
                val newFocusedIndex = longPress.getNearestKeyIndex(position)
                if (newFocusedIndex != longPress.extendedFocusedIndex) {
                    trackedOutputPointer.value = trackedPointer.copy(
                        longPress = longPress.copy(extendedFocusedIndex = newFocusedIndex),
                    )
                }
            }
        }
    }

    /**
     * DogBoard: holding space for [Trackpad.HOLD_MS] turns it into a trackpad (moving more than
     * [Trackpad.ALLOWABLE_MOVEMENT_DP] before that cancels it); sliding then moves the cursor one
     * character per [Trackpad.STEP_DP].
     */
    private suspend fun moveCursorWithSpaceBar(trackedPointer: TrackedPointer.Output, move: PointerInputChange) {
        val xDp = move.position.x / density
        var anchor = trackedPointer.cursorAnchorDp
        if (anchor == null) {
            if (trackedPointer.trackpadCancelled || !io.github.sillypuppyy.dogboard.feel.FeelSettings.trackpadEnabled) return
            if (move.uptimeMillis - trackedPointer.down.uptimeMillis < io.github.sillypuppyy.dogboard.feel.FeelSettings.trackpadHoldMs) {
                val movedDp = (move.position - trackedPointer.down.position).getDistance() / density
                if (movedDp > Trackpad.ALLOWABLE_MOVEMENT_DP) {
                    trackedOutputPointer.value = trackedPointer.copy(trackpadCancelled = true)
                }
                return
            }
            trackedPointer.repeatJob?.cancel()
            trackedPointer.longPress.extendedJob?.cancel()
            anchor = 0f
        }
        // iOS: each movement is accelerated by its own size (fast flicks go far, slow drags stay precise)
        val lastX = trackedPointer.trackpadLastXDp ?: xDp
        anchor += Trackpad.accelerated(xDp - lastX)
        val steps = (anchor / io.github.sillypuppyy.dogboard.feel.FeelSettings.trackpadStepDp).toInt()
        if (steps != 0) {
            imeController.updateState {
                val arrow = if (steps > 0) ImeActions.ArrowRight else ImeActions.ArrowLeft
                repeat(abs(steps)) { emitDescriptor(arrow) }
            }
            anchor -= steps * io.github.sillypuppyy.dogboard.feel.FeelSettings.trackpadStepDp
        }
        trackedOutputPointer.value = trackedPointer.copy(cursorAnchorDp = anchor, trackpadLastXDp = xDp)
    }

    context(scope: CoroutineScope)
    suspend fun handleUp(up: PointerInputChange, size: IntSize) {
        if (dev.patrickgold.florisboard.BuildConfig.DEBUG) {
            android.util.Log.i(
                "DogBoardTouch",
                "up id=${up.id.value} tracked=${trackedOutputPointer.value?.id?.value} peek=${trackedPeekPointer.value?.id?.value}",
            )
        }
        // DogBoard: a finished swipe goes to the recogniser instead of a key
        glide?.takeIf { it.id == up.id }?.let { g ->
            glide = null
            if (g.active) {
                glideTrail.value = null
                g.xs.add(up.position.x / density); g.ys.add(up.position.y / density)
                val (targets, _) = touchKeyboard.letterTargets(g.layerId, size.width / density, size.height / density, typingSpeed.interval)
                val centres = targets.entries.associate { (c, t) -> c.lowercaseChar() to (t.centerX to t.centerY) }
                val xs = g.xs.toFloatArray()
                val ys = g.ys.toFloatArray()
                scope.launch { imeController.commitSwipe(xs, ys, centres) }
                lastUpKeyState = null
                return
            }
        }
        trackedPeekPointer.value?.takeIf { it.id == up.id }?.let { trackedPointer ->
            imeController.updateState {
                trackedPointer.peekKey?.attrs?.output?.let { emit(it) }
                if (trackedPointer.peekMustSwitchBack) {
                    if (trackedPointer.downKey.isShiftKey) {
                        revertCycleInputShiftState(
                            trackedPointer.downLayerId, trackedPointer.downInputShiftState
                        )
                    } else {
                        switchTouchLayer(trackedPointer.downLayerId)
                    }
                }
            }
            trackedPeekPointer.value = null
            lastUpKeyState = LastUpKey(trackedPointer.downKey, up.uptimeMillis)
        }
        trackedOutputPointer.value?.takeIf { it.id == up.id }?.let { trackedPointer ->
            trackedPointer.longPress.extendedJob?.cancel()
            trackedPointer.repeatJob?.cancel()
            // DogBoard: as Gboard (measured on the emulator's vibration log, 2026-11-14): a held
            // delete vibrates on the press and once when released after repeating, not per repeat
            if (trackedPointer.downKey.attrs.output == ImeActions.Backspace &&
                up.uptimeMillis - trackedPointer.down.uptimeMillis >=
                interactionController.activeTimingOptions.value.getKeyRepeatTimeout(ImeActions.Backspace).inWholeMilliseconds
            ) {
                interactionController.performFeedback(InteractionKind.KeyPress, ImeActions.Backspace)
            }
            imeController.updateState {
                val output = when {
                    trackedPointer.isTrackpad(up.uptimeMillis) -> null // the space bar was used as a trackpad
                    trackedPointer.downKey == trackedMultiTapSeq.value?.touchKey -> null
                    trackedPointer.longPress.shouldShowExtendedPopup() -> {
                        trackedPointer.longPress.extendedKeys.getOrNull(trackedPointer.longPress.extendedFocusedIndex)
                            ?.data?.output
                    }
                    else -> trackedPointer.downKey.attrs.output
                }
                output?.let { emit(it) }
                // the release deletes once more after a hold: Undo still brings back the whole hold
                heldDeleteStart?.takeIf { output == ImeActions.Backspace }?.let { recordHeldDelete(it) }
            }
            heldDeleteStart = null
            trackedPeekPointer.update { it?.copy(peekMustSwitchBack = true) }
            trackedOutputPointer.value = null
            lastUpKeyState = LastUpKey(trackedPointer.downKey, up.uptimeMillis)
        }
        trackedMultiTapSeq.update { multiTapSeq ->
            if (multiTapSeq != null) {
                multiTapSeq.commitJob?.cancel() // should never happen, but still ensure we never have 2 active jobs
                multiTapSeq.copy(
                    commitJob = scope.launch {
                        val timingOptions = interactionController.activeTimingOptions.value
                        delay(timingOptions.multiTapTimeout)
                        mutateLocked {
                            if (trackedOutputPointer.value?.longPress?.extendedBounds?.isEmpty == false) {
                                return@mutateLocked
                            }
                            multiTapSeq.commit()
                        }
                    }
                )
            } else null
        }
    }

    suspend fun cancelNonPresent(changes: List<PointerInputChange>) {
        trackedPeekPointer.value?.id?.let { id ->
            if (changes.fastAll { it.id != id }) {
                cancelPeek()
            }
        }
        trackedOutputPointer.value?.id?.let { id ->
            if (changes.fastAll { it.id != id }) {
                cancelOutput()
            }
        }
    }

    suspend fun cancelAll() {
        cancelPeek()
        cancelOutput()
        glide = null
        glideTrail.value = null
    }

    private suspend fun cancelPeek() {
        val trackedPointer = trackedPeekPointer.getAndUpdate { null } ?: return
        imeController.updateState {
            if (trackedPointer.downKey.isShiftKey) {
                revertCycleInputShiftState(
                    trackedPointer.downLayerId, trackedPointer.downInputShiftState
                )
            } else {
                switchTouchLayer(trackedPointer.downLayerId)
            }
        }
    }

    private suspend fun cancelOutput() {
        cancelMultiTap()
        val trackedPointer = trackedOutputPointer.getAndUpdate { null } ?: return
        trackedPointer.longPress.extendedJob?.cancel()
        trackedPointer.repeatJob?.cancel()
    }

    private suspend fun cancelMultiTap() {
        val multiTapSeq = trackedMultiTapSeq.getAndUpdate { null } ?: return
        multiTapSeq.commitJob?.cancel()
    }

    private fun Offset.normalized(size: IntSize): Offset {
        return Offset(x / size.width, y / size.height)
    }

    context(scope: CoroutineScope)
    private fun launchExtendedLongPressJob(
        longPressTimeout: Duration,
    ): Job = scope.launch(Dispatchers.Default) {
        delay(longPressTimeout)
        mutateLocked {
            trackedMultiTapSeq.value?.let { multiTapSeq ->
                multiTapSeq.commitJob?.cancel()
                trackedMultiTapSeq.value = null
            }
            val trackedPointer = trackedOutputPointer.value ?: return@mutateLocked
            val longPress = trackedPointer.longPress
            val anchorBounds = longPress.anchorBounds
            val extendedKeys = if (longPress.keyboardWidthDp > 0f) {
                layoutLongPressBarRow(longPress.extendedKeys, longPress.keyBounds, anchorBounds, longPress.keyboardWidthDp)
            } else layoutExtendedLongPressKeys(longPress.extendedKeys, anchorBounds)
            val extendedBounds = anchorBounds.copy(
                left = extendedKeys.fastFold(anchorBounds.left) { acc, key -> minOf(acc, key.bounds.left) },
                top = extendedKeys.fastFold(anchorBounds.top) { acc, key -> minOf(acc, key.bounds.top) },
                right = extendedKeys.fastFold(anchorBounds.right) { acc, key -> maxOf(acc, key.bounds.right) },
                bottom = extendedKeys.fastFold(anchorBounds.bottom) { acc, key -> maxOf(acc, key.bounds.bottom) },
            )
            trackedOutputPointer.value = trackedPointer.copy(
                longPress = trackedPointer.longPress.copy(
                    simpleIndicateExtended = false,
                    extendedBounds = extendedBounds,
                    extendedKeys = extendedKeys,
                ),
            )
        }
    }
}

@Composable
fun rememberPointerTracker(
    touchKeyboard: TouchKeyboard,
): PointerTracker {
    val prefs by FlorisPreferenceStore
    val density = LocalDensity.current
    val imeController = LocalImeController.current
    val interactionController = LocalInteractionController.current

    val showSimplePopups by prefs.keyboard.popupEnabled.collectAsState()
    // TODO make configurable
    val peekDistanceSqMin = with(density) { 30.dp.toPx().pow(2) }

    val pointerTracker = remember(touchKeyboard, showSimplePopups) {
        PointerTracker(
            touchKeyboard, imeController, interactionController, showSimplePopups, peekDistanceSqMin,
            density = density.density,
        )
    }

    return pointerTracker
}

fun Modifier.trackPointerInput(
    pointerTracker: PointerTracker,
    model: K3Model,
) = this.pointerInput(pointerTracker, model) {
    try {
        coroutineScope {
            val scope = this
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val sizeAtEvent = size
                    scope.launch {
                        pointerTracker.mutateLocked {
                            cancelNonPresent(event.changes)
                        }
                    }
                    event.changes.fastForEach { change ->
                        if (change.changedToDown()) {
                            scope.launch {
                                pointerTracker.mutateLocked {
                                    handleDown(change, sizeAtEvent)
                                }
                            }
                        } else if (change.changedToUp()) {
                            scope.launch {
                                pointerTracker.mutateLocked {
                                    handleUp(change, sizeAtEvent)
                                }
                            }
                        } else if (!change.isConsumed) {
                            scope.launch {
                                pointerTracker.mutateLocked {
                                    handleMove(change, sizeAtEvent)
                                }
                            }
                        }
                        change.consume()
                    }
                }
            }
        }
    } finally {
        withContext(NonCancellable) {
            pointerTracker.mutateLocked {
                cancelAll()
            }
        }
    }
}
