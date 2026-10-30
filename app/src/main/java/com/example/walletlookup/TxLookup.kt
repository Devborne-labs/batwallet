package com.example.walletlookup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import java.net.SocketTimeoutException
import java.net.URLEncoder

/** A transaction found by its ID: which network, what moved, and whether it went through. */
data class TxInfo(val network: String, val transfer: Transfer, val status: String)

/**
 * What a search by transaction ID came to. [found] is set when a network has the transaction. Otherwise
 * [unreachable] lists the networks that couldn't be asked (their lookup failed, or hadn't answered by the deadline),
 * and [reason] says why the first of them failed in plain words ([failureReason]); null when every network answered.
 */
data class TxSearch(val found: TxInfo?, val unreachable: List<String>, val reason: String?)

private val TRANSFER = TRANSFER_TOPIC.removePrefix("0x")

/** How long a whole search may take. Networks that haven't answered by then count as unreachable. */
private const val TX_DEADLINE_MS = 45_000L

/** The lookups run here, so an answer shows as soon as one network has it while the others finish quietly. */
private val txScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** One network's lookup: the transaction, null when the network doesn't have it, or an exception when it couldn't be asked. */
internal class TxCheck(val network: String, val find: () -> TxInfo?)

/**
 * Runs [checks] at the same time and answers with the first that finds the transaction, or which networks failed.
 * Networks still running after [deadlineMs] count as unreachable ("The service took too long to answer.").
 */
internal suspend fun searchTx(checks: List<TxCheck>, deadlineMs: Long = TX_DEADLINE_MS): TxSearch {
    val answers = Channel<Pair<TxCheck, Result<TxInfo?>>>(checks.size)
    checks.forEach { c ->
        txScope.launch {
            // find() blocks and never suspends. Anything it throws is kept as that network's failure: left uncaught
            // here it would end the app.
            val r = try {
                Result.success(c.find())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            answers.send(c to r)
        }
    }
    val pending = checks.toMutableList()
    val failures = mutableListOf<Pair<String, Throwable>>()
    // Cancelling the search (the dialog closed) cancels this wait too; only the deadline itself ends in null here.
    val found = withTimeoutOrNull(deadlineMs) {
        var hit: TxInfo? = null
        while (hit == null && pending.isNotEmpty()) {
            val (c, r) = answers.receive()
            pending.remove(c)
            r.fold(onSuccess = { hit = it }, onFailure = { failures += c.network to it })
        }
        hit
    }
    if (found != null) return TxSearch(found, emptyList(), null)
    val first = failures.firstOrNull()?.second
    val reason = when {
        first != null -> failureReason(first as? Exception ?: Exception(first))
        // Still waiting at the deadline: "The service took too long to answer."
        pending.isNotEmpty() -> failureReason(SocketTimeoutException())
        else -> null
    }
    return TxSearch(null, failures.map { it.first } + pending.map { it.network }, reason)
}

/**
 * Finds a transaction by its ID on every network that ID format could belong to. [TxSearch.found] is null when none
 * has it; then [TxSearch.unreachable] names the networks that couldn't be asked, so "not found" is only said for sure
 * when every network answered.
 */
suspend fun lookupTx(raw: String): TxSearch {
    val h = raw.trim()
    // Every EVM network the app shows balances for (from EVM_CHAINS, so a new network is searched too), and Aptos,
    // whose IDs look the same.
    fun evm(hash: String): List<TxCheck> =
        EVM_CHAINS.map { c -> TxCheck(c.name) { evmTx(c, hash) } } + TxCheck("Aptos") { aptosTx(hash) }
    val checks = when {
        HEX64_0X.matches(h) -> evm(h.lowercase())
        // 64 hex characters: Tron, Bitcoin, TON, Litecoin, or an EVM hash shown without its 0x. All asked at once.
        HEX64.matches(h) -> h.lowercase().let { l ->
            listOf(
                TxCheck("Tron") { tronTx(l) },
                TxCheck("Bitcoin") { esploraTx("Bitcoin", "https://mempool.space/api", "BTC", "https://mempool.space/tx/", l) },
                TxCheck("TON") { tonTx(l) },
                TxCheck("Litecoin") { esploraTx("Litecoin", "https://litecoinspace.org/api", "LTC", "https://litecoinspace.org/tx/", l) },
            ) + evm("0x$l")
        }
        SOL_SIGNATURE.matches(h) -> listOf(TxCheck("Solana") { solanaTx(h) })
        else -> return TxSearch(null, emptyList(), null)
    }
    return searchTx(checks)
}

private fun hexNum(s: String?): BigInteger = s?.removePrefix("0x")?.ifEmpty { "0" }?.let { BigInteger(it, 16) } ?: BigInteger.ZERO

/** A GET whose 404 means "no such transaction here" (null), while any other failure throws. */
private fun getUnlessMissing(url: String): String? = try {
    http(url)
} catch (e: IOException) {
    if (e.message?.startsWith("HTTP 404") == true) null else throw e
}

/** Asks each of the network's RPCs in turn until one answers (a node that doesn't have it answers too: null). */
private fun evmTx(c: EvmChain, h: String): TxInfo? {
    var last: Exception? = null
    for (url in c.rpcs) {
        try {
            return evmTxAt(c, url, h)
        } catch (e: Exception) {
            last = e
        }
    }
    throw last ?: IOException("No RPC for ${c.name}")
}

private fun evmTxAt(c: EvmChain, url: String, h: String): TxInfo? {
    fun rpc(method: String, params: JSONArray): JSONObject {
        val r = JSONObject(http(url, JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString()))
        // A refused call is a failure, not "no such transaction".
        r.optJSONObject("error")?.let { throw IOException(it.optString("message")) }
        return r
    }
    val tx = rpc("eth_getTransactionByHash", JSONArray().put(h)).optJSONObject("result") ?: return null
    val receipt = rpc("eth_getTransactionReceipt", JSONArray().put(h)).optJSONObject("result")
    val status = when {
        receipt == null -> "Pending"
        // Receipts from before Byzantium (2017; on Ethereum Classic until Atlantis) have no status: they went through.
        receipt.isNull("status") -> "Confirmed"
        receipt.optString("status") == "0x1" -> "Confirmed"
        else -> "Failed"
    }
    val time = receipt?.optString("blockNumber")?.let { b ->
        runCatching { hexNum(rpc("eth_getBlockByNumber", JSONArray().put(b).put(false)).getJSONObject("result").optString("timestamp")).toLong() * 1000 }
            .getOrNull()
    } ?: 0L
    val txUrl = c.explorer.replace("/address/", "/tx/") + h
    // A stablecoin transfer inside the transaction (this network's own USDT/USDC contracts), if there is one;
    // otherwise the coin it sent.
    val logs = receipt?.optJSONArray("logs") ?: JSONArray()
    for (i in 0 until logs.length()) {
        val l = logs.getJSONObject(i)
        val topics = l.optJSONArray("topics") ?: continue
        if (topics.length() != 3 || topics.getString(0).removePrefix("0x") != TRANSFER) continue
        val token = c.tokens.firstOrNull { it.contract.equals(l.optString("address"), ignoreCase = true) } ?: continue
        val from = "0x" + topics.getString(1).takeLast(40)
        val to = "0x" + topics.getString(2).takeLast(40)
        return TxInfo(c.name, Transfer(true, amount(hexNum(l.optString("data")), token.decimals), null, time, txUrl, h, from, to,
            token.symbol.substringBefore(" (")), status)
    }
    return TxInfo(c.name, Transfer(true, amount(hexNum(tx.optString("value")), 18), null, time, txUrl, h,
        tx.optString("from"), tx.optString("to").takeIf { it.isNotBlank() && it != "null" }, c.sym), status)
}

/** An Aptos address without its 0x and leading zeros, so the short ("0x1") and long forms compare equal. */
private fun aptosAddr(a: String): String = a.trim().lowercase().removePrefix("0x").trimStart('0')

/** A Move name ("0x1::coin::transfer", a coin type) with its address written short, for exact comparisons. */
private fun aptosName(name: String): String {
    val addr = name.substringBefore("::", "")
    if (!addr.startsWith("0x")) return name
    return "0x" + aptosAddr(addr).ifEmpty { "0" } + "::" + name.substringAfter("::")
}

private const val APT_COIN = "0x1::aptos_coin::AptosCoin"

/** Decimals of a token from one of its on-chain resources (null when they can't be read). */
private fun aptosDecimals(account: String, resource: String): Int? = runCatching {
    JSONObject(http("$APTOS_NODE/accounts/$account/resource/" + URLEncoder.encode(resource, "UTF-8")))
        .getJSONObject("data").getInt("decimals")
}.getOrNull()

private fun aptosTx(h: String): TxInfo? {
    val o = JSONObject(getUnlessMissing("$APTOS_NODE/transactions/by_hash/$h") ?: return null)
    val status = when {
        o.optString("type") == "pending_transaction" -> "Pending"
        o.optBoolean("success", true) -> "Confirmed"
        else -> "Failed"
    }
    val time = o.optString("timestamp").toLongOrNull()?.div(1000) ?: 0L
    val p = o.optJSONObject("payload")
    val fn = aptosName(p?.optString("function").orEmpty())
    val args = p?.optJSONArray("arguments") ?: JSONArray()
    val typeArgs = p?.optJSONArray("type_arguments") ?: JSONArray()
    val from = o.optString("sender").takeIf { it.isNotBlank() }
    val url = "https://explorer.aptoslabs.com/txn/$h?network=mainnet"
    fun units(i: Int): BigInteger = args.optString(i).toBigIntegerOrNull() ?: BigInteger.ZERO
    fun transfer(raw: BigInteger, decimals: Int, to: String?, symbol: String) =
        Transfer(true, amount(raw, decimals), null, time, url, h, from, to?.takeIf { it.isNotBlank() }, symbol)
    // Any other token is shown as "Token" (never its own name: anyone can call a token USDT). Its decimals are read
    // from the chain; when they can't be, the amount is shown in the token's smallest units rather than guessed.
    val t = when (fn) {
        // Fungible assets (USDT on Aptos is one): metadata object, recipient, amount.
        "0x1::primary_fungible_store::transfer", "0x1::aptos_account::transfer_fungible_assets" -> {
            val meta = args.optJSONObject(0)?.optString("inner") ?: args.optString(0)
            if (aptosAddr(meta) == aptosAddr(USDT_APTOS)) transfer(units(2), 6, args.optString(1), "USDT")
            else transfer(units(2), aptosDecimals(meta, "0x1::fungible_asset::Metadata") ?: 0, args.optString(1), "Token")
        }
        // APT: recipient, amount.
        "0x1::aptos_account::transfer" -> transfer(units(1), 8, args.optString(0), "APT")
        // A coin given in type_arguments: recipient, amount.
        "0x1::aptos_account::transfer_coins", "0x1::coin::transfer" -> {
            val coin = typeArgs.optString(0)
            if (aptosName(coin) == APT_COIN) transfer(units(1), 8, args.optString(0), "APT")
            else transfer(units(1), aptosDecimals(coin.substringBefore("::"), "0x1::coin::CoinInfo<$coin>") ?: 0,
                args.optString(0), "Token")
        }
        else -> Transfer(true, BigDecimal.ZERO, null, time, url, h, from, null, "APT")
    }
    return TxInfo("Aptos", t, status)
}

/** The USDT contract as Tron's event logs name it: 20 bytes of hex, no 41 prefix. */
private val TRON_USDT_HEX = tronHex(USDT_TRC20)

private fun tronTx(h: String): TxInfo? {
    val body = JSONObject().put("value", h).toString()
    val info = JSONObject(http("https://api.trongrid.io/wallet/gettransactioninfobyid", body))
    val tx = JSONObject(http("https://api.trongrid.io/wallet/gettransactionbyid", body))
    if (!tx.has("txID")) return null
    val url = "https://tronscan.org/#/transaction/$h"
    val status = when {
        !info.has("id") -> "Pending"
        info.optString("result") == "FAILED" -> "Failed"
        info.optJSONObject("receipt")?.optString("result")?.let { it.isNotEmpty() && it != "SUCCESS" } == true -> "Failed"
        else -> "Confirmed"
    }
    val time = info.optLong("blockTimeStamp").takeIf { it > 0 } ?: tx.optJSONObject("raw_data")?.optLong("timestamp") ?: 0L
    val logs = info.optJSONArray("log") ?: JSONArray()
    for (i in 0 until logs.length()) {
        val l = logs.getJSONObject(i)
        val topics = l.optJSONArray("topics") ?: continue
        if (topics.length() != 3 || topics.getString(0) != TRANSFER) continue
        val usdt = l.optString("address").lowercase() == TRON_USDT_HEX
        val from = tronBase58("41" + topics.getString(1).takeLast(40))
        val to = tronBase58("41" + topics.getString(2).takeLast(40))
        return TxInfo("Tron", Transfer(true, amount(hexNum(l.optString("data")), 6), null, time, url, h, from, to,
            if (usdt) "USDT" else "Token"), status)
    }
    val v = tx.optJSONObject("raw_data")?.optJSONArray("contract")?.optJSONObject(0)?.optJSONObject("parameter")?.optJSONObject("value")
    val from = v?.optString("owner_address")?.takeIf { it.isNotBlank() }?.let(::tronBase58)
    val to = v?.optString("to_address")?.takeIf { it.isNotBlank() }?.let(::tronBase58)
    return TxInfo("Tron", Transfer(true, amount(BigInteger.valueOf(v?.optLong("amount") ?: 0L), 6), null, time, url, h, from, to, "TRX"), status)
}

/**
 * The payment among a transaction's outputs (address, amount in satoshis): one going to a saved wallet or contact
 * if there is one, else the biggest that doesn't go back to the sender. On wallets that send change to a fresh
 * address the biggest output can be the change, so a known address is the better sign of the payment.
 */
internal fun paymentOutput(outputs: List<Pair<String?, Long>>, from: String?, saved: List<String>): Pair<String?, Long>? {
    val candidates = outputs.filter { it.first != from }
    val toSaved = candidates.filter { (address, _) -> address != null && saved.any { it.equals(address, ignoreCase = true) } }
    return toSaved.ifEmpty { candidates }.maxByOrNull { it.second }
}

private fun esploraTx(name: String, base: String, sym: String, explorer: String, h: String): TxInfo? {
    val o = JSONObject(getUnlessMissing("$base/tx/$h") ?: return null)
    val st = o.optJSONObject("status")
    val confirmed = st?.optBoolean("confirmed") == true
    val time = st?.optLong("block_time")?.takeIf { it > 0 }?.times(1000) ?: 0L
    val from = o.optJSONArray("vin")?.optJSONObject(0)?.optJSONObject("prevout")?.optString("scriptpubkey_address")
    val vout = o.optJSONArray("vout") ?: JSONArray()
    val outputs = (0 until vout.length()).mapNotNull { vout.optJSONObject(it) }.map { v ->
        v.optString("scriptpubkey_address").takeIf { it.isNotEmpty() } to v.optLong("value")
    }
    val best = paymentOutput(outputs, from, lookalikeSource.map { rawAddress(it.address) })
    return TxInfo(name, Transfer(true, amount(BigInteger.valueOf(best?.second ?: 0L), 8), null, time, explorer + h, h,
        from, best?.first, sym), if (confirmed) "Confirmed" else "Pending")
}

private fun tonTx(h: String): TxInfo? {
    val o = JSONObject(getUnlessMissing("https://tonapi.io/v2/events/${URLEncoder.encode(h, "UTF-8")}") ?: return null)
    val actions = o.optJSONArray("actions") ?: return null
    val time = o.optLong("timestamp") * 1000
    val url = "https://tonviewer.com/transaction/$h"
    val status = if (o.optBoolean("in_progress")) "Pending" else "Confirmed"
    for (i in 0 until actions.length()) {
        val a = actions.getJSONObject(i)
        a.optJSONObject("JettonTransfer")?.let { j ->
            val jet = j.optJSONObject("jetton")
            return TxInfo("TON", Transfer(true, amount(BigInteger(j.optString("amount", "0")), jet?.optInt("decimals", 9) ?: 9), null, time, url, h,
                tonFriendly(j.optJSONObject("sender")?.optString("address")), tonFriendly(j.optJSONObject("recipient")?.optString("address")),
                jet?.optString("symbol")?.replace("₮", "T") ?: "Token"), status)
        }
        a.optJSONObject("TonTransfer")?.let { t ->
            return TxInfo("TON", Transfer(true, amount(BigInteger.valueOf(t.optLong("amount")), 9), null, time, url, h,
                tonFriendly(t.optJSONObject("sender")?.optString("address")), tonFriendly(t.optJSONObject("recipient")?.optString("address")), "TON"), status)
        }
    }
    return TxInfo("TON", Transfer(true, BigDecimal.ZERO, null, time, url, h, null, null, "TON"), status)
}

private fun solanaTx(sig: String): TxInfo? {
    // "confirmed", not the node's default "finalized": a transaction from the last few seconds is found too.
    val r = solanaCall("getTransaction", JSONArray().put(sig)
        .put(JSONObject().put("encoding", "jsonParsed").put("maxSupportedTransactionVersion", 0).put("commitment", "confirmed")))
        .optJSONObject("result") ?: return null
    val meta = r.optJSONObject("meta")
    val status = if (meta?.opt("err").let { it == null || it == JSONObject.NULL }) "Confirmed" else "Failed"
    val time = r.optLong("blockTime") * 1000
    val url = "https://solscan.io/tx/$sig"
    val keys = r.getJSONObject("transaction").getJSONObject("message").optJSONArray("accountKeys") ?: JSONArray()
    fun key(i: Int) = keys.optJSONObject(i)?.optString("pubkey") ?: keys.optString(i)
    // Token account → its owner wallet, so From/To show wallets rather than token accounts.
    val owners = HashMap<String, String>()
    listOf("preTokenBalances", "postTokenBalances").forEach { k ->
        meta?.optJSONArray(k)?.let { arr ->
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                b.optString("owner").takeIf { it.isNotBlank() }?.let { owners[key(b.optInt("accountIndex"))] = it }
            }
        }
    }
    val ins = r.getJSONObject("transaction").getJSONObject("message").optJSONArray("instructions") ?: JSONArray()
    for (i in 0 until ins.length()) {
        val parsed = ins.getJSONObject(i).optJSONObject("parsed") ?: continue
        val info = parsed.optJSONObject("info") ?: continue
        when (parsed.optString("type")) {
            "transferChecked", "transfer" -> if (ins.getJSONObject(i).optString("program") == "spl-token") {
                val amt = info.optJSONObject("tokenAmount")?.optString("uiAmountString")?.let(::BigDecimal)
                    ?: BigDecimal(info.optString("amount", "0")).movePointLeft(6)
                val sym = SOL_STABLES[info.optString("mint")] ?: "Token"
                val from = info.optString("authority").ifBlank { owners[info.optString("source")] ?: info.optString("source") }
                val to = owners[info.optString("destination")] ?: info.optString("destination")
                return TxInfo("Solana", Transfer(true, amt, null, time, url, sig, from, to, sym), status)
            } else {
                return TxInfo("Solana", Transfer(true, amount(BigInteger.valueOf(info.optLong("lamports")), 9), null, time, url, sig,
                    info.optString("source"), info.optString("destination"), "SOL"), status)
            }
        }
    }
    return TxInfo("Solana", Transfer(true, BigDecimal.ZERO, null, time, url, sig, key(0), null, "SOL"), status)
}
