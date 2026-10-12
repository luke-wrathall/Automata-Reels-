package com.automata.reels.feed

import com.automata.reels.ca.CaRule
import com.automata.reels.ca.Interest
import com.automata.reels.ca.InterestReport
import com.automata.reels.ca.ReelSpec
import com.automata.reels.ca.RuleKind
import com.automata.reels.ca.SeedType
import kotlin.math.exp
import kotlin.random.Random

enum class FeedMode(val label: String) {
    ELEMENTARY("Elementary"),
    VARIED("Varied"),
}

class ScoredReel(val spec: ReelSpec, val report: InterestReport)

/**
 * Produces an endless stream of reels for one [mode].
 *
 * Each pick draws a pool of random candidates, measures every one with [Interest.analyze], and samples
 * one with probability proportional to exp(adjusted score / temperature). Liked reels steer the pool
 * in two ways: some candidates are mutations of liked rules, and candidates whose measured behaviour
 * resembles a liked reel get a bonus. Candidates that behave like something shown very recently get
 * a penalty, so many rules with the same look don't crowd the feed.
 */
class FeedEngine(
    val mode: FeedMode,
    private val rng: Random = Random.Default,
    private val poolSize: Int = 10,
    private val temperature: Double = 0.08,
    private val analyze: (ReelSpec) -> InterestReport = { Interest.analyze(it) },
) {
    private val recent = ArrayDeque<String>()
    private val recentFeatures = ArrayDeque<DoubleArray>()

    fun next(likes: List<ScoredReel> = emptyList()): ScoredReel {
        val relevantLikes = likes.filter { inMode(it.spec) }
        val pool = ArrayList<ScoredReel>(poolSize)
        var attempts = 0
        while (pool.size < poolSize && attempts < poolSize * 4) {
            attempts++
            val spec = if (relevantLikes.isNotEmpty() && rng.nextDouble() < mutationChance(relevantLikes.size)) {
                mutate(relevantLikes[rng.nextInt(relevantLikes.size)].spec)
            } else {
                randomSpec()
            }
            if (spec.ruleKey in recent) continue
            pool += ScoredReel(spec, analyze(spec))
        }
        val weights = pool.map { exp(adjustedScore(it, likes) / temperature) }
        val chosen = pool[weightedIndex(weights)]
        recent.addLast(chosen.spec.ruleKey)
        while (recent.size > RECENT_MEMORY) recent.removeFirst()
        recentFeatures.addLast(chosen.report.features())
        while (recentFeatures.size > LOOKALIKE_MEMORY) recentFeatures.removeFirst()
        return chosen
    }

    fun inMode(spec: ReelSpec): Boolean =
        (spec.rule.kind == RuleKind.ELEMENTARY) == (mode == FeedMode.ELEMENTARY)

    internal fun mutationChance(likeCount: Int): Double = minOf(0.5, 0.2 + 0.05 * likeCount)

    internal fun adjustedScore(candidate: ScoredReel, likes: List<ScoredReel>): Double =
        candidate.report.score + preferenceBonus(candidate, likes) - lookalikePenalty(candidate.report)

    /**
     * Bonus for candidates similar to something the user liked: either behaving alike (measured
     * features, any mode) or being a close relative in rule space (same family, few table changes).
     */
    internal fun preferenceBonus(candidate: ScoredReel, likes: List<ScoredReel>): Double {
        if (likes.isEmpty()) return 0.0
        val behaviour = maxSimilarity(candidate.report.features(), likes.map { it.report.features() })
        val kinship = likes.maxOf { ruleSimilarity(candidate.spec.rule, it.spec.rule) }
        return LIKE_BONUS * maxOf(behaviour, kinship)
    }

    /** 1 for identical tables, falling to 0 once half the table differs; 0 across families. */
    internal fun ruleSimilarity(a: CaRule, b: CaRule): Double {
        if (a.kind != b.kind || a.k != b.k || a.r != b.r) return 0.0
        val diff = a.table.indices.count { a.table[it] != b.table[it] }
        val x = maxOf(0.0, 1.0 - 2.0 * diff / a.table.size)
        return x * x
    }

    /** Penalty for candidates that behave like something shown in the last few reels. */
    internal fun lookalikePenalty(report: InterestReport): Double =
        LOOKALIKE_PENALTY * maxSimilarity(report.features(), recentFeatures)

    private fun maxSimilarity(f: DoubleArray, others: Collection<DoubleArray>): Double {
        var best = 0.0
        for (g in others) {
            var d2 = 0.0
            for (i in f.indices) d2 += (f[i] - g[i]) * (f[i] - g[i])
            best = maxOf(best, exp(-d2 / SIMILARITY_WIDTH))
        }
        return best
    }

    fun randomSpec(): ReelSpec {
        val rule = when (mode) {
            FeedMode.ELEMENTARY -> CaRule.elementary(rng.nextInt(256))
            FeedMode.VARIED -> randomVariedRule()
        }
        return ReelSpec(
            rule = rule,
            seedType = randomSeedType(),
            seed = rng.nextLong(),
            width = randomWidth(rule.r),
            palette = rng.nextInt(Palettes.all.size),
        )
    }

    /** A nearby rule: one or two table entries changed. Keeps the family and usually the start type. */
    fun mutate(spec: ReelSpec): ReelSpec {
        val old = spec.rule
        val table = old.table.copyOf()
        val changes = 1 + rng.nextInt(2)
        repeat(changes) {
            // Leave entry 0 alone for quiescent rules so the background stays stable.
            val from = if (old.isQuiescent && table.size > 1) 1 else 0
            val i = from + rng.nextInt(table.size - from)
            table[i] = (table[i] + 1 + rng.nextInt(old.k - 1)) % old.k
        }
        val rule = CaRule(old.kind, old.k, old.r, table)
        return spec.copy(
            rule = rule,
            seedType = if (rng.nextDouble() < 0.7) spec.seedType else randomSeedType(),
            seed = rng.nextLong(),
        )
    }

    private fun randomSeedType(): SeedType {
        val x = rng.nextDouble()
        return when {
            x < 0.5 -> SeedType.RANDOM
            x < 0.85 -> SeedType.SINGLE
            else -> SeedType.SPARSE
        }
    }

    private fun randomWidth(r: Int): Int {
        val widths = if (r == 1) intArrayOf(121, 151, 181) else intArrayOf(151, 181, 211)
        return widths[rng.nextInt(widths.size)]
    }

    private class Family(val kind: RuleKind, val k: Int, val r: Int, val weight: Double)

    private val variedFamilies = listOf(
        Family(RuleKind.TOTALISTIC, 3, 1, 2.0),
        Family(RuleKind.TOTALISTIC, 3, 2, 2.0),
        Family(RuleKind.TOTALISTIC, 4, 1, 1.0),
        Family(RuleKind.TOTALISTIC, 4, 2, 1.0),
        Family(RuleKind.TOTALISTIC, 5, 1, 0.5),
        Family(RuleKind.TOTALISTIC, 2, 2, 0.7),
        Family(RuleKind.TOTALISTIC, 2, 3, 0.7),
        Family(RuleKind.TOTALISTIC, 2, 4, 0.5),
        Family(RuleKind.GENERAL, 2, 2, 2.0),
        Family(RuleKind.GENERAL, 3, 1, 2.0),
    )

    /**
     * Random rule from the varied families. Tables are drawn with a random Langton lambda (share of
     * non-zero outputs) so the pool spans ordered through chaotic behaviour; scoring picks among them.
     */
    private fun randomVariedRule(): CaRule {
        val fam = variedFamilies[weightedIndex(variedFamilies.map { it.weight })]
        val size = CaRule.tableSize(fam.kind, fam.k, fam.r)
        val lambda = 0.1 + rng.nextDouble() * 0.65
        val quiescent = rng.nextDouble() < 0.9
        val table = IntArray(size) { i ->
            if (i == 0 && quiescent) 0
            else if (rng.nextDouble() < lambda) 1 + rng.nextInt(fam.k - 1)
            else 0
        }
        return CaRule(fam.kind, fam.k, fam.r, table)
    }

    private fun weightedIndex(weights: List<Double>): Int {
        val total = weights.sum()
        if (!(total > 0.0) || total.isInfinite()) return weights.indices.maxByOrNull { weights[it] } ?: 0
        var x = rng.nextDouble() * total
        for (i in weights.indices) {
            x -= weights[i]
            if (x <= 0) return i
        }
        return weights.size - 1
    }

    companion object {
        const val RECENT_MEMORY = 30
        const val LIKE_BONUS = 0.15
        const val SIMILARITY_WIDTH = 0.08
        const val LOOKALIKE_MEMORY = 6
        const val LOOKALIKE_PENALTY = 0.2
    }
}
