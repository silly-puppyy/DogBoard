package io.github.sillypuppyy.dogboard.settings

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The update check's version order: by number, part by part ("0.6.10" after "0.6.9"), a "-debug" suffix ignored. */
class AppUpdatesTest : FunSpec({
    test("newer by number, not by text") {
        AppUpdates.isNewer("0.6.10", "0.6.9") shouldBe true
        AppUpdates.isNewer("0.7", "0.6.9") shouldBe true
        AppUpdates.isNewer("0.6.0", "0.6.0") shouldBe false
        AppUpdates.isNewer("0.6.0", "0.6.1") shouldBe false
        AppUpdates.isNewer("1.0.0", "0.6.0-debug") shouldBe true
    }
})
