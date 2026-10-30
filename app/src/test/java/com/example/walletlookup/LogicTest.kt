package com.example.walletlookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Checks for the logic that's easy to break and hard to notice on screen: matching balance changes to transfers,
 * merging Home's recent list, address conversions, and version comparison. They run before every release build (assembleRelease).
 */
class LogicTest {
    private fun t(incoming: Boolean, amount: String, time: Long, hash: String? = null, url: String? = null, symbol: String = "USDT") =
        Transfer(incoming, BigDecimal(amount), null, time, url, hash, null, null, symbol)

    @Test fun oneTransferOfExactlyTheChange() {
        val picked = pickTransfers(listOf(t(true, "30", 1_000), t(true, "5", 2_000)), BigDecimal("30"), 3_000)
        assertEquals(listOf("30"), picked.map { it.amount.toPlainString() })
    }

    @Test fun combinedChangeSplitsIntoTheRealTransfers() {
        // The real case: +100.0065 in, then −130.0011 out between two checks, seen as −29.9946.
        val inT = t(true, "100.0065", 1_000)
        val outT = t(false, "130.0011", 2_000)
        val picked = pickTransfers(listOf(inT, outT), BigDecimal("-29.9946"), 2_500)
        assertEquals(setOf(inT, outT), picked.toSet())
    }

    @Test fun noMatchGivesNothing() {
        assertTrue(pickTransfers(listOf(t(true, "1", 1_000)), BigDecimal("-7"), 2_000).isEmpty())
    }

    @Test fun mergeKeepsOneCopyPerTransaction() {
        val fromHistory = Triple("w", "BSC", t(true, "10", 1_000, hash = "0xAB", url = "https://bscscan.com/tx/0xAB"))
        val fromAlert = Triple("w", "BSC", t(true, "10", 1_500, hash = "0xab"))
        val merged = mergeRecent(listOf(fromAlert, fromHistory))
        assertEquals(1, merged.size)
        assertEquals("https://bscscan.com/tx/0xAB", merged.single().third.txUrl) // the history copy (with its link) wins
    }

    @Test fun mergeMatchesAlertWithoutIdBySameAmountWithinAnHour() {
        val history = Triple("w", "Tron", t(true, "30", 1_000_000, hash = "h1", url = "u"))
        val alert = Triple("w", "Tron", t(true, "30", 1_000_000 + 30 * 60_000))
        assertEquals(1, mergeRecent(listOf(alert, history)).size)
    }

    @Test fun mergeIsNewestFirstAndAtMost30() {
        val many = (1..40).map { Triple("w", "TON", t(true, "$it", it * 1_000L, hash = "h$it")) }
        val merged = mergeRecent(many)
        assertEquals(30, merged.size)
        assertEquals("40", merged.first().third.amount.toPlainString())
    }

    @Test fun tonRawAddressToUserFriendly() {
        assertEquals("UQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqEBI",
            tonFriendly("0:83dfd552e63729b472fcbcc8c45ebcc6691702558b68ec7527e1ba403a0f31a8"))
    }

    @Test fun tronHexAndBase58RoundTrip() {
        assertEquals("TGSNahJvAdh86aR5pdcxiep1mBWSRK3HHL", tronBase58("4146f3fb8c14006268d153b18000495c8ee858e94b"))
        assertEquals("46f3fb8c14006268d153b18000495c8ee858e94b", tronHex("TGSNahJvAdh86aR5pdcxiep1mBWSRK3HHL"))
    }

    @Test fun detectsEachNetworkFromItsAddress() {
        val samples = mapOf(
            "0x3f5CE5FBFe3E9af3971dD833D26bA9b5C936f0bE" to AddrType.EVM,
            "0x3f5CE5FBFe3E9af3971dD833D26bA9b5C936f0bE@BSC" to AddrType.EVM, // pinned to a network
            "0x357b0b74bc833e95a115ad22604854d6b0fca151cecd94111770e5d6ffc9dc2b" to AddrType.APTOS,
            "TGSNahJvAdh86aR5pdcxiep1mBWSRK3HHL" to AddrType.TRON,
            "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" to AddrType.SOLANA,
            "UQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqEBI" to AddrType.TON,
            "0:83dfd552e63729b472fcbcc8c45ebcc6691702558b68ec7527e1ba403a0f31a8" to AddrType.TON,
            "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq" to AddrType.BITCOIN,
            "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa" to AddrType.BITCOIN,
            "LdP8Qox1VAhCzLJNqrr74YovaWYyNBUWvL" to AddrType.LITECOIN,
            "DH5yaieqoZN36fDVciNyRueRGvGLR3mr7L" to AddrType.DOGECOIN,
            "rEb8TK3gBgk5auZkwc6sHnwrGVJH8DuaLh" to AddrType.XRP,
            "GAHK7EEG2WWHVKDNT4CEQFZGKF2LGDSW2IVM4S5DP42RBW3K6BTODB4A" to AddrType.STELLAR,
            "alice.near" to AddrType.NEAR,
            "eosio.token" to AddrType.EOS,
            "lnbc10u1p3xyz" to AddrType.LIGHTNING,
        )
        samples.forEach { (address, type) -> assertEquals(address, type, detect(address)) }
        assertEquals(null, detect(""))
        assertEquals(null, detect("hello world"))
        assertEquals(null, detect("0x123"))
    }

    @Test fun versionComparison() {
        assertTrue(isNewerVersion("19.10", "19.9"))
        assertTrue(isNewerVersion("20.0", "19.2"))
        assertFalse(isNewerVersion("19.2", "19.2"))
        assertFalse(isNewerVersion("19.1", "19.2"))
    }
}
