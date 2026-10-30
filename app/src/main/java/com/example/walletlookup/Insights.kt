package com.example.walletlookup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URLEncoder
import java.time.Instant

// ===================== Transaction history (stablecoin transfers) =====================

data class Transfer(
    val incoming: Boolean,
    val amount: BigDecimal,
    val counterparty: String?,
    val time: Long, // epoch millis
    val txUrl: String?,
    val hash: String? = null,
    val from: String? = null,
    val to: String? = null,
    val symbol: String = "USDT",
)

data class History(
    val network: String,
    val items: List<Transfer> = emptyList(),
    val note: String? = null,
    val error: String? = null,
    /** False when this network has no in-app history (only a note is shown). */
    val supported: Boolean = true,
    /** True when older transfers can still be fetched (Load more). */
    val canLoadMore: Boolean = false,
)

/**
 * One source's transfers, for merging several sources into one network's list. [bounded]: it stopped at a maximum,
 * so it knows nothing older than [reach] (epoch millis: its oldest entry, counted before any filtering, or the start
 * of the window it looked in). [grows]: asking with a bigger limit (Load more) gets more from it; a source with a
 * fixed maximum can't. [note] and [supported] are passed on to the network's [History].
 */
internal class Part(
    val items: List<Transfer>,
    val bounded: Boolean = false,
    val reach: Long = 0L,
    val grows: Boolean = false,
    val note: String? = null,
    val supported: Boolean = true,
)

/**
 * A source's page as a [Part]: [count] entries before any filtering (the oldest at [oldest]) from a request for
 * [asked], where the source never gives more than [cap]. A full page means there may be older ones.
 */
internal fun sourcePage(items: List<Transfer>, count: Int, asked: Int, oldest: Long, cap: Int = Int.MAX_VALUE): Part {
    val full = count >= minOf(asked, cap)
    return Part(items, full, if (full) oldest else 0L, grows = full && asked < cap)
}

/**
 * Several sources' transfers as one list, newest first and at most [limit]. A bounded source that Load more can extend
 * knows nothing older than its reach yet, so the list ends at the newest such point (its older transfers come with
 * the next page), instead of showing the other sources' older transfers with this one's missing in between. A source
 * with a fixed maximum can't be extended: cutting at it would hide the others' older transfers for good (e.g. USDT
 * history behind a 50-transaction coin list), so it doesn't cut; only its own older transfers are missing.
 * Also says whether a bigger limit (Load more) would add anything.
 */
internal fun mergeParts(parts: List<Part>, limit: Int): Pair<List<Transfer>, Boolean> {
    val growing = parts.filter { it.bounded && it.grows }
    val cut = growing.maxOfOrNull { it.reach } ?: Long.MIN_VALUE
    val kept = parts.flatMap { it.items }.filter { it.time >= cut || it.time <= 0 }.sortedByDescending { it.time }
    val more = kept.size > limit || growing.isNotEmpty()
    return kept.take(limit) to more
}

/** A network's [History] from its sources' [parts], at most the current limit. */
internal fun historyOf(network: String, parts: List<Part>): History {
    val (items, more) = mergeParts(parts, historyLimit)
    return History(network, items, note = parts.firstNotNullOfOrNull { it.note }, supported = parts.all { it.supported },
        canLoadMore = more)
}

/** Optional free Etherscan API key (Settings). Its free plan covers Ethereum and some networks, not BSC/Base/Polygon/Arbitrum. */
@Volatile var etherscanKey: String = ""

/** The ERC-20 Transfer event, which every stablecoin transfer on an EVM network emits. */
internal const val TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
/**
 * How many transfers to fetch per network ("Load more" raises it). Set per request and carried along with it (also
 * into its parallel parts), so Home's checks and an open History page can't change each other's count.
 */
private val historyLimitLocal = ThreadLocal<Int>()
internal val historyLimit: Int get() = historyLimitLocal.get() ?: 25
private val LIMIT: Int get() = historyLimit

private data class EvmHist(
    val name: String, val chainId: Long, val blockscout: String?,
    val token: String, val decimals: Int, val symbol: String, val txBase: String,
    /** Routescan offers a free, key-less Etherscan-style API for some networks. */
    val routescan: Boolean = false,
)

private val EVM_HIST = listOf(
    EvmHist("Ethereum", 1, "eth.blockscout.com", "0xdAC17F958D2ee523a2206206994597C13D831ec7", 6, "USDT", "https://etherscan.io/tx/", routescan = true),
    EvmHist("BSC", 56, null, "0x55d398326f99059fF775485246999027B3197955", 18, "USDT", "https://bscscan.com/tx/"),
    EvmHist("Polygon", 137, null, "0xc2132D05D31c914a87C6611C10748AEb04B58e8F", 6, "USDT", "https://polygonscan.com/tx/"),
    EvmHist("Arbitrum", 42161, null, "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", 6, "USDT", "https://arbiscan.io/tx/"),
    EvmHist("Optimism", 10, "optimism.blockscout.com", "0x94b008aA00579c1307B0EF2c499aD98a8ce58e58", 6, "USDT", "https://optimistic.etherscan.io/tx/"),
    EvmHist("Base", 8453, null, "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", 6, "USDC", "https://basescan.org/tx/"),
    EvmHist("Avalanche", 43114, null, "0x9702230A8Ea53601f5cD2dc00fDBc13d4dF4A8c7", 6, "USDT", "https://snowtrace.io/tx/", routescan = true),
    EvmHist("Celo", 42220, "celo.blockscout.com", "0x48065fbBE25f71C9282ddf5e1cD6D6A887483D5e", 6, "USDT", "https://celoscan.io/tx/"),
    EvmHist("Sonic", 146, null, "0x29219dd400f2Bf60E5a23d13Be72B486D4038894", 6, "USDC", "https://sonicscan.org/tx/"),
)

/**
 * Latest transfers of an address on each of its networks. [networks]: the networks where the balances show funds or
 * activity. A 0x address is only looked up on those, and an empty set means no activity anywhere, so it comes back
 * empty at once; null works them out first (one more balance lookup). For an old Bitcoin address it picks Bitcoin
 * and/or Bitcoin Cash.
 */
suspend fun history(raw: String, networks: Set<String>? = null, limit: Int = 25): List<History> =
    withContext(historyLimitLocal.asContextElement(limit)) {
        historyRaw(raw, networks).map { h -> h.copy(items = h.items.filterNot(::isDust)) }
    }

/**
 * The transfer(s) behind a balance change the alerts saw without details ([change]: + in, − out). Usually one
 * transfer of that amount; but when several happened between two checks (e.g. +100.01 in and −130 out shown as
 * −29.99), the newest transfers before [time] that add up to it. Event-log networks are searched right around that
 * time (works even when it's older than the normal history scan); others look further back in their history.
 * Empty when nothing fits.
 */
suspend fun findTransfers(raw: String, network: String, symbol: String, change: java.math.BigDecimal, time: Long): List<Transfer> =
    withContext(Dispatchers.IO) {
        val a = rawAddress(raw)
        val evm = detect(a) == AddrType.EVM
        val node = EVM_HIST.firstOrNull { it.name == network && it.chainId in LOG_NODES }
        // The stablecoin's contract on this network: the one its history lists, else the other one from the balance list.
        val token = node?.let { h ->
            if (h.symbol.equals(symbol, true)) Triple(h.token, h.decimals, h.symbol)
            else EVM_CHAINS.firstOrNull { it.name == network }?.tokens?.firstOrNull { it.symbol.startsWith(symbol, ignoreCase = true) }
                ?.let { Triple(it.contract, it.decimals, symbol.uppercase()) }
        }
        val window = time - 3 * 3_600_000L..time + 15 * 60_000L
        val candidates: List<Transfer>? = when {
            evm && node != null && token != null ->
                runCatching { logWindow(node, a, time, token.first, token.second, token.third) }.getOrNull()
            // Plain TRX (Tron's own coin): asked for directly, around that time.
            network == "Tron" && symbol.equals("TRX", true) -> runCatching { tronCoinWindow(a, time) }.getOrNull()
            // BNB / ETH / POL: from the free coin-transfer lists (Binplorer, Ethplorer, Routescan, Blockscout).
            evm && !symbol.uppercase().startsWith("USD") -> EVM_HIST.firstOrNull { it.name == network }?.let { h ->
                runCatching { evmNativeTransfers(h.name, h.chainId, h.blockscout, h.routescan, h.txBase, a) }.getOrNull()
                    ?.filter { it.symbol.equals(symbol, true) && it.time in window }
            }
            else -> {
                val found = try {
                    history(raw, setOf(network), limit = 100)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                found?.flatMap { it.items }?.filter { it.symbol.equals(symbol, true) && (it.time <= 0 || it.time in window) }
            }
        }
        pickTransfers(candidates.orEmpty(), change, time)
    }

/** Plain TRX transfers in and out of [a] from 3 hours before [time] to 15 minutes after. */
private fun tronCoinWindow(a: String, time: Long): List<Transfer> =
    tronCoinPage(a, time - 3 * 3_600_000L, time + 15 * 60_000L).items

/**
 * Plain TRX transfers in and out of [a] between [from] and [to] (epoch millis; TronGrid, Tronscan as backup). They
 * give at most 100 (Tronscan 50) transactions of any kind, so a busy wallet's list can stop short of [from]: then it
 * reaches back only to its oldest one.
 */
private fun tronCoinPage(a: String, from: Long, to: Long): Part {
    fun transfer(hash: String, f: String, t: String, sun: Long, ts: Long): Transfer {
        val incoming = t == a
        return Transfer(incoming, amount(BigInteger.valueOf(sun), 6), if (incoming) f else t, ts,
            "https://tronscan.org/#/transaction/$hash", hash, f, t, "TRX")
    }
    fun window(items: List<Transfer>, count: Int, cap: Int, oldest: Long): Part {
        val full = count >= cap
        // Not full: complete back to [from], and a longer window (Load more on the other list) gets more.
        return Part(items, full || from > 0, if (full) oldest else from, grows = !full)
    }
    runCatching {
        val arr = JSONObject(http("https://api.trongrid.io/v1/accounts/$a/transactions?only_confirmed=true&limit=100" +
            "&min_timestamp=$from&max_timestamp=$to")).getJSONArray("data")
        val oldest = (0 until arr.length()).map { arr.getJSONObject(it).optLong("block_timestamp") }.filter { it > 0 }.minOrNull() ?: 0L
        val items = (0 until arr.length()).mapNotNull { i ->
            val tx = arr.getJSONObject(i)
            val c = tx.getJSONObject("raw_data").getJSONArray("contract").getJSONObject(0)
            if (c.getString("type") != "TransferContract") return@mapNotNull null
            val v = c.getJSONObject("parameter").getJSONObject("value")
            transfer(tx.getString("txID"), tronBase58(v.getString("owner_address")), tronBase58(v.getString("to_address")),
                v.getLong("amount"), tx.getLong("block_timestamp"))
        }
        return window(items, arr.length(), 100, oldest)
    }
    val arr = JSONObject(http("https://apilist.tronscanapi.com/api/transaction?sort=-timestamp&limit=50&start=0&address=$a" +
        "&start_timestamp=$from&end_timestamp=$to")).getJSONArray("data")
    val oldest = (0 until arr.length()).map { arr.getJSONObject(it).optLong("timestamp") }.filter { it > 0 }.minOrNull() ?: 0L
    val items = (0 until arr.length()).mapNotNull { i ->
        val tx = arr.getJSONObject(i)
        if (tx.optInt("contractType") != 1) return@mapNotNull null // 1 = TRX transfer
        transfer(tx.getString("hash"), tx.getString("ownerAddress"), tx.getString("toAddress"),
            tx.optString("amount", "0").toLong(), tx.getLong("timestamp"))
    }
    return window(items, arr.length(), 50, oldest)
}

/** Tron's hex address ("41…") as the usual T… address (Base58 with a 4-byte double-SHA-256 checksum). */
internal fun tronBase58(hex: String): String {
    val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val sha = java.security.MessageDigest.getInstance("SHA-256")
    val check = sha.digest(sha.digest(bytes)).copyOf(4)
    var n = BigInteger(1, bytes + check)
    val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    val sb = StringBuilder()
    val base = BigInteger.valueOf(58)
    while (n.signum() > 0) { val (q, r) = n.divideAndRemainder(base); sb.append(alphabet[r.toInt()]); n = q }
    for (b in bytes + check) { if (b.toInt() == 0) sb.append('1') else break }
    return sb.reverse().toString()
}

/** Free nodes that keep balances of recent blocks, per network (for tracing coin transfers that just happened). */
private val COIN_NODES = mapOf(
    "BSC" to "https://bsc-rpc.publicnode.com",
    "Ethereum" to "https://ethereum-rpc.publicnode.com",
    "Polygon" to "https://polygon-bor-rpc.publicnode.com",
    "Base" to "https://base-rpc.publicnode.com",
    "Arbitrum" to "https://arb1.arbitrum.io/rpc",
)

/**
 * A coin transfer (BNB, ETH, POL) that happened in the last few minutes. These don't appear in transfer logs, and no
 * free service lists them, so: find the block where the balance last changed (free nodes keep recent balances, about
 * the last 1,000 blocks) and read that block's transactions. Call it right after the change is noticed.
 */
fun recentCoinTransfer(raw: String, network: String, change: java.math.BigDecimal): Transfer? {
    val url = COIN_NODES[network] ?: return null
    val a = rawAddress(raw).lowercase()
    fun rpc(method: String, params: JSONArray): Any {
        val o = JSONObject(http(url, JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString()))
        if (o.has("error") || !o.has("result")) throw java.io.IOException(o.optJSONObject("error")?.optString("message") ?: "no result")
        return o.get("result")
    }
    fun hex(n: Long) = "0x" + n.toString(16)
    fun bal(b: Long) = BigInteger((rpc("eth_getBalance", JSONArray().put(a).put(hex(b))) as String).removePrefix("0x"), 16)
    val latest = BigInteger((rpc("eth_blockNumber", JSONArray()) as String).removePrefix("0x"), 16).toLong()
    val now = bal(latest)
    // The furthest block back the node still has a balance for, and that differs from now.
    var span = 1000L
    var lo: Long
    while (true) {
        lo = latest - span
        val b = runCatching { bal(lo) }.getOrNull()
        if (b != null) { if (b == now) return null else break }
        span /= 2
        if (span < 16) return null
    }
    // Binary search: bal(lo) != now, bal(hi) == now; the change is in block hi.
    var hi = latest
    while (hi - lo > 1) { val m = (lo + hi) / 2; if (bal(m) == now) hi = m else lo = m }
    val block = rpc("eth_getBlockByNumber", JSONArray().put(hex(hi)).put(true)) as JSONObject
    val incoming = change.signum() > 0
    val txs = block.getJSONArray("transactions")
    val tx = (0 until txs.length()).map { txs.getJSONObject(it) }.firstOrNull { t ->
        val to = t.optString("to").lowercase()
        val from = t.optString("from").lowercase()
        BigInteger(t.optString("value", "0x0").removePrefix("0x").ifEmpty { "0" }, 16).signum() > 0 &&
            (if (incoming) to == a else from == a)
    } ?: return null
    val hash = tx.getString("hash")
    val from = tx.getString("from")
    val to = tx.optString("to")
    val value = amount(BigInteger(tx.getString("value").removePrefix("0x"), 16), 18)
    val ts = BigInteger(block.getString("timestamp").removePrefix("0x"), 16).toLong() * 1000
    val base = EVM_HIST.firstOrNull { it.name == network }?.txBase
    val sym = when (network) { "BSC" -> "BNB"; "Polygon" -> "POL"; else -> "ETH" }
    return Transfer(incoming, value, if (incoming) from else to, ts, base?.plus(hash), hash, from, to, sym)
}

/**
 * About the most a plain coin transfer's network fee can be, per coin: a coin sent also paid its fee from the same
 * balance, so the balance drops by a bit more than the amount sent. Coins whose history already counts the fee in
 * the amount (Bitcoin and the like) aren't listed.
 */
private val FEE_ALLOWANCE: Map<String, BigDecimal> = mapOf(
    "ETH" to "0.01", "BNB" to "0.002", "POL" to "0.5", "AVAX" to "0.01", "CELO" to "0.01", "S" to "0.1",
    "TRX" to "2", "SOL" to "0.01", "TON" to "0.05", "XRP" to "0.01", "XLM" to "0.01", "ALGO" to "0.01",
    "XTZ" to "0.1", "APT" to "0.01",
).mapValues { BigDecimal(it.value) }

/**
 * One transfer of exactly [change], else (a coin sent) one a fee's worth smaller, else the newest ones up to [time]
 * whose amounts add up to it. Only transfers from 3 hours before [time] to 15 minutes after count (and ones whose
 * time isn't known), so an old transfer of the same amount is never taken for a new one.
 */
fun pickTransfers(candidates: List<Transfer>, change: java.math.BigDecimal, time: Long): List<Transfer> {
    val incoming = change.signum() > 0
    val target = change.abs()
    val near = candidates.filter { it.time <= 0 || it.time in time - 3 * 3_600_000L..time + 15 * 60_000L }
    near.filter { it.incoming == incoming && it.amount.compareTo(target) == 0 }
        .minByOrNull { kotlin.math.abs(it.time - time) }?.let { return listOf(it) }
    if (!incoming) {
        near.filter { t ->
            val fee = FEE_ALLOWANCE[t.symbol.uppercase()]
            !t.incoming && fee != null && t.amount.signum() > 0 && t.amount <= target && target - t.amount <= fee
        }.minWithOrNull(compareBy({ kotlin.math.abs(it.time - time) }, { target - it.amount }))?.let { return listOf(it) }
    }
    var sum = java.math.BigDecimal.ZERO
    val picked = mutableListOf<Transfer>()
    for (t in near.filter { it.time <= time + 5 * 60_000L }.sortedByDescending { it.time }.take(12)) {
        picked += t
        sum += if (t.incoming) t.amount else t.amount.negate()
        if (picked.size > 1 && sum.compareTo(change) == 0) return picked
    }
    return emptyList()
}

/** Every transfer of the [token] in and out of [a] from 3 hours before [time] to 15 minutes after. */
private fun logWindow(c: EvmHist, a: String, time: Long, token: String, decimals: Int, symbol: String): List<Transfer> {
    val node = LOG_NODES.getValue(c.chainId)
    val me = "0x" + a.removePrefix("0x").lowercase().padStart(64, '0')
    fun call(id: Int, method: String, params: JSONArray) =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
    fun blockTime(n: Long) = BigInteger(JSONObject(node.post(call(1, "eth_getBlockByNumber", JSONArray().put("0x" + n.toString(16)).put(false)).toString()))
        .getJSONObject("result").getString("timestamp").removePrefix("0x"), 16).toLong()
    // Which blocks: estimate the block rate from the latest block and one 10,000 back.
    val latest = BigInteger(JSONObject(node.post(call(1, "eth_blockNumber", JSONArray()).toString())).getString("result").removePrefix("0x"), 16).toLong()
    val back = maxOf(0L, latest - 10_000)
    val tLatest = blockTime(latest)
    val perSec = (latest - back).toDouble() / maxOf(1L, tLatest - blockTime(back))
    fun blockAt(sec: Long) = (latest - ((tLatest - sec) * perSec).toLong()).coerceIn(0L, latest)
    val from = blockAt(time / 1000 - 3 * 3600)
    val to = blockAt(time / 1000 + 15 * 60)
    val step = if (node.backupsFullRange) node.chunk else minOf(node.chunk, LogNode.BACKUP_SPAN)
    val ranges = generateSequence(from) { it + step }.takeWhile { it <= to }.map { it..minOf(to, it + step - 1) }.toList()
    val logs = mutableListOf<JSONObject>()
    ranges.chunked(maxOf(1, node.perRequest / 2)).forEach { part ->
        val batch = JSONArray()
        var id = 0
        part.forEach { r ->
            for (incoming in listOf(true, false)) {
                val topics = if (incoming) JSONArray().put(TRANSFER_TOPIC).put(JSONObject.NULL).put(me) else JSONArray().put(TRANSFER_TOPIC).put(me)
                batch.put(call(id++, "eth_getLogs", JSONArray().put(JSONObject()
                    .put("fromBlock", "0x" + r.first.toString(16)).put("toBlock", "0x" + r.last.toString(16))
                    .put("address", token).put("topics", topics))))
            }
        }
        val resp = JSONArray(node.post(batch.toString(), step))
        for (k in 0 until resp.length()) {
            val arr = resp.getJSONObject(k).optJSONArray("result") ?: continue
            for (m in 0 until arr.length()) logs += arr.getJSONObject(m)
        }
        // A very busy wallet: too many transfers in the window to tell which ones made the change.
        if (logs.size > 300) return emptyList()
    }
    // Times: one request per distinct block (only a handful in a 3-hour window for one wallet).
    val times = HashMap<Long, Long>()
    return logs.distinctBy { it.getString("transactionHash") + it.optString("logIndex") }.map { l ->
        val block = BigInteger(l.getString("blockNumber").removePrefix("0x"), 16).toLong()
        val topics = l.getJSONArray("topics")
        val f = "0x" + topics.getString(1).takeLast(40)
        val t = "0x" + topics.getString(2).takeLast(40)
        val incoming = t.equals(a, ignoreCase = true)
        val hash = l.getString("transactionHash")
        val ts = times.getOrPut(block) { runCatching { blockTime(block) * 1000 }.getOrDefault(0L) }
        Transfer(incoming, amount(BigInteger(l.getString("data").removePrefix("0x").ifEmpty { "0" }, 16), decimals),
            if (incoming) f else t, ts, c.txBase + hash, hash, f, t, symbol)
    }
}

/**
 * Stablecoin transfers under 1 cent are almost always "address poisoning" spam: scammers send dust from
 * a lookalike address so it shows up in your history and gets copied by mistake. They are hidden.
 */
private fun isDust(t: Transfer) = t.symbol.startsWith("USD") && t.amount < java.math.BigDecimal("0.01")

private suspend fun historyRaw(raw: String, networks0: Set<String>?): List<History> = withContext(Dispatchers.IO) {
    val a = rawAddress(raw)
    val networks = netOf(raw)?.let { setOf(it) } ?: networks0
    val type = detect(a) ?: return@withContext emptyList()
    when (type) {
        AddrType.EVM -> {
            // Only ask the networks where the wallet is actually used (worked out here when not given).
            val used = networks ?: try {
                lookup(a, AddrType.EVM, withPrices = false).filter { it.active && it.error == null }.map { it.network }.toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // No funds or activity on any network: nothing to look up.
            if (used != null && used.isEmpty()) return@withContext emptyList()
            // Networks without a history source (VeChain, Ethereum Classic): say so, not "no transfers".
            val noSource = used.orEmpty().filter { n -> EVM_HIST.none { it.name == n } }.map { n ->
                History(n, supported = false, note = noHistoryNote(n))
            }
            EVM_HIST.filter { used == null || it.name in used }.map { c ->
                async(Dispatchers.IO) {
                    safeHistory(c.name) {
                        // Stablecoin transfers plus the network's own coin (ETH, BNB, …), newest first.
                        val stable = evmHistory(c, a)
                        val coin = runCatching { evmNativePage(c.name, c.chainId, c.blockscout, c.routescan, c.txBase, a) }.getOrNull()
                        historyOf(c.name, listOfNotNull(stable, coin))
                    }
                }
            }.awaitAll() + noSource
        }
        AddrType.TRON -> listOf(safeHistory("Tron") { tronHistory(a) })
        AddrType.APTOS -> listOf(safeHistory("Aptos") { aptosCached(a) })
        AddrType.TON -> listOf(safeHistory("TON") { tonHistory(a) })
        else -> coinHistory(type, a, networks) ?: listOf(History(type.label, supported = false, note = noHistoryNote(type.label)))
    }
}

private fun noHistoryNote(network: String) = tr("History for %s isn't available in the app yet.").replace("%s", network)

private inline fun safeHistory(network: String, block: () -> History): History =
    try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { History(network, error = friendlyError(e)) }

/** Short, human error text instead of raw server responses. */
fun friendlyError(e: Exception): String {
    val m = e.message.orEmpty()
    return when {
        e is java.net.UnknownHostException || e is java.net.ConnectException -> "No internet connection."
        e is java.net.SocketTimeoutException -> "The service took too long. Pull down to retry."
        m.contains("Rate limited") || m.contains("HTTP 429") -> "Too many requests. Try again in a minute."
        m.contains("HTTP") -> "This network's history service isn't available right now."
        else -> "Couldn't load right now."
    }
}

/**
 * Tries each source for an EVM network in turn and uses the first that works:
 * your Etherscan key, then Routescan, Blockscout, and finally a scan of a public node's event logs.
 * Every network in [EVM_HIST] has at least one source.
 */
private fun evmHistory(c: EvmHist, a: String): Part {
    val sources = buildList<() -> Part> {
        if (etherscanKey.isNotBlank()) add { etherscanHistory(c, a) }
        if (c.routescan) add { routescanHistory(c, a) }
        if (c.blockscout != null) add { blockscoutHistory(c, a) }
        if (c.chainId in LOG_NODES) add { logHistory(c, a) }
    }
    var last: Exception? = null
    for ((i, source) in sources.withIndex()) {
        try {
            val p = source()
            if (p.supported || i == sources.lastIndex) return p
        } catch (e: Exception) {
            last = e
        }
    }
    throw last ?: IllegalStateException("No history source for ${c.name}")
}

private fun routescanHistory(c: EvmHist, a: String): Part {
    val url = "https://api.routescan.io/v2/network/mainnet/evm/${c.chainId}/etherscan/api?module=account&action=tokentx" +
        "&contractaddress=${c.token}&address=$a&page=1&offset=$LIMIT&sort=desc"
    return parseEtherscanStyle(c, a, JSONObject(http(url)))
}

/** Blockscout gives one page of 50, whatever the limit. */
internal const val BLOCKSCOUT_PAGE = 50

private fun blockscoutHistory(c: EvmHist, a: String): Part {
    val j = JSONObject(http("https://${c.blockscout}/api/v2/addresses/$a/token-transfers?type=ERC-20&token=${c.token}"))
    val arr = j.getJSONArray("items")
    val items = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val from = o.getJSONObject("from").getString("hash")
        val to = o.getJSONObject("to").getString("hash")
        val total = o.getJSONObject("total")
        val incoming = to.equals(a, ignoreCase = true)
        val hash = o.getString("transaction_hash")
        Transfer(
            incoming,
            amount(BigInteger(total.getString("value")), total.optString("decimals", c.decimals.toString()).toIntOrNull() ?: c.decimals),
            if (incoming) from else to,
            Instant.parse(o.getString("timestamp")).toEpochMilli(),
            c.txBase + hash, hash, from, to, c.symbol,
        )
    }
    // One page, whatever the limit: Load more can't reach further back here.
    val more = j.optJSONObject("next_page_params") != null || arr.length() >= BLOCKSCOUT_PAGE
    return Part(items, more, if (more) items.minOfOrNull { it.time } ?: 0L else 0L)
}

private fun etherscanHistory(c: EvmHist, a: String): Part {
    val url = "https://api.etherscan.io/v2/api?chainid=${c.chainId}&module=account&action=tokentx" +
        "&contractaddress=${c.token}&address=$a&page=1&offset=$LIMIT&sort=desc&apikey=${URLEncoder.encode(etherscanKey, "UTF-8")}"
    val j = JSONObject(http(url))
    if (j.optString("status") != "1" && !j.optString("message").contains("No transactions", true)) {
        // The key doesn't cover this network (free plan): let the next free source answer.
        return Part(emptyList(), supported = false,
            note = tr("Your Etherscan key doesn't cover %s on the free plan.").replace("%s", c.name))
    }
    return parseEtherscanStyle(c, a, j)
}

private fun parseEtherscanStyle(c: EvmHist, a: String, j: JSONObject): Part {
    if (j.optString("status") != "1") {
        if (j.optString("message").contains("No transactions", true)) return Part(emptyList())
        throw java.io.IOException("HTTP " + j.optString("message"))
    }
    val arr = j.getJSONArray("result")
    val items = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val from = o.getString("from")
        val to = o.getString("to")
        val incoming = to.equals(a, ignoreCase = true)
        Transfer(
            incoming,
            amount(BigInteger(o.getString("value")), o.optString("tokenDecimal", c.decimals.toString()).toInt()),
            if (incoming) from else to,
            o.getString("timeStamp").toLong() * 1000,
            c.txBase + o.getString("hash"), o.getString("hash"), from, to, c.symbol,
        )
    }
    return sourcePage(items, arr.length(), LIMIT, items.minOfOrNull { it.time } ?: 0L)
}

/** Where history found by scanning a node's event logs is kept (set at start-up), so each visit only scans new blocks. */
@Volatile var historyPrefs: android.content.SharedPreferences? = null

/** Public nodes that answer eth_getLogs without a key: the URL, blocks per call, and calls per request. */
/**
 * A free node for event-log history, plus [backups] tried in turn when it fails. Backups that only take small block
 * windows ([backupsFullRange] false, at most [BACKUP_SPAN] blocks) are used only for requests that fit.
 */
private class LogNode(
    val rpc: String, val chunk: Long, val perRequest: Int,
    val backups: List<String> = emptyList(), val backupsFullRange: Boolean = false,
) {
    /** Sends a JSON-RPC request (or batch) covering up to [span] blocks; the first node that answers without errors wins. */
    fun post(body: String, span: Long = 0): String {
        val nodes = listOf(rpc) + backups.filter { backupsFullRange || span <= BACKUP_SPAN }
        var last: Exception? = null
        for (url in nodes) {
            try {
                val text = http(url, body).trim()
                val ok = if (text.startsWith("[")) {
                    val arr = JSONArray(text)
                    (0 until arr.length()).none { arr.getJSONObject(it).has("error") }
                } else JSONObject(text).let { !it.has("error") && it.has("result") }
                if (ok) return text
                last = java.io.IOException("HTTP " + text.take(120))
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: java.io.IOException("No node answered")
    }

    companion object {
        const val BACKUP_SPAN = 10_000L
    }
}
private val LOG_NODES = mapOf(
    // BSC: 5,000 blocks per call. publicnode answers big batches cleanly (48.club rate-limits them), so it leads.
    56L to LogNode("https://bsc-rpc.publicnode.com", 5_000, 12, listOf("https://rpc-bsc.48.club"), backupsFullRange = true),
    // Sonic: whole chain in a few calls; the backup only small windows (searching around one payment).
    146L to LogNode("https://rpc.soniclabs.com", 10_000_000, 10, listOf("https://sonic.drpc.org")),
    // Polygon: ~5.5 h per call. Backup takes the same requests.
    137L to LogNode("https://polygon-bor-rpc.publicnode.com", 10_000, 10, listOf("https://polygon.drpc.org"), backupsFullRange = true),
    42161L to LogNode("https://arb1.arbitrum.io/rpc", 1_000_000, 4, listOf("https://arbitrum.drpc.org")), // Arbitrum: ~3 days per call
    8453L to LogNode("https://base-rpc.publicnode.com", 25_000, 4, listOf("https://base.drpc.org")), // Base: ~14 h per call
)

/**
 * Keyless history straight from a public node's event logs. Transfers found are cached per address; each visit
 * scans the blocks since last time, then reaches further back (more when you tap Load more, and in the background).
 */
private fun logHistory(c: EvmHist, a: String): Part {
    // Requests per visit (more after each Load more), and how many transfers to look for.
    val maxRequests = 4 + (LIMIT - 25) / 50 * 4
    val want = LIMIT
    val node = LOG_NODES.getValue(c.chainId)
    val key = "loghist:${c.chainId}:${a.lowercase()}"
    val me = "0x" + a.removePrefix("0x").lowercase().padStart(64, '0')
    fun call(id: Int, method: String, params: JSONArray) =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)

    val cached = historyPrefs?.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    val found = LinkedHashMap<String, JSONObject>() // "hash:logIndex" -> log
    cached?.optJSONArray("logs")?.let { arr -> for (i in 0 until arr.length()) arr.getJSONObject(i).let { found[it.getString("k")] = it } }
    val latest = BigInteger(JSONObject(node.post(call(1, "eth_blockNumber", JSONArray()).toString()))
        .getString("result").removePrefix("0x"), 16).toLong()
    var newest = cached?.optLong("newest", -1L)?.takeIf { it >= 0 } ?: latest
    var oldest = cached?.optLong("oldest", -1L)?.takeIf { it >= 0 } ?: (latest + 1)
    var reachedStart = cached?.optBoolean("start", false) ?: false

    /** Scans block ranges (both directions: sent and received), one request of up to [LogNode.perRequest] ranges. */
    fun scan(ranges: List<LongRange>) {
        val batch = JSONArray()
        ranges.forEachIndexed { i, r ->
            fun filter(topics: JSONArray) = JSONArray().put(JSONObject()
                .put("fromBlock", "0x" + r.first.toString(16)).put("toBlock", "0x" + r.last.toString(16))
                .put("address", c.token).put("topics", topics))
            batch.put(call(i * 2, "eth_getLogs", filter(JSONArray().put(TRANSFER_TOPIC).put(me))))
            batch.put(call(i * 2 + 1, "eth_getLogs", filter(JSONArray().put(TRANSFER_TOPIC).put(JSONObject.NULL).put(me))))
        }
        val text = node.post(batch.toString(), ranges.maxOf { it.last - it.first + 1 })
        // A busy node sometimes answers a batch with a single error object instead of a list.
        if (!text.startsWith("[")) {
            val err = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
            throw java.io.IOException("HTTP " + (err ?: "batch rejected"))
        }
        val resp = JSONArray(text)
        for (i in 0 until resp.length()) {
            val o = resp.getJSONObject(i)
            o.optJSONObject("error")?.let { throw java.io.IOException("HTTP " + it.optString("message")) }
            val r = o.optJSONArray("result") ?: continue
            for (k in 0 until r.length()) {
                val log = r.getJSONObject(k)
                val topics = log.getJSONArray("topics")
                val id = log.getString("transactionHash") + ":" + log.optString("logIndex")
                found[id] = JSONObject().put("k", id).put("h", log.getString("transactionHash"))
                    .put("b", BigInteger(log.getString("blockNumber").removePrefix("0x"), 16).toLong())
                    .put("f", "0x" + topics.getString(1).takeLast(40)).put("t", "0x" + topics.getString(2).takeLast(40))
                    .put("v", log.getString("data").removePrefix("0x").ifEmpty { "0" })
            }
        }
    }
    fun ranges(from: Long, to: Long): List<LongRange> {
        val out = mutableListOf<LongRange>()
        var end = to
        while (end >= from) { val start = maxOf(from, end - node.chunk + 1); out += start..end; end = start - 1 }
        return out
    }

    var requests = 0
    // 1) Blocks since the last visit.
    if (latest > newest) {
        val gap = ranges(newest + 1, latest).chunked(maxOf(1, node.perRequest / 4))
        val filled = gap.size <= 8 && runCatching { gap.forEach { scan(it); requests++ } }.isSuccess
        if (!filled) {
            // Away too long to fill the gap quickly: start again from now (older blocks get scanned again later).
            found.clear(); oldest = latest + 1; reachedStart = false
        }
        newest = latest
    }
    // 2) Further back, a request at a time, until enough transfers are found or the time budget is used.
    // The window starts small (busy wallets have thousands of transfers an hour) and doubles while little is found.
    var size = 1
    var failures = 0
    // About 8 seconds of looking back per visit, so the list shows quickly; Load more and later visits go further.
    val started = System.currentTimeMillis()
    while (!reachedStart && requests < maxRequests && found.size < want && (requests == 0 || System.currentTimeMillis() - started < 8_000)) {
        val part = ranges(maxOf(0L, oldest - node.chunk * size), oldest - 1).take(size)
        if (part.isEmpty()) { reachedStart = true; break }
        val before = found.size
        try { scan(part) } catch (e: Exception) {
            if (size > 1) { size /= 2; continue } // too much at once: try a smaller window
            if (++failures < 2) continue // a busy public node: one retry
            // Some nodes don't keep very old blocks: stop there and keep what was found.
            if (found.isEmpty()) throw e else break
        }
        requests++
        oldest = part.last().first
        if (oldest <= 0L) reachedStart = true
        if (found.size - before < 10) size = minOf(node.perRequest, size * 2)
    }

    // Times for the blocks we show (stored, so they are fetched only once).
    val logs = found.values.sortedByDescending { it.getLong("b") }
    val missing = logs.take(LIMIT).filter { !it.has("ts") }.map { it.getLong("b") }.distinct()
    if (missing.isNotEmpty()) runCatching {
        missing.chunked(20).forEach { part ->
            val batch = JSONArray()
            part.forEachIndexed { i, b -> batch.put(call(i, "eth_getBlockByNumber", JSONArray().put("0x" + b.toString(16)).put(false))) }
            val text = runCatching { node.post(batch.toString()) }.getOrNull() ?: return@forEach
            if (!text.startsWith("[")) return@forEach
            val resp = JSONArray(text)
            val times = HashMap<Long, Long>()
            for (i in 0 until resp.length()) {
                val o = resp.getJSONObject(i)
                val block = o.optJSONObject("result") ?: continue
                times[part[o.getInt("id")]] = BigInteger(block.getString("timestamp").removePrefix("0x"), 16).toLong() * 1000
            }
            logs.forEach { l -> times[l.getLong("b")]?.let { l.put("ts", it) } }
        }
    }
    historyPrefs?.edit()?.putString(key, JSONObject().put("newest", newest).put("oldest", oldest).put("start", reachedStart)
        .put("logs", JSONArray(logs.take(500))).toString())?.apply()

    val items = logs.take(LIMIT).map { l ->
        val from = l.getString("f")
        val to = l.getString("t")
        val incoming = to.equals(a, ignoreCase = true)
        val hash = l.getString("h")
        Transfer(incoming, amount(BigInteger(l.getString("v"), 16), c.decimals), if (incoming) from else to,
            l.optLong("ts", 0L), c.txBase + hash, hash, from, to, c.symbol)
    }
    val since = if (oldest <= latest) runCatching {
        BigInteger(JSONObject(node.post(call(1, "eth_getBlockByNumber", JSONArray().put("0x" + oldest.toString(16)).put(false)).toString()))
            .getJSONObject("result").getString("timestamp").removePrefix("0x"), 16).toLong()
    }.getOrNull() else null
    // Complete back to the oldest block scanned, or (more found than shown) to the oldest one shown.
    val moreFound = logs.size > LIMIT
    val reach = if (moreFound) items.map { it.time }.filter { it > 0 }.minOrNull() ?: 0L else (since ?: 0L) * 1000
    return Part(
        items, bounded = !reachedStart || moreFound, reach = reach, grows = true,
        note = if (reachedStart || since == null) null
        else tr("Checked back to") + " ${date(since)}. " + tr("Load more looks further back."),
    )
}

private fun tronHistory(a: String): History {
    val asked = minOf(LIMIT, 200) // TronGrid's maximum
    val j = JSONObject(http("https://api.trongrid.io/v1/accounts/$a/transactions/trc20?limit=$asked&contract_address=$USDT_TRC20"))
    val arr = j.optJSONArray("data") ?: JSONArray()
    val items = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val incoming = o.getString("to") == a
        Transfer(
            incoming,
            amount(BigInteger(o.getString("value")), 6),
            if (incoming) o.getString("from") else o.getString("to"),
            o.getLong("block_timestamp"),
            "https://tronscan.org/#/transaction/" + o.getString("transaction_id"),
            o.getString("transaction_id"), o.getString("from"), o.getString("to"),
        )
    }
    val usdt = sourcePage(items, arr.length(), asked, items.minOfOrNull { it.time } ?: 0L, 200)
    // Plain TRX transfers too, over the same stretch of time, so coin payments show alongside USDT (tiny amounts
    // are poisoning spam, left out).
    val coins = runCatching { tronCoinPage(a, if (usdt.bounded) usdt.reach else 0L, System.currentTimeMillis()) }.getOrNull()
        ?.let { p -> Part(p.items.filter { it.amount >= java.math.BigDecimal("0.01") }, p.bounded, p.reach, p.grows) }
    return historyOf("Tron", listOfNotNull(usdt, coins))
}

private const val APT_COIN = "0x1::aptos_coin::AptosCoin"
private val APTOS_ARG = Regex("0x[0-9a-fA-F]{1,64}")

/**
 * Aptos's free indexer allows each phone only a little work per few minutes, and Home now checks every few
 * minutes: reuse an answer for 2 minutes (same address and limit), and if Aptos refuses, show the last good one.
 */
private val aptosAnswers = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, History>>()

private suspend fun aptosCached(a: String): History {
    val key = "${a.lowercase()}|$LIMIT"
    val now = System.currentTimeMillis()
    aptosAnswers[key]?.let { (t, h) -> if (now - t < 120_000) return h }
    return try {
        aptosHistory(a).also { aptosAnswers[key] = now to it }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        aptosAnswers[key]?.second ?: throw e
    }
}

/** The Aptos indexer returns at most 100 rows per query. */
private const val APTOS_ROWS = 100

private suspend fun aptosHistory(raw: String): History = coroutineScope {
    val a = "0x" + raw.removePrefix("0x").lowercase().padStart(64, '0')
    // USDT plus APT itself (as the old coin type and as its newer fungible-asset form, 0xa).
    val assets = """["$USDT_APTOS", "$APT_COIN", "0xa"]"""
    val asked = minOf(LIMIT, APTOS_ROWS)
    val query = """
        query { fungible_asset_activities(
            where: { owner_address: { _eq: "$a" }, asset_type: { _in: $assets }, is_transaction_success: { _eq: true } },
            order_by: { transaction_version: desc }, limit: $asked
        ) { amount type asset_type transaction_timestamp transaction_version } }
    """.trimIndent()
    val j = JSONObject(http("https://api.mainnet.aptoslabs.com/v1/graphql", JSONObject().put("query", query).toString()))
    val arr = j.getJSONObject("data").getJSONArray("fungible_asset_activities")
    // Aptos records each side of a transfer separately, so look up the other side (same transaction, other owner).
    // Up to three of these lookups at once (one by one was slow; the free indexer limits each phone's work).
    val gate = Semaphore(3)
    val versions = (0 until arr.length()).map { arr.getJSONObject(it).get("transaction_version").toString() }.distinct()
    val others = HashMap<String, MutableList<Pair<String, String>>>() // version -> (owner, type)
    // A few transactions at a time: the indexer returns at most 100 rows, and an exchange's batch payouts fill that
    // with other people's transfers, which left the newest rows without a counterparty.
    versions.chunked(5).map { chunk ->
        async(Dispatchers.IO) { gate.withPermit { runCatching { aptosOthers(a, chunk, assets) }.getOrDefault(emptyList()) } }
    }.awaitAll().flatten().forEach { (version, owner, type) -> others.getOrPut(version) { mutableListOf() } += owner to type }
    // (row, incoming, version) for the money that moved in or out; gas fees are neither.
    val moves = (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        val type = o.getString("type")
        // "…::Deposit" for fungible assets, "…::DepositEvent" for the old coin type.
        val incoming = type.contains("Deposit")
        if (!incoming && !type.contains("Withdraw")) null else Triple(o, incoming, o.get("transaction_version").toString())
    }
    // Money in came from whoever withdrew in the same transaction; money out went to whoever received it.
    val known = moves.map { (_, incoming, version) ->
        others[version]?.firstOrNull { (_, t) -> if (incoming) t.contains("Withdraw") else t.contains("Deposit") }?.first
    }
    // Still unknown: read the transaction itself (at most 20 of them).
    val missing = moves.filterIndexed { i, _ -> known[i] == null }.map { (_, incoming, version) -> version to incoming }.distinct().take(20)
    val read = missing.map { (version, incoming) ->
        async(Dispatchers.IO) { gate.withPermit { aptosCounterparty(a, version, incoming) } }
    }.awaitAll()
    val fromTransaction = missing.zip(read).toMap()
    val items = moves.mapIndexed { i, (o, incoming, version) ->
        val usdt = o.optString("asset_type") == USDT_APTOS
        val other = known[i] ?: fromTransaction[version to incoming]
        Transfer(
            incoming,
            amount(BigInteger(o.get("amount").toString()), if (usdt) 6 else 8),
            other,
            Instant.parse(o.getString("transaction_timestamp") + "Z").toEpochMilli(),
            "https://explorer.aptoslabs.com/txn/$version?network=mainnet",
            "Version $version", if (incoming) other else a, if (incoming) a else other, if (usdt) "USDT" else "APT",
        )
    }
    val oldest = (0 until arr.length()).mapNotNull { i ->
        runCatching { Instant.parse(arr.getJSONObject(i).getString("transaction_timestamp") + "Z").toEpochMilli() }.getOrNull()
    }.minOrNull() ?: 0L
    historyOf("Aptos", listOf(sourcePage(items, arr.length(), asked, oldest, APTOS_ROWS)))
}

/** The other owners in these Aptos transactions: (version, owner, activity type). */
private fun aptosOthers(a: String, versions: List<String>, assets: String): List<Triple<String, String, String>> {
    val q = """
        query { fungible_asset_activities(
            where: { transaction_version: { _in: [${versions.joinToString(",")}] }, asset_type: { _in: $assets },
                     owner_address: { _neq: "$a" } },
            limit: 100
        ) { owner_address type transaction_version } }
    """.trimIndent()
    val r = JSONObject(http("https://api.mainnet.aptoslabs.com/v1/graphql", JSONObject().put("query", q).toString()))
        .getJSONObject("data").getJSONArray("fungible_asset_activities")
    return (0 until r.length()).mapNotNull { i ->
        val o = r.getJSONObject(i)
        val owner = o.optString("owner_address").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
        Triple(o.get("transaction_version").toString(), owner, o.getString("type"))
    }
}

/** The other side of an Aptos transaction, read from the transaction itself: its sender paid us; a recipient is in its arguments. */
private fun aptosCounterparty(a: String, version: String, incoming: Boolean): String? = runCatching {
    val t = JSONObject(http("https://fullnode.mainnet.aptoslabs.com/v1/transactions/by_version/$version"))
    if (incoming) t.optString("sender").takeIf { it.startsWith("0x") && it != a }
    else {
        val args = t.optJSONObject("payload")?.optJSONArray("arguments") ?: return@runCatching null
        (0 until args.length()).map { args.opt(it) }.filterIsInstance<String>()
            .firstOrNull { APTOS_ARG.matches(it) && it != a && it != USDT_APTOS }
    }
}.getOrNull()

private fun tonHistory(a: String): History {
    val enc = URLEncoder.encode(a, "UTF-8")
    val me = JSONObject(http("https://tonapi.io/v2/accounts/$enc")).getString("address").lowercase()
    fun oldestOf(events: JSONArray) =
        (0 until events.length()).map { events.getJSONObject(it).optLong("timestamp") }.filter { it > 0 }.minOrNull()?.times(1000) ?: 0L
    val askedJettons = minOf(LIMIT, 100)
    val j = JSONObject(http("https://tonapi.io/v2/accounts/$enc/jettons/$USDT_TON/history?limit=$askedJettons"))
    val events = j.getJSONArray("events")
    val items = mutableListOf<Transfer>()
    for (i in 0 until events.length()) {
        val e = events.getJSONObject(i)
        val actions = e.getJSONArray("actions")
        for (k in 0 until actions.length()) {
            val act = actions.getJSONObject(k)
            if (act.optString("status") != "ok") continue // a transfer that failed is listed too
            val t = act.optJSONObject("JettonTransfer") ?: continue
            val sender = t.optJSONObject("sender")?.optString("address")?.lowercase()
            val recipient = t.optJSONObject("recipient")?.optString("address")?.lowercase()
            val incoming = recipient == me
            items += Transfer(
                incoming,
                amount(BigInteger(t.getString("amount")), 6),
                tonFriendly(if (incoming) sender else recipient),
                e.getLong("timestamp") * 1000,
                "https://tonviewer.com/transaction/" + e.getString("event_id"),
                e.getString("event_id"), tonFriendly(sender), tonFriendly(recipient),
            )
        }
    }
    val parts = mutableListOf(sourcePage(items, events.length(), askedJettons, oldestOf(events), 100))
    // TON coin transfers too (from the same free API); under 0.01 TON is the "excess" dust that comes back with
    // every jetton transfer, left out.
    runCatching {
        val askedCoins = minOf(LIMIT, 50)
        val ev = JSONObject(http("https://tonapi.io/v2/accounts/$enc/events?limit=$askedCoins")).getJSONArray("events")
        val coins = mutableListOf<Transfer>()
        for (i in 0 until ev.length()) {
            val e = ev.getJSONObject(i)
            val actions = e.getJSONArray("actions")
            for (k in 0 until actions.length()) {
                val act = actions.getJSONObject(k)
                if (act.optString("status") != "ok") continue
                val t = act.optJSONObject("TonTransfer") ?: continue
                val nano = t.optLong("amount")
                if (nano < 10_000_000L) continue
                val sender = t.optJSONObject("sender")?.optString("address")?.lowercase()
                val recipient = t.optJSONObject("recipient")?.optString("address")?.lowercase()
                val incoming = recipient == me
                coins += Transfer(
                    incoming, amount(BigInteger.valueOf(nano), 9), tonFriendly(if (incoming) sender else recipient),
                    e.getLong("timestamp") * 1000, "https://tonviewer.com/transaction/" + e.getString("event_id"),
                    e.getString("event_id"), tonFriendly(sender), tonFriendly(recipient), "TON",
                )
            }
        }
        parts += sourcePage(coins, ev.length(), askedCoins, oldestOf(ev), 50)
    }
    return historyOf("TON", parts)
}

private val TON_RAW_HEX = Regex("[0-9a-fA-F]{64}")

/** The API gives "0:ab12…"; wallets show the "UQ…" form (non-bounceable, mainnet), so show that. */
internal fun tonFriendly(raw: String?): String? {
    val parts = raw?.split(':') ?: return null
    val wc = parts.getOrNull(0)?.toIntOrNull()
    val hex = parts.getOrNull(1)
    if (parts.size != 2 || wc == null || hex == null || !TON_RAW_HEX.matches(hex)) return raw
    val body = ByteArray(34)
    body[0] = 0x51
    body[1] = wc.toByte()
    for (i in 0 until 32) body[2 + i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    var crc = 0 // CRC-16/XMODEM
    for (b in body) {
        crc = crc xor ((b.toInt() and 0xff) shl 8)
        repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
        crc = crc and 0xffff
    }
    return java.util.Base64.getUrlEncoder().encodeToString(body + byteArrayOf((crc shr 8).toByte(), crc.toByte()))
}

// ===================== Safety check =====================

enum class Level { GOOD, INFO, WARN, BAD }

/** One check's result. [failed]: the check couldn't run (its service didn't answer); [detail] then says why. */
data class Check(val level: Level, val title: String, val detail: String, val failed: Boolean = false)

data class Safety(val checks: List<Check>) {
    val level: Level get() = checks.maxOfOrNull { it.level } ?: Level.INFO
    /** Checks that couldn't run this time; worth a retry. */
    val failed: List<Check> get() = checks.filter { it.failed }
}

/** Why a request failed, in plain words for the person reading it (no raw server text). */
fun failureReason(e: Exception): String {
    val m = e.message.orEmpty()
    val code = HTTP_CODE.find(m)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.NoRouteToHostException ->
            "No internet connection."
        e is java.net.SocketTimeoutException -> "The service took too long to answer."
        e is javax.net.ssl.SSLException -> "Couldn't make a secure connection."
        m.contains("Rate limited") || code == 429 -> "Too many requests. Wait a minute, then retry."
        code != null && code >= 500 -> "The service is down right now."
        code != null -> "The service refused the request."
        e is org.json.JSONException -> "The service sent an answer the app couldn't read."
        else -> "Couldn't reach the service."
    }
}

private val HTTP_CODE = Regex("""HTTP (\d{3})""")

private val GOPLUS_FLAGS = mapOf(
    "cybercrime" to "cybercrime",
    "money_laundering" to "money laundering",
    "financial_crime" to "financial crime",
    "darkweb_transactions" to "dark-web transactions",
    "phishing_activities" to "phishing",
    "fake_kyc" to "fake KYC",
    "blacklist_doubt" to "blacklisted",
    "stealing_attack" to "theft / hacking",
    "blackmail_activities" to "blackmail",
    "sanctioned" to "sanctioned (OFAC)",
    "malicious_mining_activities" to "malicious mining",
    "mixer" to "crypto mixer",
    "honeypot_related_address" to "honeypot scams",
    "fake_token" to "fake tokens",
    "gas_abuse" to "gas abuse",
)

/**
 * Looks for red flags on an address: scam/sanction databases, a Tether freeze on its USDT,
 * whether it is a contract, and whether it has any activity at all. [hist]: its history; [active]: the networks
 * where its balances show funds or activity (the [ChainResult.network] names).
 */
suspend fun safety(raw: String, hist: List<History>, active: Set<String> = emptySet()): Safety = coroutineScope {
    val a = rawAddress(raw)
    val type = detect(a) ?: return@coroutineScope Safety(emptyList())
    val jobs = mutableListOf(
        async(Dispatchers.IO) { scamDatabase(a, type) },
        async(Dispatchers.IO) { tetherFreeze(a, type) },
    )
    if (type == AddrType.EVM) {
        // The network the address is pinned to, else every network it's used on; Ethereum when there's none.
        val networks = (netOf(raw)?.let { setOf(it) } ?: active).filter { n -> EVM_CHAINS.any { it.name == n } }
            .ifEmpty { listOf("Ethereum") }
        jobs += async(Dispatchers.IO) { contractCheck(a, networks) }
    }
    val checks = jobs.awaitAll().filterNotNull().toMutableList()
    activityCheck(hist, active)?.let { checks += it }
    Safety(checks.sortedByDescending { it.level })
}

/** GoPlus answers "code": 1 when the reply is complete; 4029 means too many requests. */
private const val GOPLUS_OK = 1
private const val GOPLUS_RATE_LIMITED = 4029

private fun scamDatabase(a: String, type: AddrType): Check? = try {
    when (type) {
        AddrType.EVM, AddrType.TRON -> {
            val chain = if (type == AddrType.TRON) "tron" else "1"
            val reply = JSONObject(http("https://api.gopluslabs.io/api/v1/address_security/$a?chain_id=$chain"))
            val code = reply.optInt("code", -1)
            if (code != GOPLUS_OK) {
                // No answer, or only part of one: never read as "not on scam lists".
                Check(Level.INFO, "Scam list check failed",
                    if (code == GOPLUS_RATE_LIMITED) "Too many requests. Wait a minute, then retry."
                    else "The scam list couldn't answer right now.",
                    failed = true)
            } else {
                val r = reply.getJSONObject("result")
                val hits = GOPLUS_FLAGS.filterKeys { r.optString(it) == "1" }.values
                val malicious = r.optString("number_of_malicious_contracts_created", "0").toIntOrNull() ?: 0
                val all = hits + (if (malicious > 0) listOf("created $malicious malicious contract(s)") else emptyList())
                if (all.isEmpty()) {
                    Check(Level.GOOD, "Not on scam lists", "No reports found in the GoPlus security database.")
                } else {
                    val src = r.optString("data_source").takeIf { it.isNotBlank() }?.let { " (source: $it)" }.orEmpty()
                    Check(Level.BAD, "Reported as dangerous",
                        tr("Flagged for: %s. Do not send money to this address.").replace("%s", all.joinToString(", ") + src))
                }
            }
        }
        AddrType.TON -> {
            val r = JSONObject(http("https://tonapi.io/v2/accounts/${URLEncoder.encode(a, "UTF-8")}"))
            if (r.optBoolean("is_scam")) Check(Level.BAD, "Marked as scam", "Tonapi lists this address as a scam. Do not send money to it.")
            else Check(Level.GOOD, "Not marked as scam", "No scam mark on Tonapi.")
        }
        AddrType.LIGHTNING, AddrType.BNB_BEACON -> null
        else -> Check(Level.INFO, tr("No scam database for") + " " + type.label,
            tr("Free scam lists don't cover %s yet. Rely on the other checks and only send to people you trust.")
                .replace("%s", type.label))
    }
} catch (e: Exception) {
    Check(Level.INFO, "Scam list check failed", failureReason(e), failed = true)
}

private fun tetherFreeze(a: String, type: AddrType): Check? = try {
    val frozen: Boolean? = when (type) {
        AddrType.EVM -> {
            val data = "0xe47d6060" + a.removePrefix("0x").lowercase().padStart(64, '0')
            val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "eth_call")
                .put("params", JSONArray().put(JSONObject().put("to", "0xdAC17F958D2ee523a2206206994597C13D831ec7").put("data", data)).put("latest"))
            BigInteger(JSONObject(http("https://ethereum-rpc.publicnode.com", body.toString())).getString("result").removePrefix("0x").ifEmpty { "0" }, 16).signum() != 0
        }
        AddrType.TRON -> {
            val body = JSONObject()
                .put("owner_address", a).put("contract_address", USDT_TRC20)
                .put("function_selector", "isBlackListed(address)")
                .put("parameter", tronHex(a).padStart(64, '0')).put("visible", true)
            val res = JSONObject(http("https://api.trongrid.io/wallet/triggerconstantcontract", body.toString()))
                .getJSONArray("constant_result").getString(0)
            BigInteger(res.ifEmpty { "0" }, 16).signum() != 0
        }
        AddrType.APTOS -> {
            val addr = "0x" + a.removePrefix("0x").lowercase().padStart(64, '0')
            val body = JSONObject().put("function", "0x1::primary_fungible_store::is_frozen")
                .put("type_arguments", JSONArray().put("0x1::fungible_asset::Metadata"))
                .put("arguments", JSONArray().put(addr).put(USDT_APTOS))
            JSONArray(http("https://fullnode.mainnet.aptoslabs.com/v1/view", body.toString())).getBoolean(0)
        }
        else -> null
    }
    when (frozen) {
        null -> null
        true -> Check(Level.BAD, "USDT frozen by Tether", "Tether has blacklisted this address. USDT sent here can't be moved again.")
        false -> Check(Level.GOOD, "Not frozen by Tether", "Tether hasn't blacklisted this address.")
    }
} catch (e: Exception) {
    Check(Level.INFO, "Tether freeze check failed", failureReason(e), failed = true)
}

/** What an address's code (eth_getCode) says about it. */
internal enum class CodeKind { NONE, CONTRACT, DELEGATED }

/**
 * No code: a personal wallet. "0xef0100" followed by a 20-byte address: a personal wallet that lets smart-account
 * code act for it (EIP-7702). Anything else: a contract.
 */
internal fun codeKind(code: String): CodeKind {
    val hex = code.trim().lowercase().removePrefix("0x")
    return when {
        hex.all { it == '0' } -> CodeKind.NONE
        hex.length == 6 + 40 && hex.startsWith("ef0100") -> CodeKind.DELEGATED
        else -> CodeKind.CONTRACT
    }
}

/** The code at [a] on an EVM network: eth_getCode on its public nodes, each tried in turn. */
private fun codeAt(network: String, a: String): String {
    val rpcs = EVM_CHAINS.firstOrNull { it.name == network }?.rpcs ?: listOf("https://ethereum-rpc.publicnode.com")
    val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "eth_getCode")
        .put("params", JSONArray().put(a).put("latest")).toString()
    var last: Exception? = null
    for (url in rpcs) {
        try {
            // getString: an error answer (no "result") fails the check instead of reading as "no code".
            val code = JSONObject(http(url, body)).getString("result")
            if (!code.startsWith("0x")) throw org.json.JSONException("Not a code answer")
            return code
        } catch (e: Exception) {
            last = e
        }
    }
    throw last ?: java.io.IOException("No node answered")
}

/** Whether the address holds code on any of [networks] (each asked at once). */
private suspend fun contractCheck(a: String, networks: List<String>): Check? {
    val answers = coroutineScope {
        networks.map { n ->
            async<Pair<CodeKind?, Exception?>>(Dispatchers.IO) {
                try {
                    codeKind(codeAt(n, a)) to null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null to e
                }
            }
        }.awaitAll()
    }
    val kinds = answers.mapNotNull { it.first }
    val failure = answers.firstNotNullOfOrNull { it.second }
    return when {
        CodeKind.CONTRACT in kinds ->
            Check(Level.WARN, "This is a smart contract", "Not a personal wallet. Only send to it if you know exactly what this contract does.")
        failure != null -> Check(Level.INFO, "Contract check failed", failureReason(failure), failed = true)
        CodeKind.DELEGATED in kinds -> Check(Level.INFO, "Smart account", "A personal wallet using smart-account features.")
        else -> null
    }
}

/**
 * Whether the address has been used, from its history [hist] and [active] (the networks where its balances show
 * funds or activity). Several history sources only cover recent days or only some coins, so an empty history only
 * means "new or unused" when the balances show no activity either and the history wasn't cut short.
 */
internal fun activityCheck(hist: List<History>, active: Set<String> = emptySet()): Check? {
    val supported = hist.filter { it.supported }
    if (supported.isEmpty()) {
        // Nothing was looked up because no network shows any activity: unused. Otherwise these networks have no
        // history source, and the balances above show their activity.
        return if (hist.isEmpty() && active.isEmpty()) unusedCheck() else null
    }
    val loaded = supported.filter { it.error == null }
    val all = loaded.flatMap { it.items }
    if (loaded.isEmpty()) {
        // The history's own error, minus its "pull down" hint (the safety card has a Retry button).
        val why = supported.firstNotNullOfOrNull { it.error }?.removeSuffix(" Pull down to retry.") ?: "Couldn't load right now."
        return Check(Level.INFO, "Activity check failed", why, failed = true)
    }
    if (all.isEmpty()) {
        return when {
            active.isNotEmpty() -> Check(Level.INFO, "No recent transfers found",
                "The wallet holds or has used funds, but the history sources showed no recent transfers.")
            // The history stopped at a limit, or says how far back it looked: it can't tell.
            loaded.any { it.canLoadMore || it.note != null } -> Check(Level.INFO, "No recent transfers found",
                "The history sources showed no recent transfers, but they only cover part of the wallet's past.")
            else -> unusedCheck()
        }
    }
    val newest = all.maxOf { it.time }
    return Check(Level.GOOD, "Active wallet",
        tr("%d recent transfers found, latest on %s.").replace("%d", all.size.toString()).replace("%s", date(newest / 1000)))
}

private fun unusedCheck() = Check(Level.WARN, "No stablecoin transfers found",
    "This looks like a new or unused wallet. Double-check every character of the address before sending.")

/** Tron base58 address → 20-byte hex (without the 0x41 prefix). Padded, so odd input can't go out of range. */
internal fun tronHex(address: String): String {
    val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    var n = BigInteger.ZERO
    for (c in address) n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(alphabet.indexOf(c).coerceAtLeast(0).toLong()))
    val raw = n.toByteArray()
    val bytes = (ByteArray(maxOf(0, 25 - raw.size)) + raw).takeLast(25) // 0x41 + 20 address bytes + 4 checksum bytes
    return bytes.subList(1, 21).joinToString("") { "%02x".format(it) }
}

/**
 * The last history loaded for each address (kept in memory and on the phone), so reopening a history shows it at
 * once while the fresh one loads.
 */
object HistoryCache {
    private val memory = HashMap<String, List<History>>()
    private fun key(address: String) = "histcache:" + address.lowercase()

    fun load(address: String): List<History>? = memory[address.lowercase()] ?: runCatching {
        val arr = JSONArray(historyPrefs?.getString(key(address), null) ?: return null)
        (0 until arr.length()).map { i ->
            val h = arr.getJSONObject(i)
            val items = h.getJSONArray("items")
            History(
                h.getString("network"),
                (0 until items.length()).map { k ->
                    val t = items.getJSONObject(k)
                    fun s(name: String) = t.optString(name).ifEmpty { null }
                    Transfer(t.getBoolean("in"), BigDecimal(t.getString("amt")), s("cp"), t.getLong("t"), s("url"),
                        s("hash"), s("from"), s("to"), t.optString("sym", "USDT"))
                },
                note = h.optString("note").ifEmpty { null },
                supported = h.optBoolean("supported", true),
                canLoadMore = h.optBoolean("more", false),
            )
        }.also { memory[address.lowercase()] = it }
    }.getOrNull()

    fun save(address: String, list: List<History>) {
        val ok = list.filter { it.error == null }
        if (ok.isEmpty()) return
        memory[address.lowercase()] = ok
        val arr = JSONArray()
        ok.forEach { h ->
            val items = JSONArray()
            h.items.forEach { t ->
                items.put(JSONObject().put("in", t.incoming).put("amt", t.amount.toPlainString()).put("cp", t.counterparty ?: "")
                    .put("t", t.time).put("url", t.txUrl ?: "").put("hash", t.hash ?: "").put("from", t.from ?: "")
                    .put("to", t.to ?: "").put("sym", t.symbol))
            }
            arr.put(JSONObject().put("network", h.network).put("note", h.note ?: "").put("supported", h.supported)
                .put("more", h.canLoadMore).put("items", items))
        }
        historyPrefs?.edit()?.putString(key(address), arr.toString())?.apply()
    }
}
