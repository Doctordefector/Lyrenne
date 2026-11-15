package com.lyrenne.desktop.link

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A QR code as a square grid of modules, true for dark. No quiet zone: the caller pads it. */
class QrMatrix(val size: Int, private val bits: BooleanArray) {
    operator fun get(x: Int, y: Int) = bits[y * size + x]
}

object QrCode {
    fun encode(text: String): QrMatrix {
        val hints = mapOf(
            EncodeHintType.MARGIN to 0,
            // M survives a phone camera at an angle; H would make the code denser than it needs to be.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            // No CHARACTER_SET: the URL is ASCII, and a UTF-8 hint adds an ECI header that some
            // phone camera apps do not understand.
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val size = m.width
        return QrMatrix(size, BooleanArray(size * size) { m.get(it % size, it / size) })
    }
}
