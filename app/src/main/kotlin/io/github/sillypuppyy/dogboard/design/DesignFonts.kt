package io.github.sillypuppyy.dogboard.design

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import dev.patrickgold.florisboard.R

/**
 * The design's two fonts (Kb.dc.html:11): Roboto Flex for text and Material Symbols Rounded for
 * icons, both variable. CSS uses `font-optical-sizing: auto`, so the optical size axis follows
 * the font size; the families here do the same.
 */
object DesignFonts {
    private val flexCache = HashMap<Pair<Int, Int>, FontFamily>()
    private val symbolCache = HashMap<Triple<Int, Int, Int>, FontFamily>()

    /** Roboto Flex at [weight] and optical size [sizeSp] (rounded to whole sp). */
    fun robotoFlex(weight: Int, sizeSp: Float): FontFamily = synchronized(flexCache) {
        val key = weight to sizeSp.toInt().coerceIn(8, 144)
        flexCache.getOrPut(key) {
            FontFamily(
                Font(
                    R.font.roboto_flex,
                    weight = FontWeight(weight),
                    variationSettings = FontVariation.Settings(
                        FontVariation.weight(weight),
                        FontVariation.Setting("opsz", key.second.toFloat()),
                    ),
                ),
            )
        }
    }

    /** Material Symbols Rounded with axes FILL [fill] (0/1), wght [weight], opsz [sizeSp] (20-48). */
    fun symbols(fill: Int, weight: Int, sizeSp: Float): FontFamily = synchronized(symbolCache) {
        val key = Triple(fill, weight, sizeSp.toInt().coerceIn(20, 48))
        symbolCache.getOrPut(key) {
            FontFamily(
                Font(
                    R.font.material_symbols_rounded,
                    weight = FontWeight(weight),
                    variationSettings = FontVariation.Settings(
                        FontVariation.Setting("FILL", fill.toFloat()),
                        FontVariation.weight(weight),
                        FontVariation.Setting("opsz", key.third.toFloat()),
                    ),
                ),
            )
        }
    }
}

/** Design text: Roboto Flex at [size] / [weight], line-height 1 as on the key caps. */
fun designTextStyle(size: TextUnit, weight: Int, color: Color): TextStyle = TextStyle(
    fontFamily = DesignFonts.robotoFlex(weight, size.value),
    fontWeight = FontWeight(weight),
    fontSize = size,
    lineHeight = size,
    color = color,
    textAlign = TextAlign.Center,
    // CSS line-height: 1 has no font padding and centres the glyphs in the line
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/**
 * A Material Symbols icon by its design name ("shift", "keyboard_return", ...), as the design
 * draws them: `font-family: 'Material Symbols Rounded'; font-size: [size]; line-height: 1;
 * font-variation-settings: 'FILL' [fill], 'wght' [weight]`.
 */
@Composable
fun Symbol(
    name: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    fill: Int = 0,
    weight: Int = 400,
) {
    val text = remember(name) {
        val cp = SymbolCodepoints[name] ?: error("icon \"$name\" is not bundled; add it to tools/fonts/symbols.txt")
        String(Character.toChars(cp))
    }
    BasicText(
        text = text,
        modifier = modifier,
        overflow = TextOverflow.Visible,
        style = TextStyle(
            fontFamily = DesignFonts.symbols(fill, weight, size.value),
            fontSize = size,
            lineHeight = size,
            color = color,
            textAlign = TextAlign.Center,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        ),
    )
}
