package io.github.sillypuppyy.dogboard.uitest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A copied image on the bar's paste chip, as a screenshot (files/uitest/paste-chip-image.png): the chip showed an
 * empty preview on the user's phone (2026-11-21). The image is drawn here (no user photo), saved to MediaStore and
 * copied as a content URI, as a gallery app copies one.
 */
@RunWith(AndroidJUnit4::class)
class PasteChipImageTest {
    @Test fun pasteChipShowsTheImage() {
        val kb = KeyboardDriver()
        kb.selectDogBoard()
        val context = kb.instrumentation.targetContext
        val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            c.drawColor(Color.rgb(30, 120, 200))
            c.drawCircle(200f, 150f, 100f, Paint().apply { color = Color.rgb(250, 200, 40) })
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "dogboard-uitest-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        try {
            kb.instrumentation.runOnMainSync {
                (context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(ClipData.newUri(context.contentResolver, "image", uri))
            }
            kb.open()
            // the chip shows on the plain bar, not over the tool row
            kb.instrumentation.runOnMainSync { io.github.sillypuppyy.dogboard.ime.LiveUi.toolRow.value = false }
            SystemClock.sleep(3000)
            kb.screenshot("paste-chip-image")
        } finally {
            context.contentResolver.delete(uri, null, null)
            kb.close()
        }
    }
}
