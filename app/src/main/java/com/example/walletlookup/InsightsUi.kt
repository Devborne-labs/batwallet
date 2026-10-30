package com.example.walletlookup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    /** Every network answered and the address has no balance and no activity anywhere: not activated yet. */
    unused: Boolean = false,
    /** Networks that didn't answer: a clean result would only be partial. */
    unanswered: List<String> = emptyList(),
    /** Why the whole check couldn't run this time (the balances didn't load, no internet…). */
    error: String? = null,
    /** The check, or the balances it needs, is running again: Retry shows "Checking again…". */
    retrying: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val incomplete = unanswered.isNotEmpty()
    // Checks that couldn't run are listed with their reason under the verdict, not as ticks.
    val failures = safety?.failed.orEmpty()
    val shown = safety?.checks.orEmpty().filter { !it.failed }
    val badCheck = shown.firstOrNull { it.level == Level.BAD }
    val danger = twin != null || badCheck != null
    val verdict = when {
        twin != null -> tr("Lookalike of your saved %s").replace("%s", "\"${twin.name}\"")
        badCheck != null -> badCheck.title
        savedWallet?.contact == true -> tr("Your contact") + " · ${savedWallet.name}"
        // An exchange's wallet (or your deposit address there) isn't yours to spend from.
        savedWallet != null && savedWallet.onExchange -> tr("In your wallets") + " · ${savedWallet.name}"
        savedWallet != null -> "Your own wallet"
        safety == null && loading -> "Checking…"
        safety == null && error != null -> "Couldn't run the safety check"
        safety == null -> "Couldn't check right now"
        // A caution other than "never used" (e.g. "This is a smart contract") is the headline itself.
        safety.level == Level.WARN -> shown.firstOrNull { it.level == Level.WARN && it.title != NO_ACTIVITY_TITLE }?.title
            ?: "New or unused address"
        failures.isNotEmpty() -> "Some checks didn't run"
        else -> "No red flags found"
    }
    val red = MaterialTheme.colorScheme.error
    val amber = Color(0xFFE0A100)
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    // One colour and symbol per state: red = don't send, amber = never used, green = clean, grey = yours / checking.
    val notActivated = !danger && savedWallet == null && unused
    val partial = !danger && savedWallet == null && !notActivated && incomplete
    val (tint, symbol, title) = when {
        danger -> Triple(red, "!", verdict)
        notActivated -> Triple(amber, "!", tr("Not activated yet"))
        // Your own wallet or contact: green once the checks found nothing wrong (grey while checking).
        savedWallet != null -> if (safety != null && safety.level != Level.BAD) Triple(InGreen, "✓", verdict)
            else Triple(grey, "✓", verdict)
        // Grey, not green: a network didn't answer, so the all-clear would only cover part of the address.
        partial -> Triple(grey, "?", tr("Couldn't fully check"))
        safety == null && error != null -> Triple(grey, "?", verdict)
        safety == null -> Triple(grey, "…", verdict)
        safety.level == Level.WARN -> Triple(amber, "!", verdict)
        // Grey too when part of the check couldn't run: "no red flags" would only cover what did.
        failures.isNotEmpty() -> Triple(grey, "?", verdict)
        else -> Triple(InGreen, "✓", verdict)
    }
    val subtitle = when {
        twin != null -> "Different address made to look like yours. Don't send money to it."
        danger -> "Reported as a scam or compromised. Don't send money to this address."
        notActivated -> "This address has never received or sent anything. Double-check it before sending money."
        else -> platform?.let { tr("Platform:") + " " + it.display }
    }
    // What couldn't be checked and why, each as (what, reason).
    val problems = buildList {
        if (error != null) add("Safety check didn't run" to error)
        if (incomplete) add("Some networks didn't answer" to unanswered.joinToString(", "))
        failures.forEach { add(it.title to it.detail) }
    }
    Card(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = safety != null) { open = !open },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.13f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(tint.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) {
                    // The not-activated "!" shows straight away; it doesn't wait for the safety check.
                    if (loading && safety == null && !notActivated && !partial) BatSpinner(size = 16.dp, color = tint)
                    else Text(symbol, color = tint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        color = if (danger) red else MaterialTheme.colorScheme.onSurface)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = if (danger) red else grey, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                if (safety != null && shown.isNotEmpty()) {
                    Text(if (open) "Less" else "Details", style = MaterialTheme.typography.labelMedium,
                        color = grey, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (shown.isNotEmpty()) {
                if (!open) {
                    // The individual checks as small pills.
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        shown.forEach { c ->
                            // Each flag in its level's colour: green clean, amber caution, red danger, grey information.
                            val tone = levelColor(c.level)
                            Text(
                                "${icon(c.level)}  ${tr(c.title)}",
                                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                                color = tone,
                                modifier = Modifier.clip(RoundedCornerShape(50))
                                    .background(tone.copy(alpha = 0.16f))
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                } else {
                    shown.forEach { c ->
                        Column(Modifier.padding(top = 10.dp)) {
                            Text("${icon(c.level)}  ${tr(c.title)}", style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold, color = levelColor(c.level))
                            Text(c.detail, style = MaterialTheme.typography.labelSmall, color = grey)
                        }
                    }
                    Text("A clean result isn't a guarantee. Only send to people you trust.",
                        style = MaterialTheme.typography.labelSmall, color = grey,
                        modifier = Modifier.padding(top = 10.dp))
                }
            }
            if (problems.isNotEmpty()) SafetyProblems(problems, retrying, onRetry)
        }
    }
}

/** What the safety check couldn't do and why, with a Retry button (or "Checking again…" while it runs). */
@Composable
private fun SafetyProblems(problems: List<Pair<String, String>>, retrying: Boolean, onRetry: (() -> Unit)?) {
    Column(
        Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        problems.forEach { (what, why) ->
            Column {
                Text("✕  " + tr(what), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(tr(why), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp))
            }
        }
        if (onRetry != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (retrying) {
                    BatSpinner(size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Checking again…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    OutlinedButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry")
                    }
                }
            }
        }
    }
}

/** Title of the activity check's "never used" caution; that caution keeps the plain "New or unused address" headline. */
private const val NO_ACTIVITY_TITLE = "No stablecoin transfers found"

private fun icon(level: Level) = when (level) {
    Level.GOOD -> "✓"
    Level.INFO -> "i"
    Level.WARN -> "!"
    Level.BAD -> "✕"
}

/** One colour per check level: green clean, amber caution, red danger, grey for plain information. */
@Composable
private fun levelColor(level: Level): Color = when (level) {
    Level.GOOD -> InGreen
    Level.WARN -> Color(0xFFE0A100)
    Level.BAD -> MaterialTheme.colorScheme.error
    Level.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
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
    val scope = rememberCoroutineScope()
    // Networks being asked again after their history failed to load.
    var retrying by remember { mutableStateOf(setOf<String>()) }

    /**
     * One network failed to load: ask just that one again (the others stay as they are). An address with a single
     * network, or one pinned to a network, reloads the whole list instead.
     */
    fun retry(network: String) {
        val nets = networks
        if (nets == null || nets.size <= 1 || netOf(address) != null || network !in nets) { reload++; return }
        if (network in retrying) return
        retrying = retrying + network
        // A pull-down or Load more started after this one supersedes it: its result is then dropped, not mixed in.
        val startedAt = reload to limit
        scope.launch {
            try {
                val fresh = try {
                    history(address, setOf(network), limit)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                if (fresh.isNotEmpty() && (reload to limit) == startedAt) {
                    val order = nets.toList()
                    data = (data.orEmpty().filter { it.network != network } + fresh)
                        .sortedBy { order.indexOf(it.network).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                    data?.let { HistoryCache.save(address, it) }
                }
            } finally {
                retrying = retrying - network
            }
        }
    }

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
                            val h = try {
                                history(address, setOf(n), limit)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                emptyList()
                            }
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

    // Kept here, above the transaction page, so Back from a transaction returns to the same place in the list.
    val listState = rememberLazyListState()
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
            BatPullToRefresh(isRefreshing = loading, onRefresh = { reload++ }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val d = data
                    if (d == null) {
                        item {
                            if (loading) Text("Loading transfers…")
                            else {
                                Text("Couldn't load history.")
                                TextButton(onClick = { reload++ }, contentPadding = PaddingValues(0.dp)) { Text("Retry") }
                            }
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
                                h.error?.let { err ->
                                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    // Ask this network again without pulling down the whole list.
                                    val again = h.network in retrying
                                    TextButton(onClick = { retry(h.network) }, enabled = !loading && !again,
                                        contentPadding = PaddingValues(0.dp)) {
                                        Text(if (again) "Loading…" else "Retry")
                                    }
                                }
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
                                Text(tr("No transfers on:") + " " + quiet.joinToString(", ") { it.network },
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    // More transfers: fetch a bigger batch (each source has its own maximum).
                    // Each source says whether a bigger page would add anything (a fixed-size one can't).
                    if (d != null && d.any { it.canLoadMore } && limit < 200) {
                        item(key = "more") {
                            TextButton(onClick = { limit += 50; reload++ }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                                Text(if (loading) "Loading…" else "Load more")
                            }
                        }
                    }
                    // Payments the alerts caught that the blockchain list above doesn't already show (no doubles).
                    val unmatched = seen.filter { s -> !isCoinDust(s.amount, s.symbol) && d.orEmpty().none { h -> h.network == s.network && h.items.any { sameTransfer(it, s) } } }
                    if (unmatched.isNotEmpty()) {
                        item(key = "seen") {
                            Spacer(Modifier.height(12.dp))
                            Text("Detected by payment alerts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        itemsIndexed(unmatched, key = { i, _ -> "s:$i" }) { _, s ->
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

/**
 * Whether an alert's payment is this blockchain transfer: same transaction ID, or (older alerts without one) same
 * direction, coin and amount, noticed within an hour after it happened.
 */
private fun sameTransfer(t: Transfer, s: Seen): Boolean {
    if (s.hash != null && t.hash != null) return s.hash.equals(t.hash, ignoreCase = true)
    return t.incoming == (s.amount.signum() > 0) && t.symbol == s.symbol && t.amount.compareTo(s.amount.abs()) == 0 &&
        s.time - t.time in -5 * 60_000L..60 * 60_000L
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
                        fromTo(t.incoming, saved ?: exchange ?: other?.let(::tiny) ?: "—"),
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
            // In your chosen currency, like Home; the coin amount is on the transaction's page.
            Text(
                (if (t.incoming) "+" else "−") + hideable(transferValue(t.amount, t.symbol)),
                fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                color = if (t.incoming) InGreen else OutRed,
            )
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
                    Text(tr(label) + (tag?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelMedium,
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
                // Coins: what it's worth in your currency (stablecoins already are).
                if (!t.symbol.uppercase().startsWith("USD")) transferUsd(t.amount, t.symbol)?.let { v ->
                    Text("≈ " + hideable(money(v)), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("$network · " + (if (t.time > 0) date(t.time / 1000) else tr("Pending")) + " · " + tr(status),
                    color = if (status == "Failed") OutRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (!neutral && t.hash == null && t.from == null && t.to == null) {
                item {
                    // Still being matched to the blockchain: say so clearly, with a spinner, not as an error.
                    // After a day it won't be found any more (coin transfers can only be traced for minutes): say that.
                    val stale = t.time > 0 && System.currentTimeMillis() - t.time > detailsWindowMs(t.symbol)
                    Card(Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                            if (stale) Text("i", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp))
                            else BatSpinner(Modifier.padding(top = 2.dp), size = 18.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(if (stale) "Details not available" else "Finding the transaction details",
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(if (stale) "Payment alerts noticed this from a balance change, but the transaction couldn't be " +
                                    "matched. Coin transfers (BNB, ETH, POL) can only be traced in the first minutes on free nodes."
                                else "Payment alerts noticed this from a balance change. Batwallet is matching it to the " +
                                    "blockchain; the sender, receiver and transaction ID show up here once found.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
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
                Text("${s.network} · ${shortDate(s.time / 1000)}" + (if (s.hash == null && System.currentTimeMillis() - s.time < detailsWindowMs(s.symbol)) " · " + tr("finding details…") else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text((if (incoming) "+" else "−") + hideable(transferValue(s.amount.abs(), s.symbol)),
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