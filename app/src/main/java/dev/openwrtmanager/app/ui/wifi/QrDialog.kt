package dev.openwrtmanager.app.ui.wifi

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.openwrtmanager.app.wifi.QrMatrix
import dev.openwrtmanager.app.wifi.qrMatrix
import java.io.File

/**
 * Shows a join code for a Wi-Fi network. The password itself is never displayed as text.
 * The screen is set to full brightness while the dialog is open so cameras can read it.
 */
@Composable
fun QrDialog(ssid: String, payload: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val matrix = remember(payload) { qrMatrix(payload) }

    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val previous = window?.attributes?.screenBrightness
        window?.let { w ->
            w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        }
        onDispose {
            if (window != null && previous != null) {
                window.attributes = window.attributes.apply { screenBrightness = previous }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ssid, maxLines = 2) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Always dark on white, in both themes: that is what scanners expect.
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(Color.White, RoundedCornerShape(16.dp))
                        .padding(8.dp)
                        .semantics { contentDescription = "QR code to join $ssid" },
                ) {
                    val cell = size.minDimension / matrix.size
                    for (y in 0 until matrix.size) {
                        for (x in 0 until matrix.size) {
                            if (matrix.dark[y * matrix.size + x]) {
                                // Slight overdraw avoids hairline gaps between neighbouring cells.
                                drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                            }
                        }
                    }
                }
                Text(
                    "Point a phone camera at this code to join the network.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { shareQr(context, ssid, matrix) }) { Text("Share") } },
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private const val SHARE_PIXELS_PER_MODULE = 16

private fun shareQr(context: Context, ssid: String, matrix: QrMatrix) {
    val side = matrix.size * SHARE_PIXELS_PER_MODULE
    val pixels = IntArray(side * side) { index ->
        val x = (index % side) / SHARE_PIXELS_PER_MODULE
        val y = (index / side) / SHARE_PIXELS_PER_MODULE
        if (matrix.dark[y * matrix.size + x]) AndroidColor.BLACK else AndroidColor.WHITE
    }
    val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    // The image contains the password in scannable form, so it lives only in the app's private cache.
    val dir = File(context.cacheDir, "qr").apply { mkdirs() }
    val file = File(dir, "wifi-qr.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Wi-Fi: $ssid")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share Wi-Fi code"))
}
