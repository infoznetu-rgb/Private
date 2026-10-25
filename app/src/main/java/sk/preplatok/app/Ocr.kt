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

data class Candidate(val text: String, val rect: Rect)
data class Scan(val bitmap: Bitmap, val candidates: List<Candidate>)
object MeterOcr {
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
    suspend fun recognize(bitmap: Bitmap): Scan = suspendCoroutine { continuation ->
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        client.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { text ->
            val candidates = text.textBlocks.flatMap { it.lines }.flatMap { line ->
                val box = line.boundingBox ?: return@flatMap emptyList<Candidate>()
                OcrNumbers.candidates(line.text).map { Candidate(it, Rect(box)) }
            }.distinctBy { Pair(it.text, it.rect) }.sortedByDescending { it.rect.height() * it.text.count(Char::isDigit) }.take(12)
            client.close(); continuation.resume(Scan(bitmap, candidates))
        }.addOnFailureListener { client.close(); continuation.resumeWithException(it) }
    }
    fun crop(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        val x = (left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = (top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val w = ((right * bitmap.width).toInt().coerceIn(x + 1, bitmap.width) - x)
        val h = ((bottom * bitmap.height).toInt().coerceIn(y + 1, bitmap.height) - y)
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }
}
