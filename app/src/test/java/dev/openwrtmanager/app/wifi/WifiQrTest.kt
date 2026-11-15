package dev.openwrtmanager.app.wifi

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiQrTest {
    @Test
    fun wpa_network() {
        assertEquals("WIFI:T:WPA;S:Home;P:hunter22;;", wifiQrPayload("Home", Security.WPA2, "hunter22", hidden = false))
        assertEquals("WIFI:T:WPA;S:Home;P:hunter22;;", wifiQrPayload("Home", Security.WPA2_WPA3, "hunter22", hidden = false))
    }

    @Test
    fun open_network_has_no_password_field() {
        assertEquals("WIFI:T:nopass;S:Cafe;;", wifiQrPayload("Cafe", Security.OPEN, null, hidden = false))
        assertEquals("WIFI:T:nopass;S:Cafe;;", wifiQrPayload("Cafe", Security.OPEN, "ignored", hidden = false))
    }

    @Test
    fun wpa3_only_network_uses_sae() {
        assertEquals("WIFI:T:SAE;S:Home;P:hunter22;;", wifiQrPayload("Home", Security.WPA3, "hunter22", hidden = false))
    }

    @Test
    fun hidden_network_is_flagged() {
        assertEquals("WIFI:T:WPA;S:Home;P:hunter22;H:true;;", wifiQrPayload("Home", Security.WPA2, "hunter22", hidden = true))
    }

    @Test
    fun special_characters_are_escaped() {
        val tricky = "a;b:c,d\\e\"f"
        val escaped = "a\\;b\\:c\\,d\\\\e\\\"f"
        assertEquals("WIFI:T:WPA;S:$escaped;P:$escaped;;", wifiQrPayload(tricky, Security.WPA2, tricky, hidden = false))
    }

    @Test
    fun emoji_and_spaces_pass_through() {
        assertEquals("WIFI:T:WPA;S:Joe's Café 📶;P:pass word;;", wifiQrPayload("Joe's Café 📶", Security.WPA2, "pass word", hidden = false))
    }

    @Test
    fun matrix_decodes_back_to_the_payload() {
        val payload = wifiQrPayload("Joe's \"Café\" 📶", Security.WPA2_WPA3, "it's; a:pass,word", hidden = true)
        val matrix = qrMatrix(payload)
        assertTrue(matrix.size > 20)
        assertEquals(matrix.size * matrix.size, matrix.dark.size)

        val scale = 6
        val side = matrix.size * scale
        val pixels = IntArray(side * side) { index ->
            val x = (index % side) / scale
            val y = (index / side) / scale
            if (matrix.dark[y * matrix.size + x]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels))))
        assertEquals(payload, decoded.text)
    }

    @Test
    fun matrix_has_a_quiet_border() {
        val matrix = qrMatrix("WIFI:T:nopass;S:x;;")
        val edge = (0 until matrix.size).flatMap { i ->
            listOf(matrix.dark[i], matrix.dark[(matrix.size - 1) * matrix.size + i], matrix.dark[i * matrix.size], matrix.dark[i * matrix.size + matrix.size - 1])
        }
        assertTrue(edge.none { it })
    }
}
