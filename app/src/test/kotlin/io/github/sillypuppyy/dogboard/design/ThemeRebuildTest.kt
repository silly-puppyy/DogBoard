package io.github.sillypuppyy.dogboard.design

import io.kotest.core.spec.style.FunSpec

/**
 * Can the theme editor rebuild every built-in theme (Copy and edit)? Each built-in, bright and dark, goes through
 * ThemeModel.fromBuiltIn and back to keyboard tokens; every token that comes back different or not at all is listed
 * (build/reports/theme-rebuild.txt). A measurement of the editor's reach, not a pass/fail gate.
 */
class ThemeRebuildTest : FunSpec({
    fun norm(v: String?): String? = v?.trim()?.lowercase()?.replace(" ", "")

    test("rebuild built-ins") {
        val out = StringBuilder()
        var total = 0; var lost = 0
        for (id in KbThemes.ids + KbThemes.moreIds) for (dark in listOf(false, true)) {
            val raw = KbThemes.raw(id, dark) ?: continue
            val t = ThemeModel.fromBuiltIn(id, if (dark) null else raw, if (dark) raw else null)
            val back = ThemeModel.tokensOf(t, dark)
            val diff = raw.keys.sorted().filter { k -> norm(raw[k]) != norm(back[k]) }
            total += raw.size; lost += diff.size
            if (diff.isNotEmpty()) out.append("$id ${if (dark) "dark" else "light"}: ${diff.size}/${raw.size} ")
                .append(diff.joinToString(", ") { k -> "$k=${raw[k]} -> ${back[k] ?: "missing"}" }).append('\n')
        }
        out.insert(0, "tokens not rebuilt: $lost of $total\n")
        java.io.File("build/reports").mkdirs()
        java.io.File("build/reports/theme-rebuild.txt").writeText(out.toString())
        println(out.lineSequence().first())
    }
})
