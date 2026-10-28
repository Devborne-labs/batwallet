package com.example.walletlookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val fullHistoryUrl: String? = null,
    val error: String? = null,
    /** False when this network has no in-app history (only a note is shown). */
    val supported: Boolean = true,
    /** True when older transfers can still be fetched (Load more). */
    val canLoadMore: Boolean = false,
)

/** Optional free Etherscan API key (Settings). Its free plan covers Ethereum and some networks, not BSC/Base/Polygon/Arbitrum. */
@Volatile var etherscanKey: String = ""

private const val USDT_TRC20_ADDR = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"
private const val USDT_APTOS_META = "0x357b0b74bc833e95a115ad22604854d6b0fca151cecd94111770e5d6ffc9dc2b"
private const val USDT_TON_MASTER = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs"
private const val TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
/** How many transfers to fetch per network; "Load more" raises it. */
@Volatile internal var historyLimit = 25
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
 * Latest stablecoin transfers of an address on each of its networks.
 * [networks] limits EVM lookups to the networks where the wallet actually has activity.
 */
suspend fun history(raw: String, networks: Set<String>? = null, limit: Int = 25): List<History> {
    historyLimit = limit
    return historyRaw(raw, networks).map { h -> h.copy(items = h.items.filterNot(::isDust)) }
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
            // Only ask the networks where the wallet is actually used.
            val used = networks ?: runCatching {
                lookup(a, AddrType.EVM, withPrices = false).filter { it.active && it.error == null }.map { it.network }.toSet()
            }.getOrNull()
            val chains = EVM_HIST.filter { used == null || it.name in used }
            if (chains.isEmpty()) return@withContext listOf(History("EVM", supported = true))
            chains.map { c ->
                async(Dispatchers.IO) {
                    safeHistory(c.name) {
                        // Stablecoin transfers plus the network's own coin (ETH, BNB, …), newest first.
                        val h = evmHistory(c, a)
                        val coin = runCatching {
                            evmNativeTransfers(c.name, c.chainId, c.blockscout, c.routescan, c.txBase, a)
                        }.getOrDefault(emptyList())
                        h.copy(items = (h.items + coin).sortedByDescending { it.time }.take(LIMIT))
                    }
                }
            }.awaitAll()
        }
        AddrType.TRON -> listOf(safeHistory("Tron") { tronHistory(a) })
        AddrType.APTOS -> listOf(safeHistory("Aptos") { aptosHistory(a) })
        AddrType.TON -> listOf(safeHistory("TON") { tonHistory(a) })
        else -> coinHistory(type, a) ?: listOf(History(
            type.label, supported = false,
            note = "History for ${type.label} isn't available in the app yet.",
        ))
    }
}

private inline fun safeHistory(network: String, block: () -> History): History =
    try { block() } catch (e: Exception) { History(network, error = friendlyError(e)) }

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
 */
private fun evmHistory(c: EvmHist, a: String): History {
    val sources = buildList<() -> History> {
        if (etherscanKey.isNotBlank()) add { etherscanHistory(c, a) }
        if (c.routescan) add { routescanHistory(c, a) }
        if (c.blockscout != null) add { blockscoutHistory(c, a) }
        if (c.chainId in LOG_NODES) add { logHistory(c, a) }
    }
    var last: Exception? = null
    for (source in sources) {
        try {
            val h = source()
            if (h.supported || source === sources.last()) return h
        } catch (e: Exception) {
            last = e
        }
    }
    last?.let { throw it }
    return History(c.name, supported = false,
        note = "No free history source for ${c.name}. Payments detected by alerts are listed below, " +
            "or add an Etherscan API key in Settings.")
}

private fun routescanHistory(c: EvmHist, a: String): History {
    val url = "https://api.routescan.io/v2/network/mainnet/evm/${c.chainId}/etherscan/api?module=account&action=tokentx" +
        "&contractaddress=${c.token}&address=$a&page=1&offset=$LIMIT&sort=desc"
    return parseEtherscanStyle(c, a, JSONObject(http(url)))
}

private fun blockscoutHistory(c: EvmHist, a: String): History {
    val j = JSONObject(http("https://${c.blockscout}/api/v2/addresses/$a/token-transfers?type=ERC-20&token=${c.token}"))
    val arr = j.getJSONArray("items")
    val items = (0 until minOf(arr.length(), LIMIT)).map { i ->
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
    return History(c.name, items)
}

private fun etherscanHistory(c: EvmHist, a: String): History {
    val url = "https://api.etherscan.io/v2/api?chainid=${c.chainId}&module=account&action=tokentx" +
        "&contractaddress=${c.token}&address=$a&page=1&offset=$LIMIT&sort=desc&apikey=${URLEncoder.encode(etherscanKey, "UTF-8")}"
    val j = JSONObject(http(url))
    if (j.optString("status") != "1" && !j.optString("message").contains("No transactions", true)) {
        // The key doesn't cover this network (free plan): let the next free source answer.
        return History(c.name, supported = false,
            note = "Your Etherscan key doesn't cover ${c.name} on the free plan.")
    }
    return parseEtherscanStyle(c, a, j)
}

private fun parseEtherscanStyle(c: EvmHist, a: String, j: JSONObject): History {
    if (j.optString("status") != "1") {
        if (j.optString("message").contains("No transactions", true)) return History(c.name)
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
    return History(c.name, items)
}

/** Where history found by scanning a node's event logs is kept (set at start-up), so each visit only scans new blocks. */
@Volatile var historyPrefs: android.content.SharedPreferences? = null

/** Public nodes that answer eth_getLogs without a key: the URL, blocks per call, and calls per request. */
private class LogNode(val rpc: String, val chunk: Long, val perRequest: Int)
private val LOG_NODES = mapOf(
    56L to LogNode("https://rpc-bsc.48.club", 5_000, 12),     // BSC: 5,000 blocks (~40 min) per call, sequential only
    146L to LogNode("https://rpc.soniclabs.com", 10_000_000, 10), // Sonic: whole chain in a few calls
    137L to LogNode("https://polygon-bor-rpc.publicnode.com", 10_000, 10), // Polygon: ~5.5 h per call
    42161L to LogNode("https://arb1.arbitrum.io/rpc", 1_000_000, 4), // Arbitrum: ~3 days per call
    8453L to LogNode("https://base-rpc.publicnode.com", 25_000, 4), // Base: ~14 h per call
)

/**
 * Keyless history straight from a public node's event logs. Transfers found are cached per address; each visit
 * scans the blocks since last time, then reaches further back (more when you tap Load more, and in the background).
 */
private fun logHistory(c: EvmHist, a: String, maxRequests: Int = 4 + (LIMIT - 25) / 50 * 4, want: Int = LIMIT): History {
    val node = LOG_NODES.getValue(c.chainId)
    val key = "loghist:${c.chainId}:${a.lowercase()}"
    val me = "0x" + a.removePrefix("0x").lowercase().padStart(64, '0')
    fun call(id: Int, method: String, params: JSONArray) =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)

    val cached = historyPrefs?.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    val found = LinkedHashMap<String, JSONObject>() // "hash:logIndex" -> log
    cached?.optJSONArray("logs")?.let { arr -> for (i in 0 until arr.length()) arr.getJSONObject(i).let { found[it.getString("k")] = it } }
    val latest = BigInteger(JSONObject(http(node.rpc, call(1, "eth_blockNumber", JSONArray()).toString()))
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
        val text = http(node.rpc, batch.toString()).trim()
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
    while (!reachedStart && requests < maxRequests && found.size < want) {
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
            val text = http(node.rpc, batch.toString()).trim()
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
        BigInteger(JSONObject(http(node.rpc, call(1, "eth_getBlockByNumber", JSONArray().put("0x" + oldest.toString(16)).put(false)).toString()))
            .getJSONObject("result").getString("timestamp").removePrefix("0x"), 16).toLong()
    }.getOrNull() else null
    return History(
        c.name, items,
        note = if (reachedStart || since == null) null
        else "Checked back to ${date(since)}. Load more looks further back.",
        canLoadMore = !reachedStart,
    )
}

private fun tronHistory(a: String): History {
    val j = JSONObject(http("https://api.trongrid.io/v1/accounts/$a/transactions/trc20?limit=$LIMIT&contract_address=$USDT_TRC20_ADDR"))
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
    return History("Tron", items)
}

private fun aptosHistory(raw: String): History {
    val a = "0x" + raw.removePrefix("0x").lowercase().padStart(64, '0')
    val query = """
        query { fungible_asset_activities(
            where: { owner_address: { _eq: "$a" }, asset_type: { _eq: "$USDT_APTOS_META" }, is_transaction_success: { _eq: true } },
            order_by: { transaction_version: desc }, limit: $LIMIT
        ) { amount type transaction_timestamp transaction_version } }
    """.trimIndent()
    val j = JSONObject(http("https://api.mainnet.aptoslabs.com/v1/graphql", JSONObject().put("query", query).toString()))
    val arr = j.getJSONObject("data").getJSONArray("fungible_asset_activities")
    // Aptos records each side of a transfer separately, so look up the other side (same transaction, other owner).
    val versions = (0 until arr.length()).map { arr.getJSONObject(it).get("transaction_version").toString() }.distinct()
    val others = HashMap<String, MutableList<Pair<String, String>>>() // version -> (owner, type)
    if (versions.isNotEmpty()) runCatching {
        val q2 = """
            query { fungible_asset_activities(
                where: { transaction_version: { _in: [${versions.joinToString(",")}] }, asset_type: { _eq: "$USDT_APTOS_META" },
                         owner_address: { _neq: "$a" } }
            ) { owner_address type transaction_version } }
        """.trimIndent()
        val r = JSONObject(http("https://api.mainnet.aptoslabs.com/v1/graphql", JSONObject().put("query", q2).toString()))
            .getJSONObject("data").getJSONArray("fungible_asset_activities")
        for (i in 0 until r.length()) {
            val o = r.getJSONObject(i)
            val owner = o.optString("owner_address").takeIf { it.isNotBlank() && it != "null" } ?: continue
            others.getOrPut(o.get("transaction_version").toString()) { mutableListOf() } += owner to o.getString("type")
        }
    }
    val items = (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        val type = o.getString("type")
        val incoming = type.endsWith("Deposit")
        if (!incoming && !type.endsWith("Withdraw")) return@mapNotNull null
        val version = o.get("transaction_version").toString()
        // Money in came from whoever withdrew in the same transaction; money out went to whoever received it.
        val other = others[version]?.firstOrNull { (_, t) -> if (incoming) t.endsWith("Withdraw") else t.endsWith("Deposit") }?.first
        Transfer(
            incoming,
            amount(BigInteger(o.get("amount").toString()), 6),
            other,
            Instant.parse(o.getString("transaction_timestamp") + "Z").toEpochMilli(),
            "https://explorer.aptoslabs.com/txn/$version?network=mainnet",
            "Version $version", if (incoming) other else a, if (incoming) a else other,
        )
    }
    return History("Aptos", items)
}

private fun tonHistory(a: String): History {
    val enc = URLEncoder.encode(a, "UTF-8")
    val me = JSONObject(http("https://tonapi.io/v2/accounts/$enc")).getString("address").lowercase()
    val j = JSONObject(http("https://tonapi.io/v2/accounts/$enc/jettons/$USDT_TON_MASTER/history?limit=${minOf(LIMIT, 100)}"))
    val events = j.getJSONArray("events")
    val items = mutableListOf<Transfer>()
    for (i in 0 until events.length()) {
        val e = events.getJSONObject(i)
        val actions = e.getJSONArray("actions")
        for (k in 0 until actions.length()) {
            val t = actions.getJSONObject(k).optJSONObject("JettonTransfer") ?: continue
            val sender = t.optJSONObject("sender")?.optString("address")?.lowercase()
            val recipient = t.optJSONObject("recipient")?.optString("address")?.lowercase()
            val incoming = recipient == me
            items += Transfer(
                incoming,
                amount(BigInteger(t.getString("amount")), 6),
                if (incoming) sender else recipient,
                e.getLong("timestamp") * 1000,
                "https://tonviewer.com/transaction/" + e.getString("event_id"),
                e.getString("event_id"), sender, recipient,
            )
        }
    }
    return History("TON", items)
}

// ===================== Safety check =====================

enum class Level { GOOD, INFO, WARN, BAD }

data class Check(val level: Level, val title: String, val detail: String)

data class Safety(val checks: List<Check>) {
    val level: Level get() = checks.maxOfOrNull { it.level } ?: Level.INFO
}

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
 * whether it is a contract, and whether it has any activity at all.
 */
suspend fun safety(raw: String, hist: List<History>): Safety = coroutineScope {
    val a = rawAddress(raw)
    val type = detect(a) ?: return@coroutineScope Safety(emptyList())
    val jobs = mutableListOf(
        async(Dispatchers.IO) { scamDatabase(a, type) },
        async(Dispatchers.IO) { tetherFreeze(a, type) },
    )
    if (type == AddrType.EVM) jobs += async(Dispatchers.IO) { contractCheck(a) }
    val checks = jobs.awaitAll().filterNotNull().toMutableList()
    activityCheck(hist)?.let { checks += it }
    Safety(checks.sortedByDescending { it.level })
}

private fun scamDatabase(a: String, type: AddrType): Check? = try {
    when (type) {
        AddrType.EVM, AddrType.TRON -> {
            val chain = if (type == AddrType.TRON) "tron" else "1"
            val r = JSONObject(http("https://api.gopluslabs.io/api/v1/address_security/$a?chain_id=$chain")).getJSONObject("result")
            val hits = GOPLUS_FLAGS.filterKeys { r.optString(it) == "1" }.values
            val malicious = r.optString("number_of_malicious_contracts_created", "0").toIntOrNull() ?: 0
            val all = hits + (if (malicious > 0) listOf("created $malicious malicious contract(s)") else emptyList())
            if (all.isEmpty()) {
                Check(Level.GOOD, "Not on scam lists", "No reports found in the GoPlus security database.")
            } else {
                val src = r.optString("data_source").takeIf { it.isNotBlank() }?.let { " (source: $it)" }.orEmpty()
                Check(Level.BAD, "Reported as dangerous", "Flagged for: ${all.joinToString(", ")}$src. Do not send money to this address.")
            }
        }
        AddrType.TON -> {
            val r = JSONObject(http("https://tonapi.io/v2/accounts/${URLEncoder.encode(a, "UTF-8")}"))
            if (r.optBoolean("is_scam")) Check(Level.BAD, "Marked as scam", "Tonapi lists this address as a scam. Do not send money to it.")
            else Check(Level.GOOD, "Not marked as scam", "No scam mark on Tonapi.")
        }
        AddrType.LIGHTNING, AddrType.BNB_BEACON -> null
        else -> Check(Level.INFO, "No scam database for ${type.label}",
            "Free scam lists don't cover ${type.label} yet. Rely on the other checks and only send to people you trust.")
    }
} catch (e: Exception) {
    Check(Level.INFO, "Scam database unavailable", "Couldn't reach the scam database right now (${e.message}).")
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
                .put("owner_address", a).put("contract_address", USDT_TRC20_ADDR)
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
                .put("arguments", JSONArray().put(addr).put(USDT_APTOS_META))
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
    null
}

private fun contractCheck(a: String): Check? = try {
    val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "eth_getCode")
        .put("params", JSONArray().put(a).put("latest"))
    val code = JSONObject(http("https://ethereum-rpc.publicnode.com", body.toString())).optString("result")
    if (code.isNotEmpty() && code != "0x") {
        Check(Level.WARN, "This is a smart contract", "Not a personal wallet. Only send to it if you know exactly what this contract does.")
    } else null
} catch (e: Exception) {
    null
}

private fun activityCheck(hist: List<History>): Check? {
    val supported = hist.filter { it.supported }
    if (supported.isEmpty()) return null // no history source for this network; balances above show activity
    val loaded = supported.filter { it.error == null }
    val all = loaded.flatMap { it.items }
    if (loaded.isEmpty()) return Check(Level.INFO, "Activity unknown", "Couldn't load the transaction history.")
    if (all.isEmpty()) {
        return Check(Level.WARN, "No stablecoin transfers found",
            "This looks like a new or unused wallet. Double-check every character of the address before sending.")
    }
    val newest = all.maxOf { it.time }
    return Check(Level.GOOD, "Active wallet", "${all.size} recent transfers found, latest on ${date(newest / 1000)}.")
}

/** Tron base58 address → 20-byte hex (without the 0x41 prefix). Padded, so odd input can't go out of range. */
private fun tronHex(address: String): String {
    val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    var n = BigInteger.ZERO
    for (c in address) n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(alphabet.indexOf(c).coerceAtLeast(0).toLong()))
    val raw = n.toByteArray()
    val bytes = (ByteArray(maxOf(0, 25 - raw.size)) + raw).takeLast(25) // 0x41 + 20 address bytes + 4 checksum bytes
    return bytes.subList(1, 21).joinToString("") { "%02x".format(it) }
}
