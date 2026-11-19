package io.github.sillypuppyy.dogboard.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import kotlin.math.pow

/**
 * The Glass theme's photo (design: Themes.dc.html "Glass: a photo behind the keys"; Kb:18-23):
 * the picture the user chose, kept downscaled in the app's own storage.
 */
object GlassPhoto {
    private const val MAX_SIDE = 1080

    fun file(context: Context) = File(context.filesDir, "dogboard/glass.jpg")

    /**
     * Copies [uri] (from the photo picker) downscaled, and returns its average relative luminance
     * (0..1) for the label-contrast rule, or null if it can't be read.
     */
    fun save(context: Context, uri: Uri): Double? = runCatching {
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val side = maxOf(info.size.width, info.size.height)
            if (side > MAX_SIDE) decoder.setTargetSampleSize((side + MAX_SIDE - 1) / MAX_SIDE)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val out = file(context)
        out.parentFile?.mkdirs()
        out.outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        analysisFile(context).writeText(analyse(src).encode())
        luminance(src)
    }.getOrNull()

    /**
     * What the photo page needs to judge readability (SetPhoto.dc.html:115-142): the photo's 5th and
     * 95th percentile brightness (sRGB-encoded luminance, 0..1) at each blur of [BLURS] dp, and up to
     * three of its strong colours for the return key (each: key colour, label colour, the colour itself).
     */
    data class Analysis(val levels: List<Pair<Double, Double>>, val accents: List<Triple<String, String, String>>) {
        fun encode(): String = levels.joinToString(";") { "${it.first},${it.second}" } + "\n" +
            accents.joinToString(";") { "${it.first},${it.second},${it.third}" }

        /** :122-127 the percentiles at [blur] dp, between the measured levels. */
        fun at(blur: Float): Pair<Double, Double> {
            var i = 0
            while (i < BLURS.size - 2 && blur > BLURS[i + 1]) i++
            val f = ((blur - BLURS[i]) / (BLURS[i + 1] - BLURS[i])).coerceIn(0f, 1f).toDouble()
            return levels[i].first + (levels[i + 1].first - levels[i].first) * f to levels[i].second + (levels[i + 1].second - levels[i].second) * f
        }

        companion object {
            fun decode(text: String): Analysis? = runCatching {
                val (l, a) = (text.split("\n") + "").let { it[0] to it[1] }
                Analysis(
                    l.split(";").map { it.split(",").let { (x, y) -> x.toDouble() to y.toDouble() } },
                    a.split(";").filter { it.isNotBlank() }.map { it.split(",").let { (x, y, z) -> Triple(x, y, z) } },
                ).takeIf { it.levels.size == BLURS.size }
            }.getOrNull()
        }
    }

    val BLURS = listOf(0f, 4f, 8f, 12f, 16f, 20f, 25f)

    fun analysisFile(context: Context) = File(context.filesDir, "dogboard/glass.txt")

    /** The stored analysis of the current photo (made when it was picked; older photos get one now). */
    fun analysis(context: Context): Analysis? =
        analysisFile(context).takeIf { it.isFile }?.let { Analysis.decode(it.readText()) }
            ?: load(context)?.let { b -> analyse(b).also { runCatching { analysisFile(context).writeText(it.encode()) } } }

    fun analyse(b: Bitmap): Analysis {
        // the photo as the keyboard shows it, ~411 dp wide: 96 px, so 1 px is about 4.3 dp
        val w = 96
        val h = maxOf(8, b.height * w / maxOf(1, b.width))
        val small = Bitmap.createScaledBitmap(b, w, h, true)
        fun lin(c: Int): Double { val x = c / 255.0; return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
        val y = DoubleArray(w * h) { i -> val p = small.getPixel(i % w, i / w); 0.2126 * lin((p shr 16) and 255) + 0.7152 * lin((p shr 8) and 255) + 0.0722 * lin(p and 255) }
        fun enc(l: Double) = if (l <= 0.0031308) 12.92 * l else 1.055 * l.pow(1 / 2.4) - 0.055
        val levels = BLURS.map { blurDp ->
            val r = (blurDp / 4.3f / 2f).toInt() // CSS blur(b) is a Gaussian of sigma b / 2: a box of about that radius
            val v = DoubleArray(w * h)
            for (yy in 0 until h) for (xx in 0 until w) {
                var sum = 0.0; var n = 0
                for (dy in -r..r) for (dx in -r..r) {
                    val px = (xx + dx).coerceIn(0, w - 1); val py = (yy + dy).coerceIn(0, h - 1)
                    sum += y[py * w + px]; n++
                }
                v[yy * w + xx] = enc(sum / n)
            }
            v.sort()
            v[(v.size * 0.05).toInt()] to v[(v.size * 0.95).toInt().coerceAtMost(v.size - 1)]
        }
        return Analysis(levels, accents(small))
    }

    /**
     * Up to three strong colours of the photo, most present first, each made into a return key:
     * the colour lightened for the key (HSL lightness 0.75) and a dark label in its hue (0.14).
     */
    private fun accents(b: Bitmap): List<Triple<String, String, String>> {
        val weight = DoubleArray(12); val sums = Array(12) { DoubleArray(3) }
        val hsv = FloatArray(3)
        for (yy in 0 until b.height) for (xx in 0 until b.width) {
            val p = b.getPixel(xx, yy)
            android.graphics.Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue
            val bin = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
            val wgt = (hsv[1] * hsv[2]).toDouble()
            weight[bin] += wgt
            sums[bin][0] += ((p shr 16) and 255) * wgt; sums[bin][1] += ((p shr 8) and 255) * wgt; sums[bin][2] += (p and 255) * wgt
        }
        val total = weight.sum()
        return (0 until 12).filter { weight[it] > total * 0.05 }.sortedByDescending { weight[it] }.take(3).map { bin ->
            val r = (sums[bin][0] / weight[bin]).toInt(); val g = (sums[bin][1] / weight[bin]).toInt(); val bl = (sums[bin][2] / weight[bin]).toInt()
            val hsl = FloatArray(3)
            androidx.core.graphics.ColorUtils.RGBToHSL(r, g, bl, hsl)
            fun hex(c: Int) = "#%06x".format(c and 0xffffff)
            Triple(
                hex(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1].coerceIn(0.55f, 0.9f), 0.75f))),
                hex(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1].coerceIn(0.4f, 0.9f), 0.14f))),
                hex(android.graphics.Color.rgb(r, g, bl)),
            )
        }.ifEmpty { listOf(Triple("#f0b46a", "#3a2200", "#f0b46a")) }
    }

    fun load(context: Context): Bitmap? = file(context).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.absolutePath) }

    /** Mean WCAG relative luminance over a coarse grid of pixels. */
    fun luminance(b: Bitmap): Double {
        fun lin(c: Int): Double { val x = c / 255.0; return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
        var sum = 0.0
        var n = 0
        val step = maxOf(1, minOf(b.width, b.height) / 48)
        for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
            val p = b.getPixel(x, y)
            sum += 0.2126 * lin((p shr 16) and 255) + 0.7152 * lin((p shr 8) and 255) + 0.0722 * lin(p and 255)
            n++
        }
        return if (n == 0) 0.3 else sum / n
    }
}
