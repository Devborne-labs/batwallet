package com.example.walletlookup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal

private fun short(a: String) = if (a.length > 16) a.take(8) + "…" + a.takeLast(6) else a

/** Stablecoins with 2 decimals, coins with a few more. */
private fun amountText(v: BigDecimal, symbol: String) = if (symbol.startsWith("USD")) fmt2(v) else fmt(v)

// ===================== Safety (compact) =====================

/**
 * One compact card: the verdict for sending money here, which platform the address belongs to, and the
 * individual checks as short ticks. Turns red with a clear warning when the address is flagged or a lookalike.
 */
@Composable
fun SafetyCard(
    safety: Safety?,
    loading: Boolean,
    twin: SavedAddress?,
    savedWallet: SavedAddress?,
    platform: Platform?,
) {
    var open by remember { mutableStateOf(false) }
    val badCheck = safety?.checks?.firstOrNull { it.level == Level.BAD }
    val danger = twin != null || badCheck != null
    val verdict = when {
        twin != null -> "Lookalike of your saved \"${twin.name}\""
        badCheck != null -> badCheck.title
        savedWallet?.contact == true -> "Your contact · ${savedWallet.name}"
        savedWallet != null -> "Your own wallet"
        safety == null && loading -> "Checking…"
        safety == null -> "Couldn't check right now"
        safety.level == Level.WARN -> "New or unused address"
        else -> "No red flags found"
    }
    val red = MaterialTheme.colorScheme.error
    Card(
        Modifier.fillMaxWidth().clickable { open = !open },
        colors = if (danger) CardDefaults.cardColors(containerColor = red.copy(alpha = 0.16f)) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loading && safety == null) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    (if (danger) "⚠  " else "") + verdict,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (danger) red else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (safety != null) {
                    Text(if (open) "Less" else "Details", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (danger) {
                Text(
                    if (twin != null) "Different address made to look like yours. Don't send money to it."
                    else "Reported as a scam or compromised. Don't send money to this address.",
                    style = MaterialTheme.typography.bodySmall,
                    color = red,
                )
            }
            platform?.let {
                Text("Platform: ${it.how}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            safety?.let { s ->
                if (!open) {
                    // Short ticks on one line.
                    Text(
                        s.checks.joinToString("   ") { "${icon(it.level)} ${it.title}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    s.checks.forEach { c ->
                        Column(Modifier.padding(top = 6.dp)) {
                            Text("${icon(c.level)} ${c.title}", style = MaterialTheme.typography.labelLarge,
                                color = if (c.level == Level.BAD) red else MaterialTheme.colorScheme.onSurface)
                            Text(c.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("A clean result isn't a guarantee. Only send to people you trust.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

private fun icon(level: Level) = when (level) {
    Level.GOOD -> "✓"
    Level.INFO -> "i"
    Level.WARN -> "!"
    Level.BAD -> "✕"
}

// ===================== History =====================

/**
 * Recent transfers of one address, newest first, per network, with totals in and out.
 * Tap a transfer for its details; tap the other address there to follow the money.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    address: String,
    name: String?,
    preloaded: List<History>?,
    networks: Set<String>?,
    names: Map<String, String>,
    seen: List<Seen>,
    onClose: () -> Unit,
    onOpenAddress: (address: String, network: String) -> Unit,
    onSaveContact: (address: String, network: String, name: String) -> Unit,
    /** Last known balances of this address per network (shown at the top of each network's list). */
    balances: List<ChainResult> = emptyList(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Shown at once: what Home just loaded, or the last history saved for this address. A fresh copy loads behind it.
    var data by remember { mutableStateOf(preloaded ?: HistoryCache.load(address)) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(if (preloaded == null) 1 else 0) }
    var limit by remember { mutableIntStateOf(25) }
    var selected by remember { mutableStateOf<Pair<String, Transfer>?>(null) }

    LaunchedEffect(reload) {
        if (reload == 0) { preloaded?.let { HistoryCache.save(address, it) }; return@LaunchedEffect }
        loading = true
        try {
            val nets = networks
            if (nets != null && nets.size > 1 && netOf(address) == null) {
                // Several networks: each one appears as soon as it has loaded, instead of all waiting for the slowest.
                val order = nets.toList()
                val fresh = java.util.concurrent.ConcurrentHashMap<String, History>()
                kotlinx.coroutines.coroutineScope {
                    order.forEach { n ->
                        launch {
                            val h = runCatching { history(address, setOf(n), limit) }.getOrNull().orEmpty()
                            h.forEach { fresh[it.network] = it }
                            // Fresh networks replace their old copy; the rest stay until theirs arrive.
                            data = (data.orEmpty().filter { it.network !in fresh.keys } + fresh.values)
                                .sortedBy { order.indexOf(it.network).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                        }
                    }
                }
            } else {
                data = history(address, networks, limit)
            }
            data?.let { HistoryCache.save(address, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            loading = false
        }
    }

    selected?.let { (network, t) ->
        TransactionScreen(
            network, t, names,
            onBack = { selected = null },
            onOpenAddress = { a -> onOpenAddress(a, network) },
            onSaveContact = { a, n -> onSaveContact(a, network, n) },
        )
        return
    }
    BackHandler(onBack = onClose)

    Scaffold { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("← Back") }
                Column(Modifier.weight(1f)) {
                    Text(name ?: "Transaction history", style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(short(rawAddress(address)) + " · " + labelOf(address),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val loaded = data
                if (loaded != null && loaded.any { it.items.isNotEmpty() }) {
                    TextButton(onClick = { shareHistoryCsv(context, name, address, loaded) }) { Text("Export") }
                }
                explorerUrl(address)?.let { url ->
                    TextButton(onClick = { openInBrowser(context, url) }) { Text("Explorer") }
                }
            }
            HorizontalDivider()
            PullToRefreshBox(isRefreshing = loading, onRefresh = { reload++ }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val d = data
                    if (d == null) {
                        item {
                            if (loading) Text("Loading transfers…") else Text("Couldn't load history. Pull down to retry.")
                        }
                    } else {
                        val shown = d.filter { it.items.isNotEmpty() || it.error != null || it.note != null }
                        val quiet = d.filter { it.items.isEmpty() && it.error == null && it.note == null }
                        if (shown.isEmpty()) {
                            item { Text("No transfers found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        shown.forEach { h ->
                            item(key = "h:${h.network}") {
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    NetworkIcon(h.network, 22.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(h.network, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                                balances.firstOrNull { it.network == h.network && it.error == null }?.let { NetworkBalance(it) }
                                h.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                h.note?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            itemsIndexed(h.items, key = { i, _ -> "${h.network}:$i" }) { _, t ->
                                TransferRow(t, h.network, names, onClick = { selected = h.network to t })
                            }
                        }
                        if (shown.isNotEmpty() && quiet.isNotEmpty()) {
                            item {
                                Text("No transfers on: " + quiet.joinToString(", ") { it.network },
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    // More transfers: fetch a bigger batch (each source has its own maximum).
                    if (d != null && d.any { it.items.size >= 15 || it.canLoadMore } && limit < 200) {
                        item(key = "more") {
                            TextButton(onClick = { limit += 50; reload++ }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                                Text(if (loading) "Loading…" else "Load more")
                            }
                        }
                    }
                    if (seen.isNotEmpty()) {
                        item(key = "seen") {
                            Spacer(Modifier.height(12.dp))
                            Text("Detected by payment alerts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        itemsIndexed(seen, key = { i, _ -> "s:$i" }) { _, s ->
                            SeenRow(s, onClick = {
                                val incoming = s.amount.signum() > 0
                                selected = s.network to Transfer(
                                    incoming, s.amount.abs(), if (incoming) s.from else s.to, s.time, null, s.hash, s.from, s.to, s.symbol,
                                )
                            })
                        }
                    }
                }
            }
        }
    }
}

/** The address's current balance on one network: value, then its stablecoins and coin. */
@Composable
private fun NetworkBalance(r: ChainResult) {
    Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Balance", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(hideable(money(r.usd)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (r.usdt.signum() > 0 || r.native.signum() == 0) {
                    Text(hideable(fmt2(r.usdt)) + " USDT", style = MaterialTheme.typography.bodyMedium)
                }
                if (r.native.signum() > 0) {
                    Text(hideable(fmt(r.native)) + " " + r.nativeSym, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}@Composable
private fun TransferRow(t: Transfer, network: String, names: Map<String, String>, onClick: () -> Unit) {
    val other = t.counterparty
    val saved = other?.let { names.labelFor(it, network) }
    val twin = other?.takeIf { saved == null }?.let(::lookalikeOf)
    val exchange = other?.let { knownPlatform(it) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            DirectionIcon(t.symbol, t.incoming)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // A confirmed exchange on the other side gets its badge (direction: the coin's ↓ / ↑ and the colour).
                    if (saved == null && exchange != null) {
                        ExchangeBadge(exchange, 18.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        tr(if (t.incoming) "From" else "To") + " " + (saved ?: exchange ?: other?.let(::tiny) ?: "—"),
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // Address poisoning: looks like one of yours but isn't.
                twin?.let {
                    Text(tr("Lookalike of") + " " + walletLabel(it) + " · " + tr("don't copy"),
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = OutRed)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    NetworkIcon(network, 14.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        listOfNotNull(
                            if (saved != null || exchange != null) other?.let(::tiny) else null,
                            if (t.time > 0) shortDate(t.time / 1000) else tr("Pending"),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    (if (t.incoming) "+" else "−") + hideable(amountText(t.amount, t.symbol)),
                    fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                    color = if (t.incoming) InGreen else OutRed,
                )
                Text(t.symbol, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Very short address for list rows: TCLg…JJpx. */
private fun tiny(a: String) = rawAddress(a).let {
    val head = if (it.startsWith("0x") || it.startsWith("0:")) 6 else 4
    if (it.length > 12) it.take(head) + "…" + it.takeLast(4) else it
}

/** Full details of one transfer. Tap From/To to open that address and keep following the money. */
@Composable
internal fun TransactionScreen(
    network: String,
    t: Transfer,
    names: Map<String, String>,
    onBack: () -> Unit,
    onOpenAddress: (String) -> Unit,
    onSaveContact: (String, String) -> Unit,
    /** Looked up by transaction ID (not from one of your wallets): no "sent/received" side, shows its real status. */
    neutral: Boolean = false,
    status: String = "Confirmed",
) {
    val clipboard = LocalClipboardManager.current
    var naming by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    naming?.let { addr ->
        var contactName by remember(addr) { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text("Save as contact") },
            text = {
                androidx.compose.material3.OutlinedTextField(value = contactName, onValueChange = { contactName = it },
                    label = { Text("Name (e.g. Ali)") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { onSaveContact(addr, contactName.trim()); naming = null }, enabled = contactName.isNotBlank()) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } },
        )
    }
    @Composable
    fun Party(label: String, addr: String?) {
        if (addr.isNullOrBlank()) return
        val tag = names.labelFor(addr, network) ?: knownPlatform(addr)
        Card(Modifier.fillMaxWidth().clickable { onOpenAddress(addr) }) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label + (tag?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    knownPlatform(addr)?.takeIf { names.labelFor(addr, network) == null }?.let {
                        Spacer(Modifier.width(6.dp))
                        ExchangeBadge(it, 16.dp)
                    }
                }
                Text(addr, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                if (tag == null) lookalikeOf(addr)?.let {
                    Text(tr("Lookalike of") + " " + walletLabel(it) + " · " + tr("don't copy"),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = OutRed)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Open this address →", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    if (names.labelFor(addr, null) == null) {
                        TextButton(onClick = { naming = addr }) { Text("Save as contact") }
                    }
                }
            }
        }
    }
    Scaffold { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("← Back") } }
            item {
                Text(if (neutral) "Transfer" else if (t.incoming) "Received" else "Sent", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoinIcon(t.symbol, 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Text((if (neutral) "" else if (t.incoming) "+" else "−") + hideable(amountText(t.amount, t.symbol)) + " " + t.symbol,
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                        color = if (neutral) MaterialTheme.colorScheme.onSurface else if (t.incoming) InGreen else OutRed)
                }
                Text("$network · " + (if (t.time > 0) date(t.time / 1000) else tr("Pending")) + " · " + tr(status),
                    color = if (status == "Failed") OutRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (!neutral && t.hash == null && t.from == null && t.to == null) {
                item {
                    Text("Detected from a balance change before transaction details were recorded, so the sender, receiver " +
                        "and transaction ID aren't available for this one. New alerts include them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            t.txUrl?.let { url ->
                item {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    TextButton(onClick = { openInBrowser(ctx, url) }, contentPadding = PaddingValues(0.dp)) {
                        Text("Open in blockchain explorer →")
                    }
                }
            }
            item { Party("From", t.from) }
            item { Party("To", t.to) }
            t.hash?.let { h ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Transaction ID", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer {
                                Text(h, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                            }
                            TextButton(onClick = { clipboard.setText(AnnotatedString(h)) }, contentPadding = PaddingValues(0.dp)) {
                                Text("Copy")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeenRow(s: Seen, onClick: () -> Unit) {
    val incoming = s.amount.signum() > 0
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            DirectionIcon(s.symbol, incoming)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (incoming) "Received" else "Sent", fontWeight = FontWeight.SemiBold)
                Text("${s.network} · ${shortDate(s.time / 1000)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text((if (incoming) "+" else "−") + hideable(amountText(s.amount.abs(), s.symbol)) + " " + s.symbol,
                fontWeight = FontWeight.SemiBold, color = if (incoming) InGreen else OutRed)
        }
    }
}

/** Opens a block-explorer page in the phone's browser. */
fun openInBrowser(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
/** Coin logo with a small green ↓ (received) or red ↑ (sent) badge on its corner. */
@Composable
fun DirectionIcon(symbol: String, incoming: Boolean) {
    // The arrow is drawn (not a text character), so it sits exactly in the middle of its badge.
    val ring = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(Modifier.size(38.dp)) {
        Box(Modifier.align(Alignment.TopStart)) { CoinIcon(symbol, 34.dp) }
        androidx.compose.foundation.Canvas(Modifier.align(Alignment.BottomEnd).size(18.dp)) {
            val r = size.minDimension / 2
            drawCircle(ring, r) // thin ring separates the badge from the coin
            drawCircle(if (incoming) InGreen else OutRed, r - 1.5.dp.toPx())
            val c = center
            val half = r * 0.42f
            val arm = r * 0.34f
            val w = 1.6.dp.toPx()
            val tip = if (incoming) c.y + half else c.y - half
            val tail = if (incoming) c.y - half else c.y + half
            val back = if (incoming) -arm else arm
            val cap = androidx.compose.ui.graphics.StrokeCap.Round
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(c.x, tail), androidx.compose.ui.geometry.Offset(c.x, tip), w, cap)
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(c.x, tip), androidx.compose.ui.geometry.Offset(c.x - arm, tip + back), w, cap)
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(c.x, tip), androidx.compose.ui.geometry.Offset(c.x + arm, tip + back), w, cap)
        }
    }
}