package com.example.walletlookup

/**
 * Which exchange or platform an address belongs to.
 *
 * Free label APIs all need paid keys, so this uses two things:
 *  1. a short built-in list of well-known exchange hot/cold wallets, and
 *  2. the address's own history: exchange *deposit* addresses forward ("sweep") what they receive to the
 *     exchange's main wallets, so an address that sends its money to Binance's wallets is very likely a
 *     Binance deposit address.
 */
data class Platform(val name: String, val how: String)

/** Lower-case address → platform. Only wallets publicly and widely labelled by block explorers. */
private val KNOWN: Map<String, String> = buildMap {
    fun add(name: String, vararg addresses: String) = addresses.forEach { put(it.lowercase(), name) }
    add("Binance",
        "0x3f5CE5FBFe3E9af3971dD833D26bA9b5C936f0bE", "0xD551234Ae421e3BCBA99A0Da6d736074f22192FF",
        "0xBE0eB53F46cd790Cd13851d5EFf43D12404d33E8", "0xF977814e90dA44bFA03b6295A0616a897441aceC",
        "0x28C6c06298d514Db089934071355E5743bf21d60", "0x21a31Ee1afC51d94C2eFcCAa2092aD1028285549",
        "0xDFd5293D8e347dFe59E90eFd55b2956a1343963d", "0x56Eddb7aa87536c09CCc2793473599fD21A8b17F",
        "0x9696f59E4d72E237BE84fFD425DCaD154Bf96976", "0x5a52E96BAcdaBb82fd05763E25335261B270Efcb",
        "0x8894E0a0c962CB723c1976a4421c95949bE2D4E3",
        "TV6MuMXfmLbBqPZvBHdwFsDnQeVfnmiuSi", "TJDENsfBJs4RFETt1X1W8wMDc8M5XnJhCe", "TNXoiAJ3dct8Fjg4M9fkLFh9S2v9TXc32G")
    add("Bybit", "0xf89d7b9c864f589bbF53a82105107622B35EaA40", "TU4vEruvZwLLkSfV9bNw12EJTPvNr7Pvaa")
    add("OKX", "0x6cC5F688a315f3dC28A7781717a9A798a59fDA7b")
    add("Coinbase",
        "0x71660c4005BA85c37ccec55d0C4493E66Fe775d3", "0x503828976D22510aad0201ac7EC88293211D23Da",
        "0xA9D1e08C7793af67e9d92fe308d5697FB81d3E43")
    add("Kraken", "0x2910543Af39abA0Cd09dBb2D50200b3E800A63D2")
    add("KuCoin", "0x2B5634C42055806a59e9107ED44D43c426E58258")
    add("Bitfinex", "0x77134cbC06cB00b66F4c7e623D5fdBF6777635EC")
    add("Gemini", "0xd24400ae8BfEBb18cA49Be86258a3C749cf46853")
    add("HTX (Huobi)", "0xab5C66752a9e8167967685F1450532fB96d5d24f")
    add("Gate.io", "0x0D0707963952f2fBA59dD06f2b425ace40b492Fe")
    add("Crypto.com", "0x6262998Ced04146fA42253a5C0AF90CA02dfd2A3")
    add("MEXC", "0x75e89d5979E4f6Fba9F97c104c2F0AFB3F1dcB88")
}

/** Platform name of a known exchange wallet, or null. */
fun knownPlatform(address: String): String? = KNOWN[rawAddress(address).lowercase()]

/** Best guess of the platform behind [address], using its history when it isn't a known wallet itself. */
fun platformOf(address: String, history: List<History>?): Platform? {
    knownPlatform(address)?.let { return Platform(it, "Known $it wallet") }
    val transfers = history.orEmpty().flatMap { it.items }
    // Deposit addresses forward their funds to the exchange's main wallets.
    val sentTo = transfers.filter { !it.incoming }.mapNotNull { t -> t.counterparty?.let { knownPlatform(it) } }
    if (sentTo.isNotEmpty()) {
        val top = sentTo.groupingBy { it }.eachCount().maxBy { it.value }
        val outgoing = transfers.count { !it.incoming }
        if (top.value * 2 >= outgoing) return Platform(top.key, "Likely a ${top.key} deposit address")
    }
    val fromExchange = transfers.filter { it.incoming }.mapNotNull { t -> t.counterparty?.let { knownPlatform(it) } }
    if (fromExchange.isNotEmpty()) {
        val top = fromExchange.groupingBy { it }.eachCount().maxBy { it.value }.key
        return Platform(top, "Receives withdrawals from $top")
    }
    return null
}

/** More sends than this means the address is run by a business or a bot, not a person. */
private const val BUSY_SENDS = 10_000L

/** A very busy address that isn't a known exchange wallet: a business or bot, never one person. */
fun isBusyAddress(address: String, sentTx: Long): Boolean = sentTx >= BUSY_SENDS && knownPlatform(address) == null

/** Exchanges whose deposit addresses are custodial: the exchange pays the network fees, so gas never matters. */
private val EXCHANGES = listOf(
    "binance", "bybit", "okx", "coinbase", "kraken", "kucoin", "bitfinex", "gemini", "htx", "huobi", "gate",
    "crypto.com", "mexc", "bitget", "bingx", "bitmart", "upbit", "wazirx", "coindcx",
)

/** True for a wallet on an exchange: its folder or name mentions one, or it is a known exchange address. */
fun isExchangeWallet(address: String, folder: String = "", name: String = ""): Boolean =
    // Asked many times per screen (totals, rows, folders): remember answers instead of re-running the matching.
    exchangeMemo.getOrPut("$address|$folder|$name") {
        val text = "$folder $name".lowercase()
        knownPlatform(address) != null || EXCHANGE_WORDS.any { it.containsMatchIn(text) }
    }

private val exchangeMemo = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

/** Whole words only, so "Gateway" isn't Gate.io and "Bybit 2" or "gate.io" still count. */
private val EXCHANGE_WORDS = EXCHANGES.map { Regex("(?<![a-z])" + Regex.escape(it) + "(?![a-z])") }
private fun mentionsExchange(text: String, key: String) = EXCHANGE_WORDS[EXCHANGES.indexOf(key)].containsMatchIn(text)

/** A wallet on an exchange holds the exchange's coins, not yours: it stays out of totals and its balance is hidden. */
val SavedAddress.onExchange: Boolean get() = isExchangeWallet(address, group, name)
/**
 * Why this address's balance may not be anyone's real balance: an exchange deposit address (the exchange moves the
 * coins on to its own wallets and keeps the person's balance in its own records), or an exchange's own wallet
 * (many users' coins together). Null for an ordinary wallet.
 */
fun exchangeNote(address: String, platform: Platform?, folder: String = "", name: String = "", sentTx: Long = 0): String? {
    knownPlatform(address)?.let { return tr("%s's own wallet: it holds many users' coins together.").replace("%s", it) }
    // Thousands of sends can't be done by hand (a hardware wallet asks to confirm each one): a business or exchange wallet.
    if (sentTx >= BUSY_SENDS) {
        return tr("Very busy address (%d transactions sent): this is an exchange, company or bot wallet, not one person's.")
            .replace("%d", java.text.NumberFormat.getIntegerInstance().format(sentTx))
    }
    val text = "$folder $name".lowercase()
    val exchange = platform?.takeIf { it.how.startsWith("Likely") }?.name
        ?: EXCHANGES.firstOrNull { mentionsExchange(text, it) }?.let { key ->
            folder.takeIf { it.contains(key, ignoreCase = true) } ?: key.replaceFirstChar { it.uppercase() }
        }
        ?: return null
    return tr("%s deposit address: the exchange moves the coins to its own wallets, so this isn't the account balance on %s.")
        .replace("%s", exchange)
}

/** How sure the app is that an address belongs to an exchange. Only the first two ever show the exchange's badge. */
enum class Attribution {
    /** On the built-in list of publicly labelled exchange wallets. */
    CONFIRMED,
    /** You put it in an exchange folder or named it after one (e.g. your own Binance deposit address). */
    YOUR_LABEL,
    /** Guessed from its history (it forwards its money to an exchange). Never shown as fact. */
    POSSIBLE,
}

/** An address identified as an exchange's: which exchange, how sure, and whether it's the exchange's own big wallet. */
data class ExchangeId(val name: String, val attribution: Attribution, val ownWallet: Boolean)

/** Proper names for exchanges mentioned in a folder or wallet name. */
private val EXCHANGE_NAMES = mapOf(
    "binance" to "Binance", "bybit" to "Bybit", "okx" to "OKX", "coinbase" to "Coinbase", "kraken" to "Kraken",
    "kucoin" to "KuCoin", "bitfinex" to "Bitfinex", "gemini" to "Gemini", "htx" to "HTX", "huobi" to "HTX",
    "gate" to "Gate.io", "crypto.com" to "Crypto.com", "mexc" to "MEXC", "bitget" to "Bitget", "bingx" to "BingX",
    "bitmart" to "BitMart", "upbit" to "Upbit", "wazirx" to "WazirX", "coindcx" to "CoinDCX",
)

fun exchangeIdentity(address: String, platform: Platform?, folder: String = "", name: String = ""): ExchangeId? {
    knownPlatform(address)?.let { return ExchangeId(it, Attribution.CONFIRMED, ownWallet = true) }
    val text = "$folder $name".lowercase()
    EXCHANGES.firstOrNull { mentionsExchange(text, it) }?.let { return ExchangeId(EXCHANGE_NAMES[it] ?: it, Attribution.YOUR_LABEL, ownWallet = false) }
    platform?.takeIf { it.how.startsWith("Likely") }?.let { return ExchangeId(it.name, Attribution.POSSIBLE, ownWallet = false) }
    return null
}
