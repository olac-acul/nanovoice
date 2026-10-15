package dev.nanovoice

import dev.nanovoice.agent.PairingLink
import dev.nanovoice.agent.PairingLinkResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The QR code the panel draws (made with its `qrcode` library, rows of 0 and 1) must be read by the scanner the app uses
 * (ZXing). The fixture is the panel's own output for a made-up code; the first line is the text it encodes.
 */
class PairingQrTest {
    private val fixture = checkNotNull(javaClass.getResourceAsStream("/pairing-qr-from-panel.txt")).bufferedReader().readLines()
    private val text = fixture.first()
    private val rows = fixture.drop(1)

    /** A camera-like brightness plane: the matrix with a quiet zone, each module [scale] pixels wide. */
    private fun frame(scale: Int, quiet: Int = 4): Triple<ByteArray, Int, Int> {
        val n = rows.size
        val side = (n + 2 * quiet) * scale
        val luma = ByteArray(side * side) { 255.toByte() }
        for (y in 0 until n) for (x in 0 until n) if (rows[y][x] == '1') {
            for (dy in 0 until scale) for (dx in 0 until scale) luma[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = 0
        }
        return Triple(luma, side, side)
    }

    @Test fun theScannerReadsWhatThePanelDraws() {
        for (scale in listOf(3, 6, 10)) {
            val (luma, w, h) = frame(scale)
            assertEquals(text, Barcodes.readQr(luma, w, w, h), "scale $scale")
        }
    }

    @Test fun andTheAppAcceptsTheText() {
        val link = assertIs<PairingLinkResult.Valid>(PairingLink.parse(text))
        assertEquals("ws://100.101.102.109:8767", link.server)
        assertEquals("7H2KQ9M4XB5TD3WE", link.code)
    }

    @Test fun anEmptyFrameReadsNothing() {
        val side = 200
        assertNull(Barcodes.readQr(ByteArray(side * side) { 255.toByte() }, side, side, side))
    }
}
