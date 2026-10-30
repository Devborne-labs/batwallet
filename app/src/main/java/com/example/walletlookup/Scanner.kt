package com.example.walletlookup

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeView
import kotlinx.coroutines.launch
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/** Full-screen, portrait QR scanner with a plain rounded frame. "Import photo" reads a QR code from a saved image. */
@Composable
fun ScannerScreen(onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var reading by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun read(uri: android.net.Uri?) {
        if (uri == null) return
        reading = true
        scope.launch {
            val qr = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { decodeQrImage(context, uri) }
            reading = false
            if (qr != null) onResult(qr)
            else android.widget.Toast.makeText(context, tr("No QR code found in the photo"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> read(uri) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) onClose()
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val onScan by rememberUpdatedState(onResult)
            // One camera view while the scanner is open, opened and closed only by the lifecycle below. (The view
            // used to open the camera itself as well, and then be closed and reopened once it was stored: the camera
            // started twice.)
            val view = remember {
                BarcodeView(context).apply {
                    decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                    decodeSingle { result -> result.text?.let { onScan(it) } }
                }
            }
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { view })
            DisposableEffect(lifecycle, view) {
                val observer = LifecycleEventObserver { _, e ->
                    when (e) {
                        Lifecycle.Event.ON_RESUME -> view.resume()
                        Lifecycle.Event.ON_PAUSE -> view.pause()
                        else -> {}
                    }
                }
                // Already resumed: adding the observer delivers ON_RESUME right away, which opens the camera once.
                lifecycle.addObserver(observer)
                onDispose {
                    lifecycle.removeObserver(observer)
                    view.pause()
                }
            }
            // Dim everything except a rounded square in the middle.
            Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                val side = size.minDimension * 0.68f
                val topLeft = Offset((size.width - side) / 2, (size.height - side) / 2)
                val radius = CornerRadius(28.dp.toPx())
                drawRect(Color.Black.copy(alpha = 0.55f))
                drawRoundRect(Color.Transparent, topLeft, Size(side, side), radius, blendMode = BlendMode.Clear)
                drawRoundRect(Color.White.copy(alpha = 0.9f), topLeft, Size(side, side), radius, style = Stroke(2.dp.toPx()))
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
            Text("Close", color = Color.White)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 36.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (reading) "Reading…" else "Scan a wallet QR code",
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = { pickImage.launch("image/*") }, enabled = !reading) {
                    Text("Import photo", color = Color.White)
                }
            }
        }
    }
}

/** A QR code inside an image file (photo, screenshot), or null. Big images are scaled down first. */
fun decodeQrImage(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    val source = com.google.zxing.RGBLuminanceSource(bmp.width, bmp.height, px)
    val hints = mapOf(
        com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        com.google.zxing.DecodeHintType.TRY_HARDER to true,
    )
    val reader = com.google.zxing.MultiFormatReader().apply { setHints(hints) }
    runCatching { reader.decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))).text }.getOrNull()
        ?: reader.decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.GlobalHistogramBinarizer(source))).text
}.getOrNull()


/** QR code image for an address (used by "Receive"). */
fun qrBitmap(text: String, sizePx: Int = 720): Bitmap = crispQr(text, sizePx)

/**
 * A perfectly square QR: every module is the same whole number of pixels (stretching a QR to an arbitrary size
 * makes some modules wider than others, which looks uneven and scans worse). [quiet] modules of white border.
 */
fun crispQr(
    text: String,
    targetPx: Int,
    level: com.google.zxing.qrcode.decoder.ErrorCorrectionLevel = com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
    quiet: Int = 2,
): Bitmap {
    val hints = mapOf(com.google.zxing.EncodeHintType.ERROR_CORRECTION to level, com.google.zxing.EncodeHintType.MARGIN to 0)
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val modules = m.width + quiet * 2
    val cell = maxOf(1, targetPx / modules)
    val size = cell * modules
    val pixels = IntArray(size * size) { android.graphics.Color.WHITE }
    for (y in 0 until m.height) for (x in 0 until m.width) {
        if (!m.get(x, y)) continue
        val px = (x + quiet) * cell
        val py = (y + quiet) * cell
        for (dy in 0 until cell) {
            val row = (py + dy) * size + px
            java.util.Arrays.fill(pixels, row, row + cell, android.graphics.Color.BLACK)
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val bmp = remember(text) { runCatching { qrBitmap(text) }.getOrNull() }
    if (bmp != null) {
        Image(
            bmp.asImageBitmap(), contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp),
        )
    }
}
