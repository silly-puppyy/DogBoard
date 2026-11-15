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

package dev.patrickgold.florisboard.ime.keyboard3.touch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import dev.patrickgold.florisboard.ime.keyboard3.ImeActions
import dev.patrickgold.florisboard.ime.keyboard3.ImeLayerIds
import dev.patrickgold.florisboard.ime.keyboard3.hint.LongPressKeyHintPlacement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.github.sillypuppyy.dogboard.touch.KeyTarget
import org.k3lp.lib.meta.source.SourceFileRef
import org.k3lp.lib.text.K3Descriptor
import org.k3lp.lib.text.K3String
import org.k3lp.lib.text.K3StringOrDescriptor
import org.k3lp.lib.text.asK3String
import org.k3lp.model.K3Model
import org.k3lp.model.flick.K3Flick
import org.k3lp.model.key.K3Key
import org.k3lp.model.key.K3KeyId
import org.k3lp.model.layer.K3LayerId
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import org.k3lp.model.layer.K3TouchLayers
import kotlin.contracts.contract
import kotlin.math.roundToInt

sealed interface TouchModel {
    fun selectKeyboard(deviceWidthMm: Int): TouchKeyboard

    object Empty : TouchModel {
        override fun selectKeyboard(deviceWidthMm: Int): TouchKeyboard {
            return TouchKeyboard.Empty
        }
    }

    class Single(val keyboard: TouchKeyboard) : TouchModel {
        override fun selectKeyboard(deviceWidthMm: Int): TouchKeyboard {
            return keyboard
        }
    }

    class Multiple(val keyboards: List<TouchKeyboard>) : TouchModel {
        override fun selectKeyboard(deviceWidthMm: Int): TouchKeyboard {
            return keyboards.lastOrNull { it.minDeviceWidthMm <= deviceWidthMm }
                ?: keyboards[0]
        }
    }
}

class TouchKeyboard(
    val layers: Map<K3LayerId, TouchLayer>,
    val rowCount: Int,
    /** DogBoard: the key area's height in plain rows (the number row and its letter rows are shorter, as Gboard). */
    val rowUnits: Float = rowCount.toFloat(),
    val minDeviceWidthMm: Int,
) {
    fun findKey(layerId: K3LayerId, position: Offset): TouchKey? {
        val layer = if (layerId == ImeLayerIds.Caps) {
            layers[layerId] ?: layers[ImeLayerIds.Shift] ?: layers[ImeLayerIds.Base]
        } else {
            layers[layerId] ?: layers[ImeLayerIds.Base]
        }
        if (layer == null || !NormalizedBounds.contains(position)) {
            return null
        }
        // TODO improve runtime of this
        for (key in layer.keys) {
            if (key.hitbox.contains(position)) {
                return key
            }
        }
        return null
    }

    // DogBoard: probabilistic key resolution for text keys
    /**
     * Resolves a tap like [findKey], but text keys compete through a Gaussian touch model instead
     * of strict rectangles. Function keys (shift, delete, space, layer switches, ...) keep their
     * rectangles: a tap inside one always goes to it.
     *
     * @param keyboardWidthDp keyboard width in dp
     * @param keyboardHeightDp keyboard height (key rows only) in dp
     * @param typingInterval smoothed time between presses in seconds
     */
    fun findKeyForTap(
        layerId: K3LayerId,
        position: Offset,
        keyboardWidthDp: Float,
        keyboardHeightDp: Float,
        typingInterval: Double,
        letterPrior: ((Char) -> Double)? = null,
    ): TouchKey? {
        val rectKey = findKey(layerId, position)
        if (rectKey != null && !rectKey.attrs.gap && !rectKey.isTextKey()) {
            return rectKey
        }
        val t = targetsFor(layerId, keyboardWidthDp, keyboardHeightDp)
        val textKeys = t.textKeys
        if (textKeys.isEmpty()) return rectKey
        val targets = t.targets
        val params = GaussianTouch.params(t.medianHalfWidth, typingInterval)
        val prior: (Int) -> Double = if (letterPrior == null) {
            { 0.0 }
        } else {
            { i -> (textKeys[i].attrs.output as K3String).toText().singleOrNull()?.takeIf { it.isLetter() }?.let(letterPrior) ?: 0.0 }
        }
        val index = GaussianTouch.pick(
            targets,
            position.x * keyboardWidthDp,
            position.y * keyboardHeightDp,
            params,
            prior,
        )
        return index?.let { textKeys[it] } ?: rectKey?.takeIf { !it.attrs.gap }
    }

    /** A layer's text keys at one keyboard size, with their touch targets (DogBoard). */
    private class Targets(
        val textKeys: List<TouchKey>,
        val targets: List<KeyTarget>,
        val medianHalfWidth: Double,
        /** Lowercase letter -> target, for the word decoder (the same map object for every tap). */
        val letters: Map<Char, KeyTarget>,
        val letterMedianHalfWidth: Double,
    )

    // DogBoard: built once per layer and size, not on every tap (the layout doesn't change between taps)
    private val targetsCache = HashMap<Triple<K3LayerId, Float, Float>, Targets>()

    private fun targetsFor(layerId: K3LayerId, widthDp: Float, heightDp: Float): Targets = synchronized(targetsCache) {
        targetsCache.getOrPut(Triple(layerId, widthDp, heightDp)) {
            val textKeys = textKeysOf(layerId)
            val targets = textKeys.map { it.target(widthDp, heightDp) }
            val letters = buildMap {
                for ((key, target) in textKeys.zip(targets)) {
                    val text = (key.attrs.output as K3String).toText()
                    if (text.length == 1 && text[0].isLetter()) put(text[0].lowercaseChar(), target)
                }
            }
            if (targetsCache.size > 16) targetsCache.clear() // sizes change rarely (rotation, resizing)
            Targets(
                textKeys, targets,
                if (targets.isEmpty()) 0.0 else medianHalfWidth(targets),
                letters,
                if (letters.isEmpty()) 0.0 else medianHalfWidth(letters.values.toList()),
            )
        }
    }

    private fun textKeysOf(layerId: K3LayerId): List<TouchKey> {
        val layer = if (layerId == ImeLayerIds.Caps) {
            layers[layerId] ?: layers[ImeLayerIds.Shift] ?: layers[ImeLayerIds.Base]
        } else {
            layers[layerId] ?: layers[ImeLayerIds.Base]
        } ?: return emptyList()
        return layer.keys.filter { it.isTextKey() }
    }

    /**
     * DogBoard: the layer's letter keys (lowercase letter -> touch target in dp) and the touch
     * parameters for them, for the word decoder. Empty if the layer has no letters.
     */
    fun letterTargets(
        layerId: K3LayerId,
        keyboardWidthDp: Float,
        keyboardHeightDp: Float,
        typingInterval: Double,
    ): Pair<Map<Char, KeyTarget>, GaussianTouch.Params?> {
        val t = targetsFor(layerId, keyboardWidthDp, keyboardHeightDp)
        if (t.letters.isEmpty()) return t.letters to null
        return t.letters to GaussianTouch.params(t.letterMedianHalfWidth, typingInterval)
    }

    companion object {
        val NormalizedBounds = Rect(Offset.Zero, Size(1f, 1f))

        val Empty = TouchKeyboard(
            layers = mapOf(
                ImeLayerIds.Base to TouchLayer.Empty,
            ),
            rowCount = 4,
            minDeviceWidthMm = 0,
        )
    }
}

class TouchLayer(
    val keys: List<TouchKey>,
) {
    companion object {
        val Empty = TouchLayer(emptyList())
    }
}

// DogBoard: keys that type text (letters, digits, punctuation), as opposed to function keys
private fun TouchKey.target(keyboardWidthDp: Float, keyboardHeightDp: Float) = KeyTarget(
    centerX = bounds.center.x * keyboardWidthDp,
    centerY = bounds.center.y * keyboardHeightDp,
    halfWidth = bounds.width * keyboardWidthDp / 2f,
    halfHeight = bounds.height * keyboardHeightDp / 2f,
)

private fun medianHalfWidth(targets: List<KeyTarget>): Double =
    targets.map { it.halfWidth }.sorted()[targets.size / 2].toDouble()

fun TouchKey.isTextKey(): Boolean {
    val output = attrs.output
    return !attrs.gap && attrs.layerId == null && output is K3String && output != " ".asK3String() &&
        !attrs.id.isFnKeyId()
}

class TouchKey(
    val bounds: Rect,
    val hitbox: Rect,
    val display: K3StringOrDescriptor,
    val attrs: K3Key,
    val flick: K3Flick?,
    val isShiftKey: Boolean,
    val isRepeatable: Boolean,
    val isSuitableForSpaceBarDisplayOverride: Boolean,
    val isSuitableForSimplePopup: Boolean,
    val isSuitableForExtendedPopup: Boolean,
    val extendedPopupKeys: List<TouchPopupKey>,
    val longPressKeyHint: K3StringOrDescriptor?,
    val longPressKeyHintPlacement: LongPressKeyHintPlacement,
    val shouldOverrideDisplayWithMultiTapKeys: Boolean,
    val shouldHighlightPendingMultiTapKey: Boolean,
    val multiTapKeys: List<TouchMultiTapKey>,
) {
    val isSuitableForPopup: Boolean
        get() = isSuitableForSimplePopup || isSuitableForExtendedPopup

    companion object {
        val FnKeyId = K3KeyId("fn-key")
    }
}

class TouchPopupKey(
    val bounds: Rect,
    val display: K3StringOrDescriptor,
    val data: K3Key,
) {
    fun withNewBounds(bounds: Rect): TouchPopupKey {
        return TouchPopupKey(bounds, display, data)
    }
}

class TouchMultiTapKey(
    val display: K3StringOrDescriptor,
    val data: K3Key,
)

context(scope: CoroutineScope)
suspend fun computeTouchModel(
    model: K3Model,
    options: TouchModelOptions,
): TouchModel {
    val layersGroups = model.layersByForm.touch
    return when (layersGroups.size) {
        0 -> TouchModel.Empty
        1 -> TouchModel.Single(computeTouchKeyboard(model, layersGroups[0], options))
        else -> {
            val keyboards = layersGroups.map { layersGroup ->
                scope.async { computeTouchKeyboard(model, layersGroup, options) }
            }.awaitAll()
            TouchModel.Multiple(keyboards)
        }
    }
}

private inline fun List<K3KeyId>.withKeysResolved(
    model: K3Model,
    predicate: (K3Key) -> Boolean = { true },
): List<K3Key> {
    contract {
        callsInPlace(predicate)
    }
    return mapNotNull { keyId ->
        val key = model.keys.byKeyId[keyId]
        key?.takeIf(predicate)
    }
}

private data object TouchModelComputeRef : SourceFileRef {
    override fun toString(): String {
        return "TouchModelComputeRef"
    }
}
private fun FnKeyAction.asK3Key(): K3Key {
    return K3Key(
        id = K3KeyId(hashCode().toString()),
        output = output,
        width = width,
        origin = TouchModelComputeRef,
    )
}

private fun K3LayerId.isFieldVariantLayer(): Boolean =
    value.let { it.endsWith("-email") || it.endsWith("-url") || it.endsWith("-classic") }

private fun computeTouchKeyboard(
    model: K3Model,
    layersGroup: K3TouchLayers,
    options: TouchModelOptions,
): TouchKeyboard {
    val layers = layersGroup.layers
    val keyPredicate = { key: K3Key -> options.fnKeyEnabled || !key.id.isFnKeyId() }

    val numberRow = if (options.showNumberRow) {
        val numberRowLayer = layers[ImeLayerIds.Numrow]
        if (numberRowLayer != null && numberRowLayer.rows.size == 1) {
            numberRowLayer.rows[0].withKeysResolved(model, keyPredicate)
        } else null
    } else null

    val rowCount = layers.maxOf { (_, layer) -> layer.rows.size }.coerceAtLeast(4) +
        if (numberRow != null) 1 else 0

    // DogBoard: email and URL fields use "<layer>-email" / "<layer>-url" rows for base and shift
    val fieldSuffix = when (options.keyVariation) {
        KeyVariation.EMAIL_ADDRESS -> "-email"
        KeyVariation.URI -> "-url"
        else -> null
    }
    // and the Classic bottom row: "-classic", "-classic-email", "-classic-url"
    val variantSuffix = if (options.bottomRowClassic) "-classic" + fieldSuffix.orEmpty() else fieldSuffix
    val touchLayers = buildMap {
        for ((_, layer) in layers) {
            if (layer.id == ImeLayerIds.Numrow || layer.id.isFieldVariantLayer()) {
                // the numrow layer is not intended as a standalone layer => do not waste compute time on it here
                put(layer.id, TouchLayer.Empty)
                continue
            }
            val sourceLayer = variantSuffix
                ?.takeIf { layer.id == ImeLayerIds.Base || layer.id == ImeLayerIds.Shift }
                ?.let { layers[K3LayerId(layer.id.value + it)] }
                ?: layer
            val rows = buildList {
                // DogBoard: also on caps lock, so the row doesn't vanish when caps lock is on
                if (numberRow != null && (layer.id == ImeLayerIds.Base || layer.id == ImeLayerIds.Shift || layer.id == ImeLayerIds.Caps)) {
                    add(numberRow)
                }
                sourceLayer.rows.forEach { row ->
                    add(row.withKeysResolved(model, keyPredicate))
                }
            }
            // DogBoard: with the number row, Gboard's row heights (Geometry.rowWeights); else equal rows
            val weights = io.github.sillypuppyy.dogboard.feel.Geometry.rowWeights(rows.size, numberRow != null && rows.firstOrNull() === numberRow)
            val weightSum = weights.sum()
            val touchKeys = mutableListOf<TouchKey>()
            var currentY = 0f
            for ((rowIndex, row) in rows.withIndex()) {
                val keyHeight = weights[rowIndex] / weightSum
                val desiredWeightSum = 10f
                val fullWeightSum = row.fold(0f) { acc, key -> acc + key.width.toFloat() }
                val stretchWeightSum = row.fold(0f) { acc, key -> acc + (if (key.stretch) key.width.toFloat() else 0f) }
                val nonStretchSum = fullWeightSum - stretchWeightSum
                val mayGrowKeys = fullWeightSum <= desiredWeightSum
                val mayStretchKeys = mayGrowKeys && stretchWeightSum != 0f
                val desiredKeyWidth = when {
                    mayGrowKeys -> 1f / 10f
                    else -> desiredWeightSum / fullWeightSum / 10f
                }
                val desiredStretchKeyWidth = when {
                    mayGrowKeys && mayStretchKeys -> (desiredWeightSum - nonStretchSum) / desiredWeightSum
                    else -> 0f
                }
                var keyWidthSum = 0f
                val keyWidths = mutableListOf<Float>()
                for (key in row) {
                    val keyWidthPx = when {
                        mayStretchKeys && key.stretch -> desiredStretchKeyWidth * key.width.toFloat()
                            .roundToInt() / stretchWeightSum
                        else -> desiredKeyWidth * key.width.toFloat()
                    }
                    keyWidths.add(keyWidthPx)
                    keyWidthSum += keyWidthPx
                }
                var currentX = when {
                    mayGrowKeys && !mayStretchKeys -> (1f - keyWidthSum) / 2
                    else -> 0f
                }
                for ((i, key) in row.withIndex()) {
                    val isFnKey = key.id.isFnKeyId()
                    val keyWidthPx = keyWidths[i]
                    val keyBoundsPx = Rect(
                        offset = Offset(currentX, currentY),
                        size = Size(keyWidthPx, keyHeight),
                    )
                    val hitbox = when (i) {
                        // if first key -> extend to left edge of keyboard
                        0 -> {
                            Rect(
                                offset = Offset(0f, currentY),
                                size = Size(currentX + keyWidthPx, keyHeight),
                            )
                        }
                        // if last key -> extend to right edge of keyboard
                        row.size - 1 -> {
                            Rect(
                                offset = Offset(currentX, currentY),
                                size = Size(1f - currentX, keyHeight),
                            )
                        }
                        // else same as bounds
                        else -> keyBoundsPx
                    }
                    val popups = when {
                        isFnKey -> options.fnKeyArrangement.longPressActions.map { action ->
                            val key = action.asK3Key()
                            TouchPopupKey(
                                bounds = Rect.Zero,
                                display = computeKeyDisplay(model, key),
                                data = key,
                            )
                        }
                        else -> (key.longPressKeyIds ?: emptyList()).let { ids ->
                            // DogBoard: digits on long-press of the top row (q→1 … p→0) come first
                            val letter = (key.output as? org.k3lp.lib.text.K3String)?.toText()?.lowercase()?.takeIf { it.length == 1 }
                            val digit = letter
                                ?.takeIf { options.numberRowLongPress && it[0] in TOP_ROW }
                                ?.let { K3KeyId("s-" + DIGITS[TOP_ROW.indexOf(it[0])]) }?.takeIf { model.keys.byKeyId[it] != null }
                            // the hinted symbol as a key of the layout that types it (the symbol planes have them all)
                            val symbol = letter?.takeIf { options.symbolLongPress && it[0] in 'a'..'z' }
                                ?.let { io.github.sillypuppyy.dogboard.design.KbSpec.AND_HINT[it] }
                                ?.let { sym -> symbolKeyIds(model)[sym] }
                            listOfNotNull(digit ?: symbol) + ids to (digit ?: symbol)
                        }.takeIf { it.first.isNotEmpty() }?.let { (longPressKeyIds, added) ->
                            // an added digit or symbol is the default (it is what the hint shows)
                            val defaultKeyId = added ?: key.longPressDefaultKeyId?.takeIf { it in longPressKeyIds } ?: longPressKeyIds.first()
                            val defaultKeyIndex = longPressKeyIds.indexOf(defaultKeyId)
                            buildList {
                                longPressKeyIds.forEach { keyId ->
                                    val key = model.keys.byKeyId[keyId]!!
                                    val popupKey = TouchPopupKey(
                                        bounds = Rect.Zero,
                                        display = computeKeyDisplay(model, key),
                                        data = key,
                                    )
                                    add(popupKey)
                                }
                                if (defaultKeyIndex > 0) {
                                    val defaultPopupKey = removeAt(defaultKeyIndex)
                                    add(0, defaultPopupKey)
                                }
                            }
                        } ?: emptyList()
                    }
                    val multiTapKeys = when {
                        isFnKey -> emptyList() // no support for multi-tap on fn key
                        else -> key.multiTapKeyIds?.let { multiTapKeyIds ->
                            buildList {
                                add(
                                    TouchMultiTapKey(
                                        display = computeKeyDisplay(model, key),
                                        data = key,
                                    )
                                )
                                for (multiTapKeyId in multiTapKeyIds) {
                                    val multiTapKey = model.keys.byKeyId[multiTapKeyId] ?: continue
                                    add(
                                        TouchMultiTapKey(
                                            display = computeKeyDisplay(model, multiTapKey),
                                            data = multiTapKey,
                                        )
                                    )
                                }
                            }
                        } ?: emptyList()
                    }
                    val flicks = when {
                        isFnKey -> null // TODO
                        else -> key.flickId?.let { model.flicks.byFlickId[it] }
                    }
                    val attrs = if (isFnKey) options.fnKeyArrangement.simpleAction.asK3Key() else key
                    val display = computeKeyDisplay(model, attrs)
                    val touchKey = TouchKey(
                        bounds = keyBoundsPx,
                        hitbox = hitbox,
                        display = display,
                        attrs = attrs,
                        flick = flicks,
                        isShiftKey = key.isPureLayerSwitchKey() && when (layer.id) {
                            ImeLayerIds.Base -> key.layerId == ImeLayerIds.Shift || key.layerId == ImeLayerIds.Caps
                            ImeLayerIds.Shift -> key.layerId == ImeLayerIds.Base || key.layerId == ImeLayerIds.Caps
                            ImeLayerIds.Caps -> key.layerId == ImeLayerIds.Base || key.layerId == ImeLayerIds.Shift
                            else -> false
                        },
                        isRepeatable = attrs.output?.isRepeatable() ?: false,
                        isSuitableForSpaceBarDisplayOverride = attrs.isSuitableForSpaceBarDisplayOverride(display),
                        isSuitableForSimplePopup = attrs.isSuitableForSimplePopup(),
                        isSuitableForExtendedPopup = popups.isNotEmpty(),
                        extendedPopupKeys = popups,
                        longPressKeyHint = if (options.longPressKeyHintEnabled) popups.firstOrNull()?.display else null,
                        longPressKeyHintPlacement = options.longPressKeyHintPlacement,
                        shouldOverrideDisplayWithMultiTapKeys =
                            model.displays.byKeyId[key.id] == null && multiTapKeys.isNotEmpty(),
                        shouldHighlightPendingMultiTapKey = options.multiTapHighlightEnabled,
                        multiTapKeys = multiTapKeys,
                    )
                    touchKeys.add(touchKey)
                    currentX += keyWidthPx
                }
                currentY += keyHeight
            }
            val touchLayer = TouchLayer(touchKeys.toList())
            put(layer.id, touchLayer)
        }
    }

    return TouchKeyboard(
        layers = touchLayers,
        rowCount = rowCount,
        rowUnits = if (numberRow != null) io.github.sillypuppyy.dogboard.feel.Geometry.rowWeights(rowCount, true).sum() else rowCount.toFloat(),
        minDeviceWidthMm = layersGroup.minDeviceWidth,
    )
}

private fun computeKeyDisplay(model: K3Model, key: K3Key): K3StringOrDescriptor {
    val displayByKey = model.displays.byKeyId[key.id]
    if (displayByKey != null) {
        return displayByKey.display
    }
    if (key.output != null) {
        val displayByOut = model.displays.byOutput[key.output]
        if (displayByOut != null) {
            return displayByOut.display
        }
    }
    return key.output ?: key.id.value.asK3String()
}

fun computeKeyDisplay(model: K3Model, output: K3StringOrDescriptor): K3StringOrDescriptor {
    return model.displays.byOutput[output]?.display ?: output
}

fun K3StringOrDescriptor.isRepeatable(): Boolean {
    return when (this) {
        is K3String -> false
        is K3Descriptor -> ImeActions.Repeatable.contains(this)
    }
}

private val ASCII_SPACE = " ".asK3String()

fun K3Key.isPureLayerSwitchKey(): Boolean {
    return !gap && layerId != null && output == null && longPressKeyIds.isNullOrEmpty() &&
        multiTapKeyIds.isNullOrEmpty() && flickId == null
}

fun K3Key.isSuitableForSpaceBarDisplayOverride(display: K3StringOrDescriptor): Boolean {
    return layerId == null && output is K3String && output == ASCII_SPACE && output == display
}
fun K3Key.isSuitableForSimplePopup(): Boolean {
    return layerId == null && output is K3String && output != ASCII_SPACE
}

/** DogBoard: the fn (emoji / language) key, also as the Classic bottom row's narrower copy. */
fun K3KeyId.isFnKeyId(): Boolean = this == TouchKey.FnKeyId || value == "fn-key-classic"

// DogBoard: the top letter row and its digits (number row on long-press)
private const val TOP_ROW = "qwertyuiop"
private const val DIGITS = "1234567890"

/** DogBoard: a key id that types each single character, from the symbol planes (ids "s-…") first. */
private val symbolKeyIdCache = java.util.WeakHashMap<Any, Map<String, K3KeyId>>()

private fun symbolKeyIds(model: K3Model): Map<String, K3KeyId> = synchronized(symbolKeyIdCache) {
    symbolKeyIdCache.getOrPut(model) {
        val out = HashMap<String, K3KeyId>()
        for ((id, key) in model.keys.byKeyId) {
            val text = (key.output as? org.k3lp.lib.text.K3String)?.toText() ?: continue
            if (text.length == 1 && (text !in out || id.toString().startsWith("s-"))) out[text] = id
        }
        out
    }
}
