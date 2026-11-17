package io.github.sillypuppyy.dogboard.design

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The photo page's readability rules (SetPhoto.dc.html:128-171) on the canvas's own photo data. */
class PhotoReadabilityTest : FunSpec({
    // the canvas's measured percentiles for its sample photos (SetPhoto.dc.html:115-118)
    fun photo(vararg t: Pair<Double, Double>) = GlassPhoto.Analysis(t.toList(), listOf(Triple("#ffaf7c", "#411b00", "#7f441a")))
    val cat = photo(0.2414 to 0.6333, 0.2936 to 0.6132, 0.3208 to 0.5941, 0.3419 to 0.5776, 0.3604 to 0.5628, 0.3748 to 0.5552, 0.3888 to 0.5518)
    val coffee = photo(0.053 to 0.8469, 0.0664 to 0.7743, 0.0924 to 0.73, 0.1125 to 0.6923, 0.129 to 0.6628, 0.1373 to 0.6427, 0.1442 to 0.624)

    test("auto: the least dimming that makes white labels readable") {
        val a = PhotoReadability.auto(cat)
        a.tint shouldBe 0.0
        (PhotoReadability.measure(cat, a).white >= 4.5) shouldBe true
        (PhotoReadability.measure(cat, a.copy(dim = a.dim - 0.05)).white < 4.5) shouldBe true
        // the canvas's own note for the cat: "white labels, photo dimmed 30%"
        a.dim shouldBe 0.3
        PhotoReadability.describe(cat, a) shouldBe "white labels, photo dimmed 30%, no blur, return key from the photo"
    }

    test("fix it reaches 4.5:1") {
        val bad = PhotoReadability.Look()
        (PhotoReadability.measure(coffee, bad).readable) shouldBe false
        PhotoReadability.measure(coffee, PhotoReadability.fix(coffee, bad)).readable shouldBe true
    }

    test("blur interpolates between the measured levels") {
        cat.at(2f).first shouldBe (0.2414 + (0.2936 - 0.2414) * 0.5)
    }
})
