package dev.nanovoice

import com.google.zxing.BarcodeFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class BarcodesTest {
    @Test
    fun `UPC-A gets the leading zero of EAN-13`() {
        assertEquals("0012345678905", Barcodes.normalised("012345678905", BarcodeFormat.UPC_A))
    }

    @Test
    fun `UPC-E is expanded to the UPC-A it abbreviates`() {
        assertEquals("0012345000065", Barcodes.normalised("01234565", BarcodeFormat.UPC_E))
    }

    @Test
    fun `EAN-8 has eight digits too and stays as it is`() {
        assertEquals("96385074", Barcodes.normalised("96385074", BarcodeFormat.EAN_8))
        assertEquals("8001234567890", Barcodes.normalised("8001234567890", BarcodeFormat.EAN_13))
    }
}
