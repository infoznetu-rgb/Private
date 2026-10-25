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
    @Test fun exactSevenSegmentBitmapIsDecodedWithoutGuessing() {
        val b=Bitmap.createBitmap(520,140,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(Color.WHITE)
        val paint=Paint().apply { color=Color.BLACK }
        val shapes=listOf(floatArrayOf(10f,0f,50f,10f),floatArrayOf(50f,10f,60f,50f),floatArrayOf(50f,50f,60f,90f),floatArrayOf(10f,90f,50f,100f),floatArrayOf(0f,50f,10f,90f),floatArrayOf(0f,10f,10f,50f),floatArrayOf(10f,45f,50f,55f))
        val codes=listOf(0b1101101,0b1011011,0b1111111,0b1111111,0b1111110,0b1011111)
        codes.forEachIndexed { i,bits -> shapes.forEachIndexed { segment,r -> if(bits and (1 shl (6-segment))!=0)c.drawRect(20f+i*80+r[0],20f+r[1],20f+i*80+r[2],20f+r[3],paint) } }
        assertEquals("258806",ImageAnalysis.sevenSegment(b))
    }
    @Test fun syntheticPdfInvoiceIsRenderedAndReadOffline() = runBlocking {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val file=java.io.File.createTempFile("invoice-fixture-",".pdf",context.cacheDir)
        val pdf=android.graphics.pdf.PdfDocument()
        val page=pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(800,1000,1).create())
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=27f }
        page.canvas.drawColor(Color.WHITE)
        page.canvas.drawText("Cena 0,245 EUR/kWh",30f,100f,paint)
        page.canvas.drawText("Mesacna zaloha 45,00 EUR",30f,160f,paint)
        pdf.finishPage(page);file.outputStream().use(pdf::writeTo);pdf.close()
        try {
            val result=MeterOcr.documentFromUri(context,android.net.Uri.fromFile(file))
            val draft=InvoiceParser.parse(result.text,Energy.ELECTRICITY)
            assertTrue(draft.prices.any { decimal(it.value)?.compareTo("0.245".toBigDecimal())==0 })
            assertFalse(result.truncated)
        } finally { file.delete() }
    }
    @Test fun blankPhotoDoesNotProduceInventedDigits() = runBlocking {
        val blank=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(180,180,180)) }
        assertTrue(MeterOcr.recognize(blank).candidates.isEmpty())
        assertTrue(ImageAnalysis.quality(ImageAnalysis.gray(blank)).blur)
    }
    @Test fun syntheticWholeMeterFindsDisplayWithoutManualCrop() = runBlocking {
        val b=Bitmap.createBitmap(1280,800,Bitmap.Config.ARGB_8888)
        val c=Canvas(b);c.drawColor(Color.rgb(220,215,205));val p=Paint(Paint.ANTI_ALIAS_FLAG)
        p.color=Color.DKGRAY;c.drawRoundRect(180f,130f,1100f,650f,30f,30f,p)
        p.color=Color.rgb(186,205,175);c.drawRect(250f,260f,1020f,480f,p)
        p.color=Color.BLACK;p.textSize=135f;p.typeface=Typeface.MONOSPACE;c.drawText("258806",280f,420f,p)
        p.textSize=42f;c.drawText("kWh",880f,470f,p)
        assertTrue("No automatic display regions",ImageAnalysis.displayRegions(b).isNotEmpty())
        val scan=MeterOcr.recognize(b)
        assertTrue("Synthetic whole-meter fixture: ${scan.candidates}",scan.candidates.any { it.text=="258806" && !it.blocked })
    }
    @Test fun syntheticInvoiceImageIsReadLocally() = runBlocking {
        val b=Bitmap.createBitmap(1900,1000,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(Color.WHITE)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=52f;typeface=Typeface.DEFAULT }
        listOf("FAKTURA - ELEKTRINA","Cena 0,245 EUR/kWh vratane DPH","Mesacna zaloha 45,00 EUR","Fakturacne obdobie 01.01.2026 - 31.12.2026").forEachIndexed { i,line -> c.drawText(line,70f,150f+i*140f,p) }
        val draft=InvoiceParser.parse(MeterOcr.document(b),Energy.ELECTRICITY)
        assertTrue("Invoice price not found: $draft",draft.prices.any { decimal(it.value)?.compareTo("0.245".toBigDecimal())==0 })
        assertTrue(draft.advances.any { decimal(it.value)?.compareTo("45".toBigDecimal())==0 })
        assertTrue(draft.periods.contains(java.time.LocalDate.of(2026,1,1) to java.time.LocalDate.of(2026,12,31)))
    }
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
