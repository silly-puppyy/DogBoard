package io.github.sillypuppyy.dogboard.design

import android.content.Context
import android.os.Build

/**
 * The default theme in the system's Material You colours (from the wallpaper), the way Gboard
 * colours its keyboard (gboard-study: body = surface container, letter keys = the lowest container,
 * function and action keys = the tonal secondary container, labels = on-surface; 8 dp corners).
 * Android 14+ (the system colour roles); null on older versions.
 */
object MaterialYouTheme {
    fun tokens(context: Context, dark: Boolean): Map<String, String>? {
        if (Build.VERSION.SDK_INT < 34) return null
        fun c(light: Int, night: Int) = "#%06x".format(context.getColor(if (dark) night else light) and 0xffffff)
        val r = android.R.color::class.java
        fun role(name: String) = c(r.getField("system_${name}_light").getInt(null), r.getField("system_${name}_dark").getInt(null))
        val surface = role("surface_container")
        // letter keys: the lowest container (white) in light; in dark Gboard lifts them to a grey
        // above the body (bright_key_on_dynamic_color_dark_theme), not the lowest container (black)
        val lowest = if (dark) role("surface_container_high") else role("surface_container_lowest")
        val highest = role("surface_container_highest")
        val onSurface = role("on_surface")
        val variant = role("on_surface_variant")
        val second = role("secondary_container")
        val onSecond = role("on_secondary_container")
        val primary = role("primary")
        val onPrimary = role("on_primary")
        return mapOf(
            "bg" to surface, "key" to lowest, "keyFg" to onSurface,
            "fn" to second, "fnFg" to onSecond, "act" to second, "actFg" to onSecond,
            "chip" to second, "chipFg" to onSecond, "pop" to highest, "popFg" to onSurface,
            "sel" to primary, "selFg" to onPrimary, "hint" to variant, "div" to role("outline_variant"),
            "barFg" to onSurface, "barMuted" to variant, "tile" to lowest, "tileFg" to onSurface,
            "pressKey" to highest, "pressFn" to if (dark) role("surface_bright") else role("surface_container_high"), "lock" to primary, "lockFg" to onPrimary,
            "radius" to "8", "gboardRow" to "1",
        )
    }

    /**
     * Before Android 14 (no system colour roles): neutral greys in the same roles, not the old
     * blue palette (user 2026-11-17: Gboard reads neutral, ours had an obvious blue tint).
     */
    val NEUTRAL_LIGHT = mapOf(
        "bg" to "#eeeeee", "key" to "#ffffff", "keyFg" to "#1f1f1f", "fn" to "#d9d9d9", "fnFg" to "#1f1f1f",
        "act" to "#d9d9d9", "actFg" to "#1f1f1f", "chip" to "#d9d9d9", "chipFg" to "#1f1f1f", "pop" to "#e3e3e3", "popFg" to "#1f1f1f",
        "sel" to "#474747", "selFg" to "#ffffff", "hint" to "#5e5e5e", "div" to "#c7c7c7", "barFg" to "#1f1f1f", "barMuted" to "#474747",
        "tile" to "#ffffff", "tileFg" to "#1f1f1f", "pressKey" to "#e3e3e3", "pressFn" to "#c6c6c6", "lock" to "#474747", "lockFg" to "#ffffff",
        "radius" to "8", "gboardRow" to "1",
    )
    val NEUTRAL_DARK = mapOf(
        "bg" to "#1b1b1b", "key" to "#2a2a2a", "keyFg" to "#e3e3e3", "fn" to "#3b3b3b", "fnFg" to "#e3e3e3",
        "act" to "#3b3b3b", "actFg" to "#e3e3e3", "chip" to "#3b3b3b", "chipFg" to "#e3e3e3", "pop" to "#353535", "popFg" to "#e3e3e3",
        "sel" to "#c7c7c7", "selFg" to "#1f1f1f", "hint" to "#ababab", "div" to "#474747", "barFg" to "#e3e3e3", "barMuted" to "#c7c7c7",
        "tile" to "#2a2a2a", "tileFg" to "#e3e3e3", "pressKey" to "#353535", "pressFn" to "#474747", "lock" to "#c7c7c7", "lockFg" to "#1f1f1f",
        "radius" to "8", "gboardRow" to "1",
    )

    /** The default theme's (light, dark) tokens: Material You from the system, else the neutral greys. */
    fun forDevice(context: Context): Pair<Map<String, String>, Map<String, String>> =
        tokens(context, false)?.let { l -> tokens(context, true)?.let { d -> l to d } } ?: (NEUTRAL_LIGHT to NEUTRAL_DARK)

    /** Set by the live keyboard: the default theme's tokens are these instead (null: the fixed palette). */
    @Volatile var override: Pair<Map<String, String>, Map<String, String>>? = null
}
