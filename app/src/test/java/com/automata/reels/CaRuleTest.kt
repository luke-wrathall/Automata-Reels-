package com.automata.reels

import com.automata.reels.ca.CaRule
import com.automata.reels.ca.RuleKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import kotlin.random.Random

class CaRuleTest {
    @Test
    fun elementaryTableFollowsWolframNumbering() {
        // Rule 30: 111->0 110->0 101->0 100->1 011->1 010->1 001->1 000->0
        assertArrayEquals(intArrayOf(0, 1, 1, 1, 1, 0, 0, 0), CaRule.elementary(30).table)
        assertEquals(BigInteger.valueOf(30), CaRule.elementary(30).code)
        assertEquals("Rule 110", CaRule.elementary(110).title)
    }

    @Test
    fun codeRoundTripsForEveryFamily() {
        val rng = Random(1)
        val families = listOf(
            Triple(RuleKind.TOTALISTIC, 3, 2),
            Triple(RuleKind.GENERAL, 3, 1),
            Triple(RuleKind.GENERAL, 2, 2),
        )
        for ((kind, k, r) in families) {
            repeat(20) {
                val size = CaRule.tableSize(kind, k, r)
                val rule = CaRule(kind, k, r, IntArray(size) { rng.nextInt(k) })
                assertEquals(rule, CaRule.fromCode(kind, k, r, rule.code))
                assertEquals(rule, CaRule.decode(rule.encode()))
            }
        }
    }

    @Test
    fun tableSizes() {
        assertEquals(7, CaRule.tableSize(RuleKind.TOTALISTIC, 3, 1))
        assertEquals(11, CaRule.tableSize(RuleKind.TOTALISTIC, 3, 2))
        assertEquals(27, CaRule.tableSize(RuleKind.GENERAL, 3, 1))
        assertEquals(32, CaRule.tableSize(RuleKind.GENERAL, 2, 2))
    }

    @Test
    fun quiescenceAndLambda() {
        assertTrue(CaRule.elementary(110).isQuiescent)
        assertFalse(CaRule.elementary(1).isQuiescent)
        assertEquals(5.0 / 8, CaRule.elementary(110).lambda, 1e-9)
    }

    @Test
    fun bigGeneralRulesUseHexTitles() {
        val rule = CaRule(RuleKind.GENERAL, 3, 1, IntArray(27) { 2 })
        assertTrue(rule.title, rule.title.startsWith("Rule 0x"))
    }
}
