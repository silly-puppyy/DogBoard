package io.github.sillypuppyy.dogboard.uitest

import android.content.ClipData
import dev.patrickgold.florisboard.clipboardManager
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
 * empty preview on a phone (2026-11-21). The image is drawn here (no user photo), saved to MediaStore and
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
            // straight to the keyboard's clipboard (the test runs in its process), as its system-clipboard listener does:
            // a copy made from the test's background process never reached either keyboard
            val clip = ClipData.newUri(context.contentResolver, "image", uri)
            val item = dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem.fromClipData(context, clip, cloneUri = true)
            context.clipboardManager().value.updatePrimaryClip(item)
            kb.open()
            // the chip shows on the plain bar, not over the tool row
            kb.instrumentation.runOnMainSync { io.github.sillypuppyy.dogboard.ime.LiveUi.toolRow.value = false }
            SystemClock.sleep(3000)
            kb.screenshot("paste-chip-image")
            // the clipboard panel's row for it: a window onto the picture (KbClipRows.WindowImage)
            kb.panel("clipboard")
            SystemClock.sleep(1500)
            kb.screenshot("clipboard-image-row")
            // the list scrolled up a little (the row higher in the list): the slice moves up the picture
            kb.shell("input swipe 540 2150 540 2060 900")
            SystemClock.sleep(1200)
            kb.screenshot("clipboard-image-row-scrolled")
        } finally {
            context.contentResolver.delete(uri, null, null)
            kb.close()
        }
    }
}
