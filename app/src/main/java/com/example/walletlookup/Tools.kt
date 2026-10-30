package com.example.walletlookup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Several addresses found (in a message, a photo or a shared text): pick one.
 * [fromPhoto] adds a reminder to compare with the original, since a camera can misread a character.
 */
@Composable
fun AddressChooser(
    found: List<String>,
    fromPhoto: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Set when the text could also be a transaction ID (0x + 64 characters is both an Aptos address and an EVM hash). */
    txAlternative: String? = null,
    onCheckTx: (String) -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (found.size == 1) "Address found" else "Addresses found") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (fromPhoto) {
                    Text("Read from a photo: check it matches before you use it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                found.forEach { a ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(a) }.padding(vertical = 10.dp, horizontal = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NetworkIcon(labelOf(a), 18.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(labelOf(a), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(rawAddress(a), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
        },
        confirmButton = {
            txAlternative?.let { h -> TextButton(onClick = { onCheckTx(h) }) { Text("Check as transaction ID") } }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Paste a transaction ID; the app finds its network and shows what moved and whether it went through. */
@Composable
fun TxCheckDialog(initial: String = "", onFound: (TxInfo) -> Unit, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var hash by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun check() {
        busy = true; error = null
        scope.launch {
            val r = runCatching { lookupTx(hash) }.getOrNull()
            busy = false
            if (r != null) onFound(r) else error = tr("Not found on the supported networks.")
        }
    }
    // Opened with an ID already filled in (shared, pasted or "Check as transaction ID"): search straight away.
    LaunchedEffect(Unit) { if (looksLikeTxHash(initial)) check() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hash, onValueChange = { hash = it.trim(); error = null }, singleLine = true,
                    label = { Text("Transaction ID") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                TextButton(onClick = { clipboard.getText()?.text?.trim()?.let { hash = it; error = null } }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("Paste")
                }
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Searching…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { check() }, enabled = looksLikeTxHash(hash) && !busy) { Text("Check") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Before sending: paste the address you're about to pay and get a quick verdict — who it is (your wallet or contact,
 * an exchange), whether it imitates one of yours, whether it was ever used, and the scam checks.
 */
@Composable
fun SendCheckDialog(onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf(clipboard.getText()?.text?.trim().orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<Pair<Level, String>>?>(null) }
    // Part of the check couldn't run (each such line says why): offer Retry.
    var incomplete by remember { mutableStateOf(false) }
    fun check() {
        val a = cleanAddress(input)
        val type = detect(a)
        incomplete = false
        if (type == null) { lines = listOf(Level.BAD to tr("Not a supported address")); return }
        busy = true
        lines = null
        scope.launch {
            val out = mutableListOf<Pair<Level, String>>()
            val raw = rawAddress(a)
            lookalikeSource.firstOrNull { rawAddress(it.address).equals(raw, true) }?.let { w ->
                out += Level.GOOD to (tr(if (w.contact) "Your contact" else "Your wallet") + ": " + walletLabel(w))
            }
            lookalikeOf(a)?.let { twin ->
                out += Level.BAD to (tr("Lookalike of") + " " + walletLabel(twin) + " · " + tr("don't send"))
            }
            knownPlatform(a)?.let { out += Level.INFO to (tr("Exchange wallet") + ": " + it) }
            var lookupError: String? = null
            val results = try {
                lookup(a, type, withPrices = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lookupError = failureReason(e)
                emptyList()
            }
            val ok = results.filter { it.error == null }
            val unanswered = results.filter { it.error != null }.map { it.network }
            when {
                results.isEmpty() || ok.isEmpty() -> {
                    out += Level.INFO to (tr("Couldn't fully check") + " · " + tr(lookupError ?: "No network answered."))
                    incomplete = true
                }
                ok.none { it.active } -> out += Level.WARN to tr("Not activated yet") + " · " + tr("never received or sent anything")
                else -> out += Level.GOOD to tr("Used on") + " " + ok.filter { it.active }.joinToString(", ") { it.network }
            }
            if (ok.isNotEmpty() && unanswered.isNotEmpty()) {
                out += Level.INFO to (tr("Some networks didn't answer") + ": " + unanswered.joinToString(", "))
                incomplete = true
            }
            try {
                val used = ok.filter { it.active }.map { it.network }.toSet().ifEmpty { null }
                safety(a, history(a, used, limit = 10)).checks.forEach { c ->
                    // A check that couldn't run says why.
                    out += c.level to (tr(c.title) + if (c.failed) " · " + tr(c.detail) else "")
                    if (c.failed) incomplete = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                out += Level.INFO to (tr("Safety check didn't run") + " · " + tr(failureReason(e)))
                incomplete = true
            }
            lines = out.distinctBy { it.second }
            busy = false
        }
    }
    LaunchedEffect(Unit) { if (detect(cleanAddress(input)) != null) check() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check before sending") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it.trim(); lines = null }, singleLine = true,
                    label = { Text("Address you'll send to") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                TextButton(onClick = { clipboard.getText()?.text?.trim()?.let { input = it; lines = null; check() } },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Paste") }
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Checking…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                lines?.forEach { (level, text) ->
                    val color = when (level) {
                        Level.BAD -> MaterialTheme.colorScheme.error
                        Level.WARN -> androidx.compose.ui.graphics.Color(0xFFE0A100)
                        Level.GOOD -> InGreen
                        Level.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row {
                        Text(when (level) { Level.GOOD -> "✓"; Level.INFO -> "i"; Level.WARN -> "!"; Level.BAD -> "✕" },
                            color = color, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.width(22.dp))
                        androidx.compose.material3.Text(text, style = MaterialTheme.typography.bodyMedium,
                            color = if (level == Level.BAD) color else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        confirmButton = {
            if (lines != null && detect(cleanAddress(input)) != null) Row {
                if (incomplete) TextButton(onClick = { check() }, enabled = !busy) { Text("Retry") }
                TextButton(onClick = { onOpen(cleanAddress(input)) }) { Text("Open") }
            }
            else TextButton(onClick = { check() }, enabled = !busy && input.isNotBlank()) { Text("Check") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Two addresses side by side; every character that differs is marked, so a swapped or poisoned address stands out. */
@Composable
fun CompareDialog(onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val x = rawAddress(cleanAddress(a))
    val y = rawAddress(cleanAddress(b))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compare addresses") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(true, false).forEach { first ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = if (first) a else b, onValueChange = { if (first) a = it else b = it }, singleLine = true,
                            label = { Text(if (first) "First" else "Second") }, modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                        TextButton(onClick = { clipboard.getText()?.text?.let { if (first) a = it else b = it } }) { Text("Paste") }
                    }
                }
                if (x.isNotEmpty() && y.isNotEmpty()) {
                    val same = x.equals(y, ignoreCase = x.startsWith("0x"))
                    val diff = (0 until maxOf(x.length, y.length)).count { i ->
                        !x.getOrNull(i).toString().equals(y.getOrNull(i).toString(), ignoreCase = x.startsWith("0x"))
                    }
                    Text(
                        if (same) "✓ " + tr("Same address") else "✗ " + tr("Different") + " · $diff " + tr(if (diff == 1) "character" else "characters"),
                        fontWeight = FontWeight.SemiBold, color = if (same) InGreen else OutRed,
                    )
                    if (!same) {
                        listOf(x to y, y to x).forEach { (s, other) ->
                            Text(
                                buildAnnotatedString {
                                    s.forEachIndexed { i, ch ->
                                        val bad = !ch.toString().equals(other.getOrNull(i).toString(), ignoreCase = s.startsWith("0x"))
                                        if (bad) withStyle(SpanStyle(color = OutRed, fontWeight = FontWeight.Bold)) { append(ch) } else append(ch)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
