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

/** Exchanges whose deposit addresses are custodial: the exchange pays the network fees, so gas never matters. */
private val EXCHANGES = listOf(
    "binance", "bybit", "okx", "coinbase", "kraken", "kucoin", "bitfinex", "gemini", "htx", "huobi", "gate",
    "crypto.com", "mexc", "bitget", "bingx", "bitmart", "upbit", "wazirx", "coindcx",
)

/** True for a wallet on an exchange: its folder or name mentions one, or it is a known exchange address. */
fun isExchangeWallet(address: String, folder: String = "", name: String = ""): Boolean {
    val text = "$folder $name".lowercase()
    return knownPlatform(address) != null || EXCHANGES.any { it in text }
}