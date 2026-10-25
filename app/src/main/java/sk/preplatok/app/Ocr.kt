package sk.preplatok.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class Candidate(val text: String, val rect: Rect, val warnings: List<String> = emptyList(), val blocked: Boolean = false, val evidence: String = "", val passes: Int = 1)
data class Scan(val bitmap: Bitmap, val candidates: List<Candidate>, val warnings: List<String> = emptyList())
data class DocumentScan(val bitmap: Bitmap, val text: String, val truncated: Boolean)
object MeterOcr {
    suspend fun documentFromUri(context: Context,uri: Uri): DocumentScan {
        val pdf=withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri).use { stream -> val header=ByteArray(5);stream?.read(header)==5 && String(header,Charsets.US_ASCII)=="%PDF-" } }
        if(!pdf) { val bitmap=load(context,uri);return DocumentScan(bitmap,document(bitmap),false) }
        val descriptor=withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openFileDescriptor(uri,"r")) }
        val renderer=withContext(Dispatchers.IO) { try { android.graphics.pdf.PdfRenderer(descriptor) } catch(e:Exception) { descriptor.close();throw e } }
        var first: Bitmap?=null
        val text=StringBuilder()
        try {
            for(index in 0 until minOf(renderer.pageCount,5)) {
                val bitmap=withContext(Dispatchers.IO) {
                    renderer.openPage(index).use { page ->
                        val scale=2400f/maxOf(page.width,page.height)
                        Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(android.graphics.Color.WHITE);page.render(b,null,Matrix().apply { setScale(scale,scale) },android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                    }
                }
                if(index==0)first=bitmap
                try { text.append(document(bitmap)).append('\n') } finally { if(index>0)bitmap.recycle() }
            }
            return DocumentScan(requireNotNull(first),text.toString(),renderer.pageCount>5)
        } finally { renderer.close();descriptor.close() }
    }
    suspend fun load(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Fotografiu sa nepodarilo otvoriť." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2600) sample *= 2
        val decoded = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: error("Neplatná fotografia.")
        val orientation = resolver.openInputStream(uri).use { stream -> if (stream != null) ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) else ExifInterface.ORIENTATION_NORMAL }
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }
    private suspend fun raw(bitmap: Bitmap, client: com.google.mlkit.vision.text.TextRecognizer): com.google.mlkit.vision.text.Text = suspendCoroutine { continuation ->
        client.process(InputImage.fromBitmap(bitmap,0)).addOnSuccessListener { continuation.resume(it) }.addOnFailureListener { continuation.resumeWithException(it) }
    }
    suspend fun document(bitmap: Bitmap): String {
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val original=raw(bitmap,client).text
            val adjusted=withContext(Dispatchers.Default) { ImageAnalysis.adjusted(bitmap,false) }
            return try { original+"\n"+raw(adjusted,client).text } finally { adjusted.recycle() }
        } finally { client.close() }
    }
    suspend fun recognize(bitmap: Bitmap, energy: Energy = Energy.ELECTRICITY): Scan {
        val client=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val found=mutableListOf<Candidate>()
        val regionHints=mutableListOf<Rect>()
        var fullUnit: String? = null
        suspend fun pass(image: Bitmap, region: Rect, label: String) {
            val lines=raw(image,client).textBlocks.flatMap { it.lines }
            if(region.width()==bitmap.width && region.height()==bitmap.height) {
                val units=lines.mapNotNull { ReadingRules.inspect(it.text,energy).unit }.distinct()
                if(units.size==1)fullUnit=units.single()
            }
            for(line in lines) {
                val box=line.boundingBox ?: continue
                val nearby=lines.filter { other -> other !== line && other.boundingBox?.let { kotlin.math.abs(it.centerY()-box.centerY()) < box.height()*1.6 && kotlin.math.abs(it.centerX()-box.centerX())<box.width()+box.height()*5 } == true }.joinToString(" ") { it.text }
                val evidence=line.text+" "+nearby
                val inspection=ReadingRules.inspect(evidence,energy)
                val global=Rect(region.left+box.left*region.width()/image.width,region.top+box.top*region.height()/image.height,region.left+box.right*region.width()/image.width,region.top+box.bottom*region.height()/image.height)
                val values=ReadingRules.numericValues(line.text)
                for(value in values) found+=Candidate(value,global,inspection.warnings,inspection.blocked,"$label · ${line.text}")
                if(values.isNotEmpty())regionHints+=global
            }
        }
        try {
            val all=Rect(0,0,bitmap.width,bitmap.height)
            pass(bitmap,all,"Pôvodná snímka")
            val contrasted=withContext(Dispatchers.Default) { ImageAnalysis.adjusted(bitmap,false) }
            try { pass(contrasted,all,"Zvýšený kontrast") } finally { contrasted.recycle() }
            val detected=withContext(Dispatchers.Default) { ImageAnalysis.displayRegions(bitmap) }
            val regions=(regionHints.sortedByDescending { it.height()*it.width() }.take(3)+detected).distinctBy { listOf(it.centerX()/50,it.centerY()/50,it.width()/70) }.take(6)
            for(r in regions) {
                val margin=(r.height()/4).coerceAtLeast(3)
                val expanded=Rect((r.left-margin).coerceAtLeast(0),(r.top-margin).coerceAtLeast(0),(r.right+margin).coerceAtMost(bitmap.width),(r.bottom+margin).coerceAtMost(bitmap.height))
                if(expanded.width()<10 || expanded.height()<10)continue
                val cropped=Bitmap.createBitmap(bitmap,expanded.left,expanded.top,expanded.width(),expanded.height())
                try {
                    pass(cropped,expanded,"Automatický displej")
                    val binary=withContext(Dispatchers.Default) { ImageAnalysis.adjusted(cropped,true) }
                    try { pass(binary,expanded,"Čiernobiely displej") } finally { binary.recycle() }
                    val segmentValue=withContext(Dispatchers.Default) { ImageAnalysis.sevenSegment(cropped) }
                    if(segmentValue!=null) found+=Candidate(segmentValue,expanded,listOf("Návrh zo segmentov. Overte desatinnú čiarku aj všetky číslice."),evidence="Sedemsegmentový displej")
                } finally { if(cropped !== bitmap)cropped.recycle() }
            }
            val grouped=found.groupBy { decimal(it.text)?.stripTrailingZeros()?.toPlainString() ?: it.text }.values.map { alternatives ->
                val representative=alternatives.maxBy { it.rect.height()*it.text.count(Char::isDigit) }
                val blocked=alternatives.any { it.blocked } || (fullUnit!=null && fullUnit!=energy.unit)
                representative.copy(blocked=blocked,passes=alternatives.map { it.evidence.substringBefore(" ·") }.distinct().size,warnings=(alternatives.flatMap { it.warnings }+if(fullUnit!=null && fullUnit!=energy.unit) listOf("Jednotka $fullUnit nezodpovedá ${energy.unit}.") else emptyList()).distinct())
            }.sortedWith(compareBy<Candidate> { it.blocked }.thenByDescending { it.passes }.thenByDescending { it.rect.height()*it.text.count(Char::isDigit) }).take(8)
            val quality=withContext(Dispatchers.Default) { ImageAnalysis.quality(ImageAnalysis.gray(bitmap,640)) }
            val warnings=quality.warnings.toMutableList()
            if(grouped.map { it.text }.distinct().size>1)warnings+="Na snímke sú rôzne čísla. Porovnajte návrh s displejom."
            return Scan(bitmap,grouped,warnings)
        } finally { client.close() }
    }
    fun crop(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        val x = (left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = (top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val w = ((right * bitmap.width).toInt().coerceIn(x + 1, bitmap.width) - x)
        val h = ((bottom * bitmap.height).toInt().coerceIn(y + 1, bitmap.height) - y)
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }
}
