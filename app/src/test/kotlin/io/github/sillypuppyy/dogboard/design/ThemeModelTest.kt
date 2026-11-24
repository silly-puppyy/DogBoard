package io.github.sillypuppyy.dogboard.design

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.boolean
import java.io.File

/**
 * ThemeModel against the design's own code: theme-model-cases.json is ThemeEditor.dc.html's lib() run in node on 38
 * backgrounds (solid colours tinted and not, gradients, the four sample photos bright and dimmed, overrides with
 * labels worked out from them): every role's colour, its source, the base, the tokens and the other version. Updated
 * 2026-11-24 for the canvas's whiter function keys on tinted gradients (fn = min(0.92, keys + 0.28), ThemeEditor:628).
 */
class ThemeModelTest : FunSpec({
    val cases = Json.parseToJsonElement(File("src/test/resources/theme-model-cases.json").readText()).jsonArray.map { it.jsonObject }

    fun strings(o: JsonObject?) = o?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()

    fun bgOf(o: JsonObject) = ThemeModel.Bg(
        type = o["type"]!!.jsonPrimitive.content,
        color = o["color"]?.jsonPrimitive?.content ?: "#ededf4",
        stops = o["stops"]?.jsonArray?.map { s -> ThemeModel.Stop(s.jsonObject["c"]!!.jsonPrimitive.content, s.jsonObject["p"]!!.jsonPrimitive.int) }
            ?: listOf(ThemeModel.Stop("#ffe3d1", 0), ThemeModel.Stop("#f4c4d8", 100)),
        angle = o["angle"]?.jsonPrimitive?.int ?: 180,
        photo = o["photo"]?.jsonPrimitive?.content ?: "",
        dim = o["dim"]?.jsonPrimitive?.double ?: 0.0,
        photoCols = o["photoCols"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        photoAcc = o["photoAcc"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
    )

    test("every role, its source and the base, as the design computes them") {
        for (c in cases) {
            val v = ThemeModel.Version(bgOf(c["bg"]!!.jsonObject), c["tint"]!!.jsonPrimitive.boolean, strings(c["ov"]?.jsonObject))
            val r = ThemeModel.resolve(v)
            withClue(c["bg"].toString()) {
                r.base shouldBe c["base"]!!.jsonPrimitive.content
                // barIcon came after the reference (it follows barFg unless set)
                r.vals - "barIcon" shouldBe strings(c["vals"]!!.jsonObject)
                r.src - "barIcon" shouldBe strings(c["src"]!!.jsonObject)
                r.vals["barIcon"] shouldBe r.vals["barFg"]
            }
        }
    }
    test("the keyboard tokens (shape: radius 12, weight 600, size 108 %)") {
        for (c in cases) {
            val v = ThemeModel.Version(bgOf(c["bg"]!!.jsonObject), c["tint"]!!.jsonPrimitive.boolean, strings(c["ov"]?.jsonObject))
            val want = strings(c["roles"]!!.jsonObject) - "bg"
            val got = ThemeModel.toRoles(v, ThemeModel.Shape(12.0, 600, 108, 1.5), "") - "bg" - "navIcons"
            withClue(c["bg"].toString()) { got shouldBe want }
        }
    }
    test("the other version: the same family moved to the other side") {
        for (c in cases) {
            val v = ThemeModel.Version(bgOf(c["bg"]!!.jsonObject), c["tint"]!!.jsonPrimitive.boolean, strings(c["ov"]?.jsonObject))
            for (side in listOf("dark", "light")) {
                val want = c[side]?.jsonObject ?: continue
                val got = ThemeModel.otherVersion(v, side).bg
                withClue("${c["bg"]} -> $side") {
                    if (got.type == "solid") got.color shouldBe want["color"]!!.jsonPrimitive.content
                    else got.stops.map { it.c } shouldBe want["stops"]!!.jsonArray.map { it.jsonObject["c"]!!.jsonPrimitive.content }
                }
            }
        }
    }
    test("an editor theme is its own theme id, and the keyboard draws its version for the mode") {
        val t = ThemeModel.Theme(
            "Labrador", "both", ThemeModel.Version(ThemeModel.Bg(color = "#e8dcc8"), tint = true),
            ThemeModel.Version(ThemeModel.Bg(color = "#2b1d16"), tint = true), ThemeModel.Shape(radius = 12.0), sound = "clack",
        )
        val id = ThemeModel.encode(t)
        ThemeModel.decode(id) shouldBe t
        val light = KbThemes.tokens(id, dark = false); val dark = KbThemes.tokens(id, dark = true)
        light.bg shouldBe css("#e8dcc8")
        dark.bg shouldBe css("#2b1d16")
        light.key shouldBe css(ThemeModel.resolve(t.light!!).vals.getValue("key"))
        light.radius shouldBe 12f
    }
    test("Copy and edit of a Pride theme keeps its stops as written, and saving doesn't grow them") {
        val light = KbThemes.raw("pride", false); val dark = KbThemes.raw("pride", true)
        val t = ThemeModel.fromBuiltIn("pride", light, dark)
        val v = (t.light ?: t.dark)!!
        val n = v.bg.stops.size
        withClue("stops $n") { (n in 2..7) shouldBe true }
        val again = ThemeModel.fromBuiltIn("pride", t.light?.let { ThemeModel.tokensOf(t, false) }, t.dark?.let { ThemeModel.tokensOf(t, true) })
        (again.light ?: again.dark)!!.bg.stops.size shouldBe n
    }
    test("a gradient's colour past its last stop is the last colour, as the shader pads") {
        val g = CssGradient.parse("linear-gradient(180deg, #ff0000 0%, #0000ff 80%)")!!
        g.colorAt(50f, 99f, 100f, 100f) shouldBe androidx.compose.ui.graphics.Color(0xff0000ff)
    }
})

private inline fun <T> withClue(clue: String, block: () -> T): T = io.kotest.assertions.withClue(clue, block)
