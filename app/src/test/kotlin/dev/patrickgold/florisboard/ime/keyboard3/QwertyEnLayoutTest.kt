package dev.patrickgold.florisboard.ime.keyboard3

import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchModelOptions
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.ime.keyboard3.touch.computeTouchModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.k3lp.lib.meta.source.SourceFileRef
import org.k3lp.lib.meta.source.TextSourceFile
import org.k3lp.lib.text.WillRequireMigrationToRichErrors
import org.k3lp.model.K3CompileResult
import org.k3lp.model.K3ImpliedImports
import org.k3lp.model.K3ImportResolver
import org.k3lp.model.compileToModel
import org.k3lp.model.layer.K3LayerId
import org.k3lp.lib.text.asK3String
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.net.URI

private val FOUNDATION_DIR = File("src/main/assets/extensions/org.florisboard.k3.foundation")

private fun sourceFile(relPath: String): TextSourceFile {
    val file = File(FOUNDATION_DIR, relPath)
    return TextSourceFile(object : SourceFileRef {
        override fun toString() = file.path
    }, file.readText())
}

@OptIn(WillRequireMigrationToRichErrors::class)
private suspend fun compileLayout(path: String): K3CompileResult {
    val importResolver = K3ImportResolver { importPath, _ ->
        val relPath = runCatching { URI(importPath).path?.removePrefix("/") }.getOrNull() ?: importPath
        sourceFile("import/$relPath")
    }
    val impliedImports = K3ImpliedImports(
        displays = listOf("flex://org.florisboard.k3.foundation/displays-implied.xml"),
        keys = listOf("flex://org.florisboard.k3.foundation/keys-implied.xml"),
    )
    return sourceFile(path).compileToModel(importResolver, impliedImports)
}

class QwertyEnLayoutTest : FunSpec({
    test("qwerty-en compiles without reports") {
        val result = compileLayout("keyboard/qwerty-en.xml")
        result.reports.map { it.toString() }.shouldBeEmpty()
        result.shouldBeInstanceOf<K3CompileResult.Success>()
    }

    test("every row of the main layers spans the full 10 units (home row 9, centred)") {
        val result = compileLayout("keyboard/qwerty-en.xml")
        result.shouldBeInstanceOf<K3CompileResult.Success>()
        val model = result.model
        val touchLayers = model.layersByForm.touch.single().layers
        val layerIds = listOf(ImeLayerIds.Base, ImeLayerIds.Shift, K3LayerId("symbol-1"), K3LayerId("symbol-2"))
        for (layerId in layerIds) {
            val layer = touchLayers[layerId] ?: error("missing layer $layerId")
            layer.rows.size shouldBe 4
            layer.rows.forEachIndexed { rowIndex, row ->
                val keys = row.map { model.keys.byKeyId[it] ?: error("unknown key $it in $layerId") }
                val isHomeRow = rowIndex == 1 && (layerId == ImeLayerIds.Base || layerId == ImeLayerIds.Shift)
                val expected = if (isHomeRow) 9.0 else 10.0
                keys.sumOf { it.width.toDouble() } shouldBe (expected plusOrMinus 0.01)
            }
        }
    }

    test("touch model can be computed (same code path the keyboard runs on open)") {
        val result = compileLayout("keyboard/qwerty-en.xml")
        result.shouldBeInstanceOf<K3CompileResult.Success>()
        coroutineScope {
            computeTouchModel(result.model, TouchModelOptions.Default)
        }
    }

    test("every key referenced by a row or a long-press list exists") {
        val result = compileLayout("keyboard/qwerty-en.xml")
        result.shouldBeInstanceOf<K3CompileResult.Success>()
        val model = result.model
        val referenced = buildSet {
            for ((_, layer) in model.layersByForm.touch.single().layers) {
                layer.rows.forEach { row -> addAll(row) }
            }
            for (key in model.keys.byKeyId.values) {
                key.longPressKeyIds?.let { addAll(it) }
                key.longPressDefaultKeyId?.let { add(it) }
            }
        }
        referenced.filter { model.keys.byKeyId[it] == null }.map { it.toString() }.sorted().shouldBeEmpty()
    }

    test("taps resolve like the iOS touch model on the real layout") {
        val result = compileLayout("keyboard/qwerty-en.xml")
        result.shouldBeInstanceOf<K3CompileResult.Success>()
        val touchModel = coroutineScope { computeTouchModel(result.model, TouchModelOptions.Default) }
        val keyboard = touchModel.selectKeyboard(deviceWidthMm = 70)
        val widthDp = 375f
        val heightDp = 216f
        fun tap(xDp: Float, yDp: Float) = keyboard.findKeyForTap(
            ImeLayerIds.Base, androidx.compose.ui.geometry.Offset(xDp / widthDp, yDp / heightDp),
            widthDp, heightDp, typingInterval = 0.5,
        )?.attrs?.output
        // letter centres
        tap(18.75f, 27f) shouldBe "q".asK3String()
        tap(187.5f, 81f) shouldBe "g".asK3String()
        // the 8 dp gap between shift and z goes to z, not to nothing
        tap(48f + 4f, 135f) shouldBe "z".asK3String()
        // inside the shift key's rectangle it stays shift (a layer switch, no text output)
        keyboard.findKeyForTap(
            ImeLayerIds.Base, androidx.compose.ui.geometry.Offset(20f / widthDp, 135f / heightDp),
            widthDp, heightDp, 0.5,
        )?.attrs?.layerId?.toString() shouldBe "shift"
        // 25 dp above the centre of g (row 2) is closer to g than to t or y in row 1
        tap(187.5f, 81f - 25f) shouldBe "g".asK3String()
    }

    test("email and URL fields get their own bottom row; other fields keep space") {
        val result = compileLayout("keyboard/qwerty-en.xml")
        result.shouldBeInstanceOf<K3CompileResult.Success>()
        suspend fun bottomRow(variation: KeyVariation, layer: org.k3lp.model.layer.K3LayerId): List<String> {
            val options = TouchModelOptions.Default.copy(keyVariation = variation)
            val keyboard = coroutineScope { computeTouchModel(result.model, options) }.selectKeyboard(deviceWidthMm = 70)
            val keys = keyboard.layers.getValue(layer).keys
            val bottom = keys.maxOf { it.bounds.top }
            // slot 1 is the fn key, which the app replaces with a generated key
            return keys.filter { it.bounds.top == bottom }.sortedBy { it.bounds.left }
                .mapIndexed { i, key -> if (i == 1) "fn-key" else key.attrs.id.toString() }
        }
        bottomRow(KeyVariation.NORMAL, ImeLayerIds.Base) shouldBe listOf("to-symbol-1", "fn-key", "space", "enter")
        bottomRow(KeyVariation.EMAIL_ADDRESS, ImeLayerIds.Base) shouldBe
            listOf("to-symbol-1", "fn-key", "space-email", "at-email", "period-email", "enter")
        bottomRow(KeyVariation.EMAIL_ADDRESS, ImeLayerIds.Shift) shouldBe
            listOf("to-symbol-1", "fn-key", "space-email", "at-email", "period-email", "enter")
        // URL fields keep a space bar (address bars take searches too)
        bottomRow(KeyVariation.URI, ImeLayerIds.Base) shouldBe
            listOf("to-symbol-1", "fn-key", "space-email", "slash-url", "period-url", "enter")
        bottomRow(KeyVariation.URI, ImeLayerIds.Shift) shouldBe
            listOf("to-symbol-1", "fn-key", "space-email", "slash-url", "period-url", "enter")
    }
})
