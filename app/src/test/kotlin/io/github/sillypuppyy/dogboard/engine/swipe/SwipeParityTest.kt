package io.github.sillypuppyy.dogboard.engine.swipe

import io.github.sillypuppyy.dogboard.engine.Lexicon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/** SwipePath and SwipeBeam against the Python tools on real FUTO swipes (tools/swipe/make_fixture.py). */
class SwipeParityTest {
    private val swipes = Json.parseToJsonElement(File("src/test/resources/swipe-fixture.json").readText()).jsonArray.map { it.jsonObject }
    private fun JsonObject.floats(key: String) = (this[key] as JsonArray).map { it.jsonPrimitive.float }.toFloatArray()

    @Test
    fun featuresMatchPython() {
        for (s in swipes) {
            val got = SwipePath.features(s.floats("xs"), s.floats("ys"))
            val want = s.floats("features")
            assertEquals(want.size, got.size, s["word"].toString())
            for (i in want.indices) assertTrue(abs(want[i] - got[i]) < 1e-3f, "${s["word"]} feature $i: ${want[i]} vs ${got[i]}")
        }
    }

    @Test
    fun beamMatchesPython() {
        val lexicon = File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
        var same = 0
        for (s in swipes) {
            val logp = s.floats("logp")
            val top = SwipeBeam.decode(logp, logp.size / 27, lexicon, top = 3).map { lexicon.word(it.node)!!.lowercase() }
            val want = s["top"]!!.jsonArray.map { it.jsonPrimitive.content }
            if (top.firstOrNull() == want.firstOrNull()) same++
            println("SWIPE ${s["word"]!!.jsonPrimitive.content}: kotlin $top python $want")
        }
        assertTrue(same >= swipes.size - 1, "top-1 agrees on $same of ${swipes.size}")
    }

    @Test
    fun keyUnitsFitAnyKeyboard() {
        // a keyboard drawn at 108 px per key, rows 150 px, letters 300 px from the top
        val centres = SwipePath.KEY_UNITS.mapValues { (_, u) -> (u.first * 108f + 4f) to (u.second * 150f + 300f) }
        val map = SwipePath.ToKeyUnits(centres)
        for ((c, u) in SwipePath.KEY_UNITS) {
            val (px, py) = centres[c]!!
            assertTrue(abs(map.x(px) - u.first) < 1e-3f && abs(map.y(py) - u.second) < 1e-3f, "$c")
        }
    }
}
