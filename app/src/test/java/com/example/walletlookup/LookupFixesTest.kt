package com.example.walletlookup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

/** Checks for the balance-lookup, transaction-lookup and paste fixes (no network: only the logic around the requests). */
class LookupFixesTest {
    private val tron = "TGSNahJvAdh86aR5pdcxiep1mBWSRK3HHL"
    private val evm = "0x3f5CE5FBFe3E9af3971dD833D26bA9b5C936f0bE"

    @Test fun joinedLinesDontGlueAnAddressToTheNextWord() {
        // "T…HHL" + "thanks" is 40 base58 characters, which would read as a Solana address.
        assertEquals(listOf(tron), extractAddresses("Pay to $tron\nthanks"))
        assertEquals(listOf(tron), extractAddresses("Pay to $tron\nthanks\n"))
    }

    @Test fun anAddressSplitOverTwoLinesIsStillFound() {
        assertEquals(listOf(evm), extractAddresses("${evm.take(22)}\n${evm.drop(22)}"))
        assertEquals(listOf(evm), extractAddresses("Send to:\n${evm.take(22)}\n${evm.drop(22)}"))
    }

    @Test fun tonMasterchainAddressesAreRecognised() {
        val body = "8zMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzM0vF" // 46 characters after the two-letter prefix
        for (prefix in listOf("Ef", "Uf", "kf", "0f", "EQ", "UQ")) assertEquals(prefix, AddrType.TON, detect(prefix + body))
        // The old, wrong prefixes aren't TON addresses.
        for (prefix in listOf("Eu", "Uu", "ku")) assertNotEquals(prefix, AddrType.TON, detect(prefix + body))
    }

    @Test fun eosNoAccountOnlyForAnUnknownAccountError() {
        // The whole answer is read: here the "unknown key" text comes after the first 150 characters.
        val unknown = """{"code":500,"message":"Internal Service Error","error":{"code":0,"name":"exception","what":"unspecified",""" +
            """"details":[{"message":"","file":"http_plugin.cpp","line_number":954,"method":"handle_exception"},""" +
            """{"message":"unknown key (eosio::chain::name): nosuchacct12","file":"abi_serializer.cpp"}]}}"""
        assertTrue(unknown.indexOf("unknown key") > 150)
        assertTrue(isEosUnknownAccount(unknown))
        assertTrue(isEosUnknownAccount("""{"error":{"name":"account_query_exception","what":"Account Query Exception"}}"""))
        assertFalse(isEosUnknownAccount("""{"code":500,"message":"Internal Service Error","error":{"name":"deadline_exception","what":"Transaction took too long"}}"""))
        assertFalse(isEosUnknownAccount("<html>502 Bad Gateway</html>"))
    }

    @Test fun stellarStablecoinsOnlyFromTheirRealIssuers() {
        assertTrue(isStellarStable("USDC", "GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"))
        assertTrue(isStellarStable("USDT", "GCQTGZQQ5G4PTM2GL7CDIFKUBIPEC52BROAQIAPW53XBRJVN6ZJVTG6V"))
        assertFalse(isStellarStable("USDT", "GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"))
        assertFalse(isStellarStable("USDC", "GAHK7EEG2WWHVKDNT4CEQFZGKF2LGDSW2IVM4S5DP42RBW3K6BTODB4A"))
        assertFalse(isStellarStable("XLM", ""))
    }

    @Test fun resultsBuiltBeforePricesArePricedAfterwards() {
        val unpriced = ChainResult("Tron",
            rows = listOf("Total value" to money(null), "USDT (TRC20)" to "5.00", "TRX" to "10 · " + money(null), "Created" to "1 Jan 2024"),
            usdt = BigDecimal("5"), nativeSym = "TRX", native = BigDecimal("10"), usd = null)
        val r = priced(unpriced, mapOf("tron" to 0.25))
        assertEquals(0, BigDecimal("7.5").compareTo(r.usd))
        assertEquals(money(BigDecimal("7.5")), r.rows[0].second)
        assertEquals("10 · " + money(BigDecimal("2.5")), r.rows.first { it.first == "TRX" }.second)
        assertEquals(unpriced.rows.drop(1).filter { it.first != "TRX" }, r.rows.drop(1).filter { it.first != "TRX" })
        // No price: the total stays unknown, failed networks are left alone.
        assertNull(priced(unpriced, emptyMap()).usd)
        val failed = ChainResult("Tron", error = "HTTP 503")
        assertEquals(failed, priced(failed, mapOf("tron" to 0.25)))
    }

    @Test fun bitcoinPaymentPrefersASavedAddressOverTheBiggestOutput() {
        val outs = listOf<Pair<String?, Long>>("sender" to 5L, "change" to 900L, "payee" to 100L, null to 0L)
        assertEquals("change", paymentOutput(outs, "sender", emptyList())?.first)
        assertEquals("payee", paymentOutput(outs, "sender", listOf("PAYEE"))?.first)
        // The sender's own address is never the payment, even when it's saved.
        assertEquals("change", paymentOutput(outs, "sender", listOf("sender"))?.first)
    }

    private fun tx(network: String) =
        TxInfo(network, Transfer(true, BigDecimal.ONE, null, 0L, null, "h", null, null, "USDT"), "Confirmed")

    @Test fun transactionSearchReportsWhatItCouldNotAsk() = runBlocking {
        // Found on one network: that answer, whatever the others did.
        val hit = searchTx(listOf(
            TxCheck("Tron") { throw IOException("HTTP 503 down") },
            TxCheck("BSC") { tx("BSC") },
            TxCheck("Bitcoin") { null },
        ))
        assertEquals("BSC", hit.found?.network)

        // Not found, one network failed: it's named, with the plain reason.
        val miss = searchTx(listOf(TxCheck("Tron") { throw IOException("HTTP 503 down") }, TxCheck("Bitcoin") { null }))
        assertNull(miss.found)
        assertEquals(listOf("Tron"), miss.unreachable)
        assertEquals(failureReason(IOException("HTTP 503 down")), miss.reason)

        // Every network answered "not here": nothing unreachable, no reason.
        val clean = searchTx(listOf(TxCheck("Tron") { null }, TxCheck("Bitcoin") { null }))
        assertEquals(TxSearch(null, emptyList(), null), clean)

        // Still running at the deadline: unreachable, "took too long".
        val slow = searchTx(listOf(TxCheck("TON") { Thread.sleep(3_000); null }, TxCheck("Bitcoin") { null }), deadlineMs = 300)
        assertEquals(listOf("TON"), slow.unreachable)
        assertEquals(failureReason(java.net.SocketTimeoutException()), slow.reason)
    }

    @Test fun platformTextForDisplayKeepsTheEnglishTextForChecks() {
        val likely = Platform("Binance", "Likely a Binance deposit address")
        assertTrue(likely.how.startsWith("Likely"))
        assertEquals("Likely a Binance deposit address", likely.display) // English: the same text
        assertEquals("Known OKX wallet", Platform("OKX", "Known OKX wallet").display)
        assertEquals("Receives withdrawals from Bybit", Platform("Bybit", "Receives withdrawals from Bybit").display)
    }

    @Test fun busyAddressNoteOnlyForBusyAddresses() {
        assertNull(exchangeNote("0x0000000000000000000000000000000000000001", 9_999))
        assertTrue(exchangeNote("0x0000000000000000000000000000000000000001", 10_000)!!.startsWith("Very busy address"))
        // A known exchange wallet gets the exchange card, never this note.
        assertNull(exchangeNote("0x3f5CE5FBFe3E9af3971dD833D26bA9b5C936f0bE", 50_000))
    }
}
