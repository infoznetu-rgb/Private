package sk.preplatok.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrTest {
    @Test fun bundledModelRecognizesCleanDigitsWithoutAppNetworkPermission() = runBlocking {
        val bitmap = Bitmap.createBitmap(1200,300,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText("12345.67",60f,210f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 170f; typeface = Typeface.MONOSPACE })
        val scan = MeterOcr.recognize(bitmap)
        assertTrue("Expected clean fixture digits, got ${scan.candidates}",scan.candidates.any { it.text == "12345.67" || it.text == "12345,67" })
    }
    @Test fun cropUsesNormalizedBoundsAndKeepsAtLeastOnePixel() {
        val bitmap = Bitmap.createBitmap(100,200,Bitmap.Config.ARGB_8888)
        val cropped = MeterOcr.crop(bitmap,0.1f,0.2f,0.9f,0.8f)
        assertEquals(80,cropped.width)
        assertEquals(120,cropped.height)
        assertEquals(1,MeterOcr.crop(bitmap,1f,1f,1f,1f).width)
    }
}
