package dev.nanovoice

import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.oned.UPCEReader

/** Product barcodes (EAN/UPC) in a photo or a camera frame, read on the phone with ZXing: nothing leaves it. */
object Barcodes {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E),
        DecodeHintType.TRY_HARDER to true,
    )

    /** The code's digits, or null when there is no readable product barcode (also turned sideways). */
    fun read(bitmap: Bitmap): String? {
        for (candidate in sequenceOf(bitmap, rotated(bitmap))) {
            val pixels = IntArray(candidate.width * candidate.height)
            candidate.getPixels(pixels, 0, candidate.width, 0, 0, candidate.width, candidate.height)
            decode(RGBLuminanceSource(candidate.width, candidate.height, pixels))?.let { return it }
        }
        return null
    }

    /**
     * A camera frame's brightness plane ([rowStride] bytes per row). [sideways] reads it turned by 90 degrees: the
     * sensor is landscape, so a barcode held level in a portrait phone has its bars across the frame's rows.
     */
    fun read(luma: ByteArray, rowStride: Int, width: Int, height: Int, sideways: Boolean): String? {
        if (!sideways) return decode(PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false))
        val turned = ByteArray(width * height)
        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width) turned[x * height + (height - 1 - y)] = luma[row + x]
        }
        return decode(PlanarYUVLuminanceSource(turned, height, width, 0, 0, height, width, false))
    }

    private val qrHints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /** The text of a QR code in a camera frame (a QR code reads at any angle, so there is no sideways attempt). */
    fun readQr(luma: ByteArray, rowStride: Int, width: Int, height: Int): String? = try {
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false))), qrHints).text
    } catch (_: ReaderException) {
        null
    }

    /**
     * The code as Open Food Facts files it, 13 digits for the UPC family: UPC-A is EAN-13 with a leading 0, and UPC-E
     * (8 digits, like EAN-8, so only the format tells them apart) is a UPC-A with its zeros left out.
     */
    internal fun normalised(code: String, format: BarcodeFormat? = null): String {
        val upcA = if (format == BarcodeFormat.UPC_E && code.length == 8) UPCEReader.convertUPCEtoUPCA(code) else code
        return if (upcA.length == 12) "0$upcA" else upcA
    }

    private fun decode(source: LuminanceSource): String? = try {
        val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints)
        result.text?.takeIf { code -> code.all { it.isDigit() } }?.let { normalised(it, result.barcodeFormat) }
    } catch (_: ReaderException) {
        null
    }

    private fun rotated(bitmap: Bitmap): Bitmap =
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f) }, false)
}
