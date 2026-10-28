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
