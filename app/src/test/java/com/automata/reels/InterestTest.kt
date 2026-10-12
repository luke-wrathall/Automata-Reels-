package com.automata.reels

import com.automata.reels.ca.CaRule
import com.automata.reels.ca.Interest
import com.automata.reels.ca.ReelSpec
import com.automata.reels.ca.SeedType
import com.automata.reels.ca.runSpaceTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scorer never sees rule numbers, so these tests check that well-known behaviour classes come
 * out in the expected order when measured from simulation alone.
 */
class InterestTest {
    private fun meanScore(rule: Int, seedType: SeedType = SeedType.RANDOM) =
        (0 until 4).map { Interest.analyze(ReelSpec(CaRule.elementary(rule), seedType, 100L + it, 151, 0)).score }.average()

    @Test
    fun deadAndStaticRulesScoreNearZero() {
        for (rule in listOf(0, 8, 32, 128, 204, 255)) {
            val s = meanScore(rule)
            assertTrue("rule $rule scored $s", s < 0.1)
        }
    }

    @Test
    fun complexRulesBeatOrderedAndNoisyOnes() {
        val complex = listOf(110, 54, 137, 193, 124).map { meanScore(it) }.average()
        val ordered = listOf(4, 50, 108, 51, 2, 170).map { meanScore(it) }.average()
        val noisy = listOf(90, 150, 30, 45, 105).map { meanScore(it) }.average()
        assertTrue("complex $complex vs ordered $ordered", complex > ordered + 0.2)
        assertTrue("complex $complex vs noisy $noisy", complex > noisy + 0.1)
    }

    @Test
    fun complexRulesRankInTopQuarterOfAllElementaryRules() {
        val scores = (0..255).associateWith { meanScore(it) }
        val ranked = scores.entries.sortedByDescending { it.value }.map { it.key }
        for (rule in listOf(110, 54, 137, 193, 124)) {
            assertTrue("rule $rule ranked ${ranked.indexOf(rule)}", ranked.indexOf(rule) < 64)
        }
    }

    @Test
    fun nestedPatternFromSingleSeedIsInteresting() {
        assertTrue(meanScore(90, SeedType.SINGLE) > 0.6)
    }

    @Test
    fun periodDetectionFindsShiftsAndCycles() {
        // Rule 170 shifts everything one cell per step: period 1 with a shift.
        val shift = runSpaceTime(ReelSpec(CaRule.elementary(170), SeedType.RANDOM, 3, 64, 0), 50).toList()
        assertEquals(1, Interest.findPeriod(shift, 1).first)
        // Rule 51 inverts every cell each step: period 2.
        val blink = runSpaceTime(ReelSpec(CaRule.elementary(51), SeedType.RANDOM, 3, 64, 0), 50).toList()
        assertEquals(2, Interest.findPeriod(blink, 1).first)
    }

    @Test
    fun multiscaleSeparatesNoiseFromStructure() {
        val noise = Interest.analyze(ReelSpec(CaRule.elementary(150), SeedType.RANDOM, 9, 151, 0)).multiscale
        val nested = Interest.analyze(ReelSpec(CaRule.elementary(90), SeedType.SINGLE, 9, 151, 0)).multiscale
        assertEquals(0.25, noise, 0.05)
        assertTrue("nested $nested", nested > noise + 0.1)
    }

    @Test
    fun scoreIsBounded() {
        for (rule in 0..255 step 7) {
            val s = meanScore(rule)
            assertTrue(s in 0.0..1.0)
        }
    }
}
