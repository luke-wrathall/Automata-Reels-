package com.automata.reels

import com.automata.reels.ca.CaRule
import com.automata.reels.ca.CaSimulator
import com.automata.reels.ca.ReelSpec
import com.automata.reels.ca.RuleKind
import com.automata.reels.ca.SeedType
import com.automata.reels.ca.runSpaceTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class SimulatorTest {
    /** Straightforward reference implementation to check the sliding-window simulator against. */
    private fun naiveStep(rule: CaRule, row: IntArray): IntArray {
        val w = row.size
        return IntArray(w) { x ->
            val cells = (-rule.r..rule.r).map { row[Math.floorMod(x + it, w)] }
            if (rule.kind == RuleKind.TOTALISTIC) rule.table[cells.sum()]
            else rule.table[cells.fold(0) { acc, c -> acc * rule.k + c }]
        }
    }

    @Test
    fun matchesNaiveImplementationForRandomRules() {
        val rng = Random(42)
        val families = listOf(
            Triple(RuleKind.ELEMENTARY, 2, 1),
            Triple(RuleKind.TOTALISTIC, 3, 1),
            Triple(RuleKind.TOTALISTIC, 4, 2),
            Triple(RuleKind.TOTALISTIC, 2, 4),
            Triple(RuleKind.GENERAL, 2, 2),
            Triple(RuleKind.GENERAL, 3, 1),
        )
        for ((kind, k, r) in families) repeat(10) {
            val rule = CaRule(kind, k, r, IntArray(CaRule.tableSize(kind, k, r)) { rng.nextInt(k) })
            var expected = IntArray(37) { rng.nextInt(k) }
            val sim = CaSimulator(rule, expected)
            repeat(25) {
                sim.step()
                expected = naiveStep(rule, expected)
                assertArrayEquals("$rule gen ${sim.generation}", expected, sim.current)
            }
        }
    }

    @Test
    fun rule90FromSingleCellIsPascalsTriangleMod2() {
        val spec = ReelSpec(CaRule.elementary(90), SeedType.SINGLE, 0, 101, 0)
        val rows = runSpaceTime(spec, 40)
        val c = 50
        for (t in 0..40) for (x in 0 until 101) {
            val d = x - c
            val expected = if (abs(d) <= t && (t + d) % 2 == 0) binomMod2(t, (t + d) / 2) else 0
            assertEquals("t=$t x=$x", expected, rows[t][x])
        }
    }

    @Test
    fun rule30FirstRows() {
        val rows = runSpaceTime(ReelSpec(CaRule.elementary(30), SeedType.SINGLE, 0, 11, 0), 3)
        fun s(row: IntArray) = row.joinToString("")
        assertEquals("00000100000", s(rows[0]))
        assertEquals("00001110000", s(rows[1]))
        assertEquals("00011001000", s(rows[2]))
        assertEquals("00110111100", s(rows[3]))
    }

    @Test
    fun initialRowIsDeterministicPerSeed() {
        val spec = ReelSpec(CaRule.elementary(110), SeedType.RANDOM, 1234, 151, 0)
        assertArrayEquals(spec.initialRow(), spec.copy().initialRow())
        assertEquals(spec, ReelSpec.decode(spec.encode()))
    }

    private fun binomMod2(n: Int, k: Int) = if ((k and (n - k)) == 0) 1 else 0
}
