package dev.patrickgold.florisboard.ime.keyboard3

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
})
