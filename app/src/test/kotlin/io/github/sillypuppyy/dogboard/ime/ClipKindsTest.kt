package io.github.sillypuppyy.dogboard.ime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ClipKindsTest : FunSpec({
    test("colours: only the whole clip, in the usual CSS forms") {
        ClipKinds.color("#4B6EAF")?.hex shouldBe "#4B6EAF"
        ClipKinds.color(" #4b6eaf ")?.rgb shouldBe "rgb(75 110 175)"
        ClipKinds.color("#fff")?.hex shouldBe "#FFFFFF"
        ClipKinds.color("rgb(75, 110, 175)")?.hex shouldBe "#4B6EAF"
        ClipKinds.color("rgb(75 110 175)")?.hsl shouldBe "hsl(219 40% 49%)"
        ClipKinds.color("hsl(219 40% 49%)")?.hex shouldBe "#4B6EAF"
        // not colours: false positives are worse than misses (user, 2026-11-17)
        ClipKinds.color("#hashtag") shouldBe null
        ClipKinds.color("#1234") shouldBe null
        ClipKinds.color("Use #4B6EAF for the button") shouldBe null
        ClipKinds.color("rgb(300, 0, 0)") shouldBe null
        ClipKinds.color("#123") shouldBe ClipKinds.color("#112233")?.copy(copied = "#123")
    }

    test("the other formats leave out the one copied") {
        ClipKinds.color("#4B6EAF")!!.others().map { it.first } shouldBe listOf("RGB", "HSL")
        ClipKinds.color("rgb(75 110 175)")!!.others().map { it.first } shouldBe listOf("HEX", "HSL")
    }

    test("codes: only 4-8 digits, and only in a number field") {
        ClipKinds.code("482913", numberField = true) shouldBe "482913"
        ClipKinds.code("482 913", numberField = true) shouldBe "482913"
        ClipKinds.code("482-913", numberField = true) shouldBe "482913"
        ClipKinds.code("482913", numberField = false) shouldBe null
        ClipKinds.code("123", numberField = true) shouldBe null
        ClipKinds.code("123456789", numberField = true) shouldBe null
        ClipKinds.code("12.50", numberField = true) shouldBe null
        ClipKinds.code("Your code is 482913", numberField = true) shouldBe null
    }
})
