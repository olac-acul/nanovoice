package dev.nanovoice

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.nanovoice.agent.AgentImage
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A photo ready to go with the next message: [image] at the normal size, [detail] larger for photos full of writing
 * (see PhotoDetail), its preview for the chat, and the product barcode read in it, if any.
 */
class PreparedPhoto(val image: AgentImage, val preview: ImageBitmap, val barcode: String? = null, val detail: AgentImage = image)

/**
 * Turns a picked or shot photo into what the agent gets: at most [MAX_SIDE] px on its longer side (enough to read text
 * and recognise things, a few hundred KB), upright, and re-encoded as JPEG. Re-encoding writes no EXIF, so the photo's
 * location, time and camera never leave the phone. A product barcode is looked for in a larger copy first, on the
 * phone: for Open Food Facts only that number is needed.
 */
object Photos {
    const val MAX_SIDE = 1024
    /** Barcodes are read, and photos full of writing sent, at this size: thin bars and small letters need pixels. */
    private const val SCAN_SIDE = 2048
    private const val PREVIEW_SIDE = 480
    private const val MAX_BYTES = 1_400_000 // the server takes up to 1.5 MB
    private const val TAG = "Photos"

    /** Null when the file cannot be read as an image. Blocking: call it off the main thread. */
    fun prepare(resolver: ContentResolver, uri: Uri): PreparedPhoto? = try {
        val large = opaque(decode(resolver, uri, SCAN_SIDE))
        val code = Barcodes.read(large)
        val bitmap = scaled(large, MAX_SIDE)
        val image = AgentImage("image/jpeg", jpeg(bitmap))
        // Only worth a second copy when the original is really larger than the normal size.
        val detail = if (max(large.width, large.height) > MAX_SIDE) AgentImage("image/jpeg", jpeg(large)) else image
        // The chat shows a small copy: a 1024 px bitmap per photo would weigh 4 MB for the whole conversation.
        PreparedPhoto(image, scaled(bitmap, PREVIEW_SIDE).asImageBitmap(), code, detail)
    } catch (e: Exception) {
        Log.w(TAG, "cannot read the photo: ${e.javaClass.simpleName}")
        null
    }

    private fun scaled(bitmap: Bitmap, side: Int): Bitmap {
        val scale = minOf(1.0, side.toDouble() / max(bitmap.width, bitmap.height))
        return if (scale >= 1.0) bitmap
        else Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
    }

    private fun decode(resolver: ContentResolver, uri: Uri, side: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the EXIF rotation itself.
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val (w, h) = info.size.width to info.size.height
                val scale = minOf(1.0, side.toDouble() / max(w, h))
                decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // a hardware bitmap cannot be compressed
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        val decoded = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("not an image")
        return scaled(decoded, side)
    }

    /** JPEG has no transparency: a PNG's transparent parts become white instead of black. */
    private fun opaque(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        return out
    }

    private fun jpeg(bitmap: Bitmap): ByteArray {
        var quality = 85
        while (true) {
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            if (bytes.size <= MAX_BYTES || quality <= 40) return bytes
            quality -= 15
        }
    }
}
