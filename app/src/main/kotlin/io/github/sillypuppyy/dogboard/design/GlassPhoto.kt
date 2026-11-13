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
        luminance(src)
    }.getOrNull()

    fun load(context: Context): Bitmap? = file(context).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.absolutePath) }

    /** Mean WCAG relative luminance over a coarse grid of pixels. */
    private fun luminance(b: Bitmap): Double {
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
