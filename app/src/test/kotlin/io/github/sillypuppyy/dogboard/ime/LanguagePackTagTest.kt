package io.github.sillypuppyy.dogboard.ime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Which pack folders a keyboard language looks in. */
class LanguagePackTagTest : FunSpec({
    test("a tag with the system's regional preferences finds its pack (en-US-u-fw-mon -> en-US)") {
        LanguagePack.candidates("en-US-u-fw-mon") shouldBe listOf("en-US", "en")
        LanguagePack.candidates("fr-FR-u-mu-celsius") shouldBe listOf("fr-FR", "fr")
    }

    test("plain tags as before") {
        LanguagePack.candidates("es-ES") shouldBe listOf("es-ES", "es")
        LanguagePack.candidates("bg") shouldBe listOf("bg")
    }
})
