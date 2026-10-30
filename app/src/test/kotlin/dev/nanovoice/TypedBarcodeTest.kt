package dev.nanovoice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TypedBarcodeTest {
    @Test fun theDigitsAsPrintedWithSpacesOrDashes() {
        assertEquals("8001234567890", typedBarcode("8 001234 567890"))
        assertEquals("9788804668237", typedBarcode("978-88-04-66823-7"))
        assertEquals("96385074", typedBarcode("9638 5074"))
    }

    @Test fun aTwelveDigitUpcBecomesTheThirteenTheDatabasesUse() {
        assertEquals("0182100007377", typedBarcode("182100007377"))
    }

    @Test fun notAProductCode() {
        assertNull(typedBarcode(""))
        assertNull(typedBarcode("1234567"))
        assertNull(typedBarcode("123456789012345"))
        assertNull(typedBarcode("80012345A7890"))
    }
}
