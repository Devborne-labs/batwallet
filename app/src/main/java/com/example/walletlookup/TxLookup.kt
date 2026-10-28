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
import java.security.MessageDigest

/** A transaction found by its ID: which network, what moved, and whether it went through. */
data class TxInfo(val network: String, val transfer: Transfer, val status: String)

private const val TRANSFER = "ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"

private class EvmNet(val name: String, val rpc: String, val explorer: String, val coin: String)
private val EVM_TX = listOf(
    EvmNet("Ethereum", "https://ethereum-rpc.publicnode.com", "https://etherscan.io/tx/", "ETH"),
    EvmNet("BSC", "https://bsc-rpc.publicnode.com", "https://bscscan.com/tx/", "BNB"),
    EvmNet("Polygon", "https://polygon-bor-rpc.publicnode.com", "https://polygonscan.com/tx/", "POL"),
    EvmNet("Arbitrum", "https://arb1.arbitrum.io/rpc", "https://arbiscan.io/tx/", "ETH"),
    EvmNet("Optimism", "https://optimism-rpc.publicnode.com", "https://optimistic.etherscan.io/tx/", "ETH"),
    EvmNet("Base", "https://base-rpc.publicnode.com", "https://basescan.org/tx/", "ETH"),
    EvmNet("Avalanche", "https://avalanche-c-chain-rpc.publicnode.com", "https://snowtrace.io/tx/", "AVAX"),
)

/** Stablecoin contracts on the EVM networks above (lower-case address → symbol, decimals). */
private val EVM_STABLES = mapOf(
    "0xdac17f958d2ee523a2206206994597c13d831ec7" to ("USDT" to 6), "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48" to ("USDC" to 6),
    "0x55d398326f99059ff775485246999027b3197955" to ("USDT" to 18), "0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d" to ("USDC" to 18),
    "0xc2132d05d31c914a87c6611c10748aeb04b58e8f" to ("USDT" to 6), "0x3c499c542cef5e3811e1192ce70d8cc03d5c3359" to ("USDC" to 6),
    "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9" to ("USDT" to 6), "0xaf88d065e77c8cc2239327c5edb3a432268e5831" to ("USDC" to 6),
    "0x94b008aa00579c1307b0ef2c499ad98a8ce58e58" to ("USDT" to 6), "0x0b2c639c533813f4aa9d7837caf62653d097ff85" to ("USDC" to 6),
    "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913" to ("USDC" to 6),
    "0x9702230a8ea53601f5cd2dc00fdbc13d4df4a8c7" to ("USDT" to 6), "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e" to ("USDC" to 6),
)

/** Finds a transaction by its ID on every network that ID format could belong to. Null when none has it. */
suspend fun lookupTx(raw: String): TxInfo? = withContext(Dispatchers.IO) {
    val h = raw.trim()
    suspend fun evmAll(hash: String): TxInfo? = coroutineScope {
        (EVM_TX.map { n -> async { runCatching { evmTx(n, hash) }.getOrNull() } } +
            async { runCatching { aptosTx(hash) }.getOrNull() }).awaitAll().firstOrNull { it != null }
    }
    when {
        Regex("^0x[0-9a-fA-F]{64}$").matches(h) -> evmAll(h.lowercase())
        Regex("^[0-9a-fA-F]{64}$").matches(h) ->
            runCatching { tronTx(h.lowercase()) }.getOrNull()
                ?: runCatching { esploraTx("Bitcoin", "https://mempool.space/api", "BTC", "https://mempool.space/tx/", h.lowercase()) }.getOrNull()
                ?: runCatching { tonTx(h.lowercase()) }.getOrNull()
                ?: runCatching { esploraTx("Litecoin", "https://litecoinspace.org/api", "LTC", "https://litecoinspace.org/tx/", h.lowercase()) }.getOrNull()
                ?: evmAll("0x" + h.lowercase()) // some explorers show EVM hashes without the 0x
        Regex("^[1-9A-HJ-NP-Za-km-z]{86,90}$").matches(h) -> runCatching { solanaTx(h) }.getOrNull()
        else -> null
    }
}

private fun hexNum(s: String?): BigInteger = s?.removePrefix("0x")?.ifEmpty { "0" }?.let { BigInteger(it, 16) } ?: BigInteger.ZERO

private fun evmTx(n: EvmNet, h: String): TxInfo? {
    fun rpc(method: String, params: JSONArray) = JSONObject(http(n.rpc,
        JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString()))
    val tx = rpc("eth_getTransactionByHash", JSONArray().put(h)).optJSONObject("result") ?: return null
    val receipt = rpc("eth_getTransactionReceipt", JSONArray().put(h)).optJSONObject("result")
    val status = when {
        receipt == null -> "Pending"
        receipt.optString("status") == "0x1" -> "Confirmed"
        else -> "Failed"
    }
    val time = receipt?.optString("blockNumber")?.let { b ->
        runCatching { hexNum(rpc("eth_getBlockByNumber", JSONArray().put(b).put(false)).getJSONObject("result").optString("timestamp")).toLong() * 1000 }
            .getOrNull()
    } ?: 0L
    // A stablecoin transfer inside the transaction, if there is one; otherwise the coin it sent.
    val logs = receipt?.optJSONArray("logs") ?: JSONArray()
    for (i in 0 until logs.length()) {
        val l = logs.getJSONObject(i)
        val topics = l.optJSONArray("topics") ?: continue
        if (topics.length() != 3 || topics.getString(0).removePrefix("0x") != TRANSFER) continue
        val (sym, dec) = EVM_STABLES[l.optString("address").lowercase()] ?: continue
        val from = "0x" + topics.getString(1).takeLast(40)
        val to = "0x" + topics.getString(2).takeLast(40)
        return TxInfo(n.name, Transfer(true, amount(hexNum(l.optString("data")), dec), null, time, n.explorer + h, h, from, to, sym), status)
    }
    return TxInfo(n.name, Transfer(true, amount(hexNum(tx.optString("value")), 18), null, time, n.explorer + h, h,
        tx.optString("from"), tx.optString("to").takeIf { it.isNotBlank() && it != "null" }, n.coin), status)
}

private const val APTOS_USDT = "0x357b0b74bc833e95a115ad22604854d6b0fca151cecd94111770e5d6ffc9dc2b"

private fun aptosTx(h: String): TxInfo? {
    val o = JSONObject(http("https://fullnode.mainnet.aptoslabs.com/v1/transactions/by_hash/$h"))
    val status = when {
        o.optString("type") == "pending_transaction" -> "Pending"
        o.optBoolean("success", true) -> "Confirmed"
        else -> "Failed"
    }
    val time = o.optString("timestamp").toLongOrNull()?.div(1000) ?: 0L
    val p = o.optJSONObject("payload")
    val fn = p?.optString("function").orEmpty()
    val args = p?.optJSONArray("arguments") ?: JSONArray()
    val from = o.optString("sender").takeIf { it.isNotBlank() }
    val url = "https://explorer.aptoslabs.com/txn/$h?network=mainnet"
    val t = when {
        fn.endsWith("primary_fungible_store::transfer") -> {
            val meta = args.optJSONObject(0)?.optString("inner") ?: args.optString(0)
            val usdt = meta.equals(APTOS_USDT, true)
            Transfer(true, amount(BigInteger(args.optString(2, "0")), if (usdt) 6 else 8), null, time, url, h, from, args.optString(1),
                if (usdt) "USDT" else "Token")
        }
        fn.contains("aptos_account::transfer") || fn.endsWith("coin::transfer") ->
            Transfer(true, amount(BigInteger(args.optString(1, "0")), 8), null, time, url, h, from, args.optString(0), "APT")
        else -> Transfer(true, BigDecimal.ZERO, null, time, url, h, from, null, "APT")
    }
    return TxInfo("Aptos", t, status)
}

private const val TRON_USDT_HEX = "a614f803b6fd780986a42c78ec9c7f77e6ded13c"

/** Tron hex address (41 + 20 bytes) → the usual T… address. */
private fun tronAddress(hex41: String): String {
    val bytes = hex41.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val sha = MessageDigest.getInstance("SHA-256")
    val check = sha.digest(sha.digest(bytes)).take(4).toByteArray()
    val full = bytes + check
    val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    var n = BigInteger(1, full)
    val sb = StringBuilder()
    val b58 = BigInteger.valueOf(58)
    while (n.signum() > 0) { val (q, r) = n.divideAndRemainder(b58); sb.append(alphabet[r.toInt()]); n = q }
    full.takeWhile { it.toInt() == 0 }.forEach { _ -> sb.append('1') }
    return sb.reverse().toString()
}

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
        val from = tronAddress("41" + topics.getString(1).takeLast(40))
        val to = tronAddress("41" + topics.getString(2).takeLast(40))
        return TxInfo("Tron", Transfer(true, amount(hexNum(l.optString("data")), 6), null, time, url, h, from, to,
            if (usdt) "USDT" else "Token"), status)
    }
    val v = tx.optJSONObject("raw_data")?.optJSONArray("contract")?.optJSONObject(0)?.optJSONObject("parameter")?.optJSONObject("value")
    val from = v?.optString("owner_address")?.takeIf { it.isNotBlank() }?.let(::tronAddress)
    val to = v?.optString("to_address")?.takeIf { it.isNotBlank() }?.let(::tronAddress)
    return TxInfo("Tron", Transfer(true, amount(BigInteger.valueOf(v?.optLong("amount") ?: 0L), 6), null, time, url, h, from, to, "TRX"), status)
}

private fun esploraTx(name: String, base: String, sym: String, explorer: String, h: String): TxInfo? {
    val o = JSONObject(http("$base/tx/$h"))
    val st = o.optJSONObject("status")
    val confirmed = st?.optBoolean("confirmed") == true
    val time = st?.optLong("block_time")?.takeIf { it > 0 }?.times(1000) ?: 0L
    val from = o.optJSONArray("vin")?.optJSONObject(0)?.optJSONObject("prevout")?.optString("scriptpubkey_address")
    // The payment is the biggest output that doesn't go back to the sender (the rest is change).
    val outs = o.optJSONArray("vout") ?: JSONArray()
    var best: JSONObject? = null
    for (i in 0 until outs.length()) {
        val v = outs.getJSONObject(i)
        if (v.optString("scriptpubkey_address") == from) continue
        if (best == null || v.optLong("value") > best.optLong("value")) best = v
    }
    return TxInfo(name, Transfer(true, amount(BigInteger.valueOf(best?.optLong("value") ?: 0L), 8), null, time, explorer + h, h,
        from, best?.optString("scriptpubkey_address"), sym), if (confirmed) "Confirmed" else "Pending")
}

private fun tonTx(h: String): TxInfo? {
    val o = JSONObject(http("https://tonapi.io/v2/events/${URLEncoder.encode(h, "UTF-8")}"))
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

private val SOL_MINTS = mapOf(
    "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" to "USDT",
    "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to "USDC",
)

private fun solanaTx(sig: String): TxInfo? {
    val r = solanaCall("getTransaction", JSONArray().put(sig)
        .put(JSONObject().put("encoding", "jsonParsed").put("maxSupportedTransactionVersion", 0))).optJSONObject("result") ?: return null
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
                val sym = SOL_MINTS[info.optString("mint")] ?: "Token"
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

