package io.github.sillypuppyy.dogboard.design

import androidx.compose.ui.geometry.Rect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.github.sillypuppyy.dogboard.design.DesignLint.Kind
import io.github.sillypuppyy.dogboard.design.DesignLint.Node

/** The lint's rules on hand-made layouts (the device run checks the real ones). */
class DesignLintTest : FunSpec({
    fun lint(vararg nodes: Node) = DesignLint().apply { nodes.forEach { record(it) } }.check("kb", 375f)
    val root = Node("kb", null, Kind.BOX, Rect(0f, 0f, 375f, 296f))

    test("a clean layout passes") {
        lint(
            root,
            Node("cap:q", "kb", Kind.CAP, Rect(3f, 54f, 34.5f, 96f), expected = Rect(3f, 54f, 34.5f, 96f)),
            Node("cap:q/label", "cap:q", Kind.LABEL, Rect(12f, 63f, 25.5f, 87f), text = "q", textWidth = 13.5f, boxWidth = 31.5f),
        ).shouldBeEmpty()
    }
    test("text wider than its box") {
        lint(root, Node("t", "kb", Kind.TEXT, Rect(0f, 0f, 10f, 10f), text = "Undo", textWidth = 33.5f, boxWidth = 9.9f)).single() shouldStartWith "OVERFLOW t"
    }
    test("truncated and wrapped text") {
        lint(root, Node("t", "kb", Kind.TEXT, Rect(0f, 0f, 60f, 20f), text = "keyboards", textWidth = 60f, boxWidth = 68f, truncated = true)).single() shouldStartWith "TRUNCATED"
        lint(root, Node("t", "kb", Kind.TEXT, Rect(0f, 0f, 60f, 40f), text = "a b", textWidth = 60f, boxWidth = 68f, lines = 2, maxLines = 1)).single() shouldStartWith "WRAP"
    }
    test("escaping the parent, but not for floating popups") {
        lint(root, Node("c", "kb", Kind.BOX, Rect(0f, -4f, 34f, 42f))).single() shouldStartWith "ESCAPE c"
        lint(root, Node("pop", "kb", Kind.FLOATING, Rect(0f, -60f, 50f, -2f))).shouldBeEmpty()
    }
    test("off the keyboard sideways, unless inside a scrolling row") {
        lint(root, Node("pop", "kb", Kind.FLOATING, Rect(2f, 0f, 384f, 54f))).single() shouldStartWith "OFFSCREEN pop"
        lint(root, Node("row", "kb", Kind.BOX, Rect(0f, 48f, 375f, 92f)), Node("dev:|", "row", Kind.SCROLLED, Rect(400f, 52f, 438f, 88f)),
            Node("dev:|/text", "dev:|", Kind.TEXT, Rect(415f, 60f, 420f, 80f), textWidth = 5f, boxWidth = 20f)).shouldBeEmpty()
    }
    test("spacing: a cap 0.6 dp from its spec position") {
        lint(root, Node("cap:t", "kb", Kind.CAP, Rect(152.4f, 54f, 183.9f, 96f), expected = Rect(153f, 54f, 184.5f, 96f))).single() shouldStartWith "SPACING cap:t"
    }
    test("label off centre, overlapping caps") {
        lint(root, Node("cap", "kb", Kind.CAP, Rect(0f, 0f, 40f, 40f)), Node("cap/label", "cap", Kind.LABEL, Rect(0f, 10f, 10f, 30f), textWidth = 10f, boxWidth = 40f)).single() shouldStartWith "OFFCENTRE"
        lint(root, Node("a", "kb", Kind.CAP, Rect(0f, 0f, 40f, 40f)), Node("b", "kb", Kind.CAP, Rect(39f, 0f, 80f, 40f))).single() shouldStartWith "OVERLAP"
    }
    test("tolerance: half a dp is rounding") {
        lint(root, Node("cap:t", "kb", Kind.CAP, Rect(152.6f, 54.2f, 184.1f, 96.2f), expected = Rect(153f, 54f, 184.5f, 96f))).shouldBeEmpty()
        DesignLint.TOL shouldBe 0.5f
    }
})
