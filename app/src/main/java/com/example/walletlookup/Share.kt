package com.example.walletlookup

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** True while a share card is being made, so a second tap doesn't start another one. */
@Volatile private var preparingShare = false

/** For a share started from something that isn't a screen (normally the screen's own scope is used). */
private val shareScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

/**
 * Shares a wallet: the address, a branded QR card image and a link to see it on a block explorer.
 * Balances and tokens are deliberately left out. Call from the main thread; the card is drawn and saved in the
 * background, and the share sheet opens once it's ready.
 */
fun shareWallet(context: Context, name: String?, address: String) {
    if (preparingShare) return
    val raw = rawAddress(address)
    val network = labelOf(address)
    val link = explorerUrl(address)
    val text = buildString {
        if (!name.isNullOrBlank()) appendLine(name)
        appendLine("Network: $network")
        appendLine(raw)
        if (link != null) {
            appendLine()
            appendLine("View on the blockchain: $link")
        }
        appendLine()
        append("Shared with Batwallet")
    }
    val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, name ?: "Wallet address")
    preparingShare = true
    val scope = (context as? LifecycleOwner)?.lifecycleScope ?: shareScope
    scope.launch {
        // A 1080×1400 image drawn and compressed: too slow for the main thread (the screen froze for a moment).
        val image = withContext(Dispatchers.Default) { runCatching { qrCard(context, name, network, raw) }.getOrNull() }
        if (image != null) {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", image)
            send.setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri("QR", uri)
        } else {
            send.setType("text/plain")
        }
        context.startActivity(Intent.createChooser(send, "Share wallet"))
    }.invokeOnCompletion { preparingShare = false } // also when the screen closed first
}

/**
 * Clean share card: wallet name and a network badge on top, a crisp QR with the Batwallet bat in the middle
 * (high error correction keeps it scannable), the address split evenly over two lines, and a small footer.
 */
private fun qrCard(context: Context, name: String?, network: String, raw: String): File {
    val w = 1080
    val h = 1400
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val semi = ResourcesCompat.getFont(context, R.font.inter_600) ?: Typeface.DEFAULT_BOLD
    val regular = ResourcesCompat.getFont(context, R.font.inter_400) ?: Typeface.DEFAULT
    val ink = Color.rgb(17, 17, 17)
    val grey = Color.rgb(120, 120, 120)

    // Soft background with a white rounded card.
    c.drawColor(Color.rgb(242, 242, 244))
    val card = RectF(60f, 60f, w - 60f, h - 60f)
    c.drawRoundRect(card, 56f, 56f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })

    // Title and network badge.
    var y = 190f
    val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 58f; typeface = semi; textAlign = Paint.Align.CENTER }
    c.drawText((name?.takeIf { it.isNotBlank() } ?: "Wallet address").take(24), w / 2f, y, title)
    y += 40f
    val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 34f; typeface = semi; textAlign = Paint.Align.CENTER }
    val bw = badgeText.measureText(network) + 56f + 52f
    val badge = RectF(w / 2f - bw / 2, y, w / 2f + bw / 2, y + 62f)
    c.drawRoundRect(badge, 31f, 31f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(236, 236, 238) })
    drawCoinLogo(context, c, networkSymbol(network), badge.left + 40f, y + 31f, 20f)
    c.drawText(network, w / 2f + 26f, y + 43f, badgeText)
    y += 110f

    // QR with the logo in the middle.
    val qr = brandedQr(raw, 660)
    val qrSize = qr.width // exact multiple of the module size, so every square is the same
    val qx = (w - qrSize) / 2f
    c.drawBitmap(qr, qx, y, null)
    val tile = qrSize * 0.2f
    val cx = w / 2f
    val cy = y + qrSize / 2f
    c.drawRoundRect(RectF(cx - tile / 2 - 10, cy - tile / 2 - 10, cx + tile / 2 + 10, cy + tile / 2 + 10), 34f, 34f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    c.drawRoundRect(RectF(cx - tile / 2, cy - tile / 2, cx + tile / 2, cy + tile / 2), 28f, 28f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
    ContextCompat.getDrawable(context, R.drawable.ic_bat)?.let { d ->
        val pad = tile * 0.22f // the vector has margins; scale it up so the bat fills the tile
        d.setBounds((cx - tile / 2 - pad).toInt(), (cy - tile / 2 - pad).toInt(), (cx + tile / 2 + pad).toInt(), (cy + tile / 2 + pad).toInt())
        d.draw(c)
    }
    y += qrSize + 70f

    // Address on two balanced lines.
    val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 38f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    val lines = if (raw.length > 24) listOf(raw.substring(0, (raw.length + 1) / 2), raw.substring((raw.length + 1) / 2)) else listOf(raw)
    lines.forEach { line ->
        c.drawText(line, w / 2f, y, mono)
        y += 52f
    }

    // Footer.
    val foot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = grey; textSize = 30f; typeface = regular; textAlign = Paint.Align.CENTER }
    c.drawText("Scan to send", w / 2f, h - 150f, foot)
    val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 34f; typeface = semi; textAlign = Paint.Align.CENTER }
    c.drawText("Batwallet", w / 2f, h - 105f, brand)

    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(dir, "wallet-qr.png")
    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}

/** Square QR with high error correction, so a logo can sit in the middle and it still scans. */
private fun brandedQr(text: String, size: Int): Bitmap =
    crispQr(text, size, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H, quiet = 1)
/** Exports loaded transfers as a CSV file (opens the share sheet: save to Drive, email, etc.). */
fun shareHistoryCsv(context: Context, name: String?, address: String, history: List<History>) {
    fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
    val csv = buildString {
        appendLine("network,date,direction,amount,coin,from,to,transaction")
        history.forEach { h ->
            h.items.forEach { t ->
                appendLine(listOf(
                    q(h.network), q(if (t.time > 0) date(t.time / 1000) else ""), q(if (t.incoming) "in" else "out"),
                    t.amount.toPlainString(), q(t.symbol), q(t.from), q(t.to), q(t.hash),
                ).joinToString(","))
            }
        }
    }
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val safeName = (name ?: rawAddress(address).take(10)).replace(Regex("[^A-Za-z0-9_-]"), "_")
    val file = File(dir, "history-$safeName.csv").apply { writeText(csv) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType("text/csv")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "Transaction history · ${name ?: rawAddress(address)}")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri("CSV", uri)
    context.startActivity(Intent.createChooser(send, "Export history"))
}