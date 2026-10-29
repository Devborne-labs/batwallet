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

    @Test fun versionComparison() {
        assertTrue(isNewerVersion("19.10", "19.9"))
        assertTrue(isNewerVersion("20.0", "19.2"))
        assertFalse(isNewerVersion("19.2", "19.2"))
        assertFalse(isNewerVersion("19.1", "19.2"))
    }
}
