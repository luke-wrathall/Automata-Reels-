package com.automata.reels

import com.automata.reels.ca.CaRule
import com.automata.reels.ca.Interest
import com.automata.reels.ca.ReelSpec
import com.automata.reels.ca.RuleKind
import com.automata.reels.ca.SeedType
import com.automata.reels.data.FavoritesCodec
import com.automata.reels.feed.FeedEngine
import com.automata.reels.feed.FeedMode
import com.automata.reels.feed.ScoredReel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FeedEngineTest {
    @Test
    fun elementaryModeOnlyServesElementaryRules() {
        val engine = FeedEngine(FeedMode.ELEMENTARY, Random(1))
        repeat(15) { assertEquals(RuleKind.ELEMENTARY, engine.next().spec.rule.kind) }
    }

    @Test
    fun variedModeServesMultipleFamiliesAndNoElementaryRules() {
        val engine = FeedEngine(FeedMode.VARIED, Random(2))
        val picks = (0 until 30).map { engine.next().spec.rule }
        assertTrue(picks.none { it.kind == RuleKind.ELEMENTARY })
        val families = picks.map { it.familyLabel }.toSet()
        assertTrue("families: $families", families.size >= 4)
    }

    @Test
    fun picksScoreHigherThanRandomCandidates() {
        for (mode in FeedMode.entries) {
            val engine = FeedEngine(mode, Random(3))
            val picked = (0 until 25).map { engine.next().report.score }.average()
            val sampler = FeedEngine(mode, Random(4))
            val random = (0 until 60).map { Interest.analyze(sampler.randomSpec()).score }.average()
            assertTrue("$mode picked $picked vs random $random", picked > random + 0.15)
        }
    }

    @Test
    fun feedHasNoDullReelsAndAMixOfStartTypes() {
        for (mode in FeedMode.entries) {
            val engine = FeedEngine(mode, Random(8))
            val picks = (0 until 40).map { engine.next() }
            val worst = picks.minOf { it.report.score }
            assertTrue("$mode worst pick scored $worst", worst >= 0.2)
            val single = picks.count { it.spec.seedType == SeedType.SINGLE }
            assertTrue("$mode single-cell picks: $single/40", single in 6..24)
        }
    }

    @Test
    fun noRuleRepeatsWithinRecentMemory() {
        val engine = FeedEngine(FeedMode.ELEMENTARY, Random(5))
        val keys = (0 until engine.recentMemory).map { engine.next().spec.ruleKey }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun likesPullTheFeedTowardSimilarRules() {
        val liked = ReelSpec(CaRule.elementary(110), SeedType.RANDOM, 1, 151, 0)
        val likes = listOf(ScoredReel(liked, Interest.analyze(liked)))
        fun nearLiked(seed: Int, withLikes: Boolean): Int {
            val engine = FeedEngine(FeedMode.ELEMENTARY, Random(seed))
            return (0 until 40).count {
                val rule = engine.next(if (withLikes) likes else emptyList()).spec.rule
                rule.table.indices.count { i -> rule.table[i] != liked.rule.table[i] } <= 2
            }
        }
        val without = (1..3).sumOf { nearLiked(it, false) }
        val with = (1..3).sumOf { nearLiked(it, true) }
        // Most rules near a liked one are dull, and the feed shouldn't serve those just for being
        // related, so the pull from a single like is bounded (about 2x in practice).
        assertTrue("near-liked picks: without=$without with=$with", with >= without * 1.5 && with - without >= 15)
    }

    @Test
    fun mutationKeepsFamilyAndQuiescence() {
        val engine = FeedEngine(FeedMode.VARIED, Random(6))
        repeat(50) {
            val spec = engine.randomSpec()
            val m = engine.mutate(spec)
            assertEquals(spec.rule.kind, m.rule.kind)
            assertEquals(spec.rule.k, m.rule.k)
            assertEquals(spec.rule.r, m.rule.r)
            if (spec.rule.isQuiescent) assertTrue(m.rule.isQuiescent)
            val diff = spec.rule.table.indices.count { spec.rule.table[it] != m.rule.table[it] }
            assertTrue(diff in 1..2)
        }
    }

    @Test
    fun favoritesCodecRoundTripsAndSkipsGarbage() {
        val engine = FeedEngine(FeedMode.VARIED, Random(7))
        val specs = (0 until 5).map { engine.randomSpec() } +
            ReelSpec(CaRule.elementary(30), SeedType.SINGLE, -5, 121, 3)
        assertEquals(specs, FavoritesCodec.decode(FavoritesCodec.encode(specs)))
        val corrupted = FavoritesCodec.encode(specs) + "\nnot a spec\nGENERAL:2:2:01|RANDOM|1|151|0"
        assertEquals(specs, FavoritesCodec.decode(corrupted))
        assertEquals(emptyList<ReelSpec>(), FavoritesCodec.decode(null))
    }
}
