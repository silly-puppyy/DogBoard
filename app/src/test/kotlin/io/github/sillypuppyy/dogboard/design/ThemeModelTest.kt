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
 * labels worked out from them): every role's colour, its source, the base, the tokens and the other version.
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
                r.vals shouldBe strings(c["vals"]!!.jsonObject)
                r.src shouldBe strings(c["src"]!!.jsonObject)
            }
        }
    }
    test("the keyboard tokens (shape: radius 12, weight 600, size 108 %)") {
        for (c in cases) {
            val v = ThemeModel.Version(bgOf(c["bg"]!!.jsonObject), c["tint"]!!.jsonPrimitive.boolean, strings(c["ov"]?.jsonObject))
            val want = strings(c["roles"]!!.jsonObject) - "bg"
            val got = ThemeModel.toRoles(v, ThemeModel.Shape(12.0, 600, 108, 1.5), "") - "bg"
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
})

private inline fun <T> withClue(clue: String, block: () -> T): T = io.kotest.assertions.withClue(clue, block)
