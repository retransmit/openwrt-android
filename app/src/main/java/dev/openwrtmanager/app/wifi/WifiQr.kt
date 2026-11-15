package dev.openwrtmanager.app.wifi

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The text a phone camera understands as "join this Wi-Fi network".
 * Format: WIFI:T:<type>;S:<ssid>;P:<password>;H:true;;
 */
fun wifiQrPayload(ssid: String, security: Security, key: String?, hidden: Boolean): String {
    val type = when (security) {
        Security.OPEN -> "nopass"
        Security.WPA3 -> "SAE"
        Security.WPA2, Security.WPA2_WPA3 -> "WPA"
    }
    return buildString {
        append("WIFI:T:").append(type).append(';')
        append("S:").append(escape(ssid)).append(';')
        if (security != Security.OPEN && key != null) append("P:").append(escape(key)).append(';')
        if (hidden) append("H:true;")
        append(';')
    }
}

private fun escape(value: String): String = buildString {
    for (c in value) {
        if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') append('\\')
        append(c)
    }
}

/** A square QR code. [dark] is row by row, [size] modules per side, including the quiet border. */
class QrMatrix(val size: Int, val dark: BooleanArray)

fun qrMatrix(payload: String): QrMatrix {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 2,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    // Asking for size 0 returns the code at one cell per module, which is what we want to draw ourselves.
    val bits = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
    val size = bits.width
    return QrMatrix(size, BooleanArray(size * size) { bits.get(it % size, it / size) })
}
