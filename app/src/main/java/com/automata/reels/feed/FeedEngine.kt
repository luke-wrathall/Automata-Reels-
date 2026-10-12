package com.automata.reels.feed

import com.automata.reels.ca.CaRule
import com.automata.reels.ca.Interest
import com.automata.reels.ca.InterestReport
import com.automata.reels.ca.ReelSpec
import com.automata.reels.ca.RuleKind
import com.automata.reels.ca.SeedType
import java.util.stream.Collectors
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
 * Each pick first chooses a start type (single cell, random, sparse): their patterns differ so much
 * that scores are only comparable within one start type, and choosing it up front keeps the feed's
 * mix of looks stable. It then draws a pool of random candidates with that start type, measures every
 * one with [Interest.analyze], and samples
 * one with probability proportional to exp(adjusted score / temperature). If nothing in the pool is
 * interesting enough, more candidates are drawn (most of rule space is dull). In elementary mode the
 * space is small, so measured scores are remembered per rule and start type and reused as estimates. Liked reels steer the pool
 * in two ways: some candidates are mutations of liked rules, and candidates whose measured behaviour
 * resembles a liked reel get a bonus. Candidates that behave like something shown very recently get
 * a penalty, so many rules with the same look don't crowd the feed.
 */
class FeedEngine(
    val mode: FeedMode,
    private val rng: Random = Random.Default,
    private val poolSize: Int = if (mode == FeedMode.ELEMENTARY) 16 else 10,
    private val temperature: Double = 0.08,
    private val analyze: (ReelSpec) -> InterestReport = { Interest.analyze(it) },
) {
    /** How many recent picks can't be repeated. Elementary space has few great rules, so it's shorter. */
    val recentMemory = if (mode == FeedMode.ELEMENTARY) 12 else 30
    private val recent = ArrayDeque<String>()
    private val recentReports = ArrayDeque<InterestReport>()
    private val estimates = HashMap<String, InterestReport>()

    fun next(likes: List<ScoredReel> = emptyList()): ScoredReel {
        val relevantLikes = likes.filter { inMode(it.spec) }
        // Some picks explore liked territory: the start type of a liked reel, and a pool made mostly
        // of its mutations. The rest explore freely (the like bonus still applies to them).
        val exploit = relevantLikes.isNotEmpty() && rng.nextDouble() < mutationChance(relevantLikes.size)
        val seedType = if (exploit) relevantLikes[rng.nextInt(relevantLikes.size)].spec.seedType else randomSeedType()
        val parents = if (exploit) relevantLikes.filter { it.spec.seedType == seedType } else emptyList()
        val pool = ArrayList<ScoredReel>()
        for (round in 0 until MAX_ROUNDS) {
            pool += scoreAll(drawCandidates(seedType, parents, exclude = pool.map { it.spec.ruleKey }.toSet()))
            if (pool.isNotEmpty() && pool.maxOf { adjustedScore(it, likes) } >= GOOD_ENOUGH) break
        }
        val finalists = pickFinalists(pool, likes)
        val weights = finalists.map { exp(adjustedScore(it, likes) / temperature) }
        val chosen = finalists[weightedIndex(weights)]
        analyzedThisPick.clear()
        recent.addLast(chosen.spec.ruleKey)
        while (recent.size > recentMemory) recent.removeFirst()
        recentReports.addLast(chosen.report)
        while (recentReports.size > LOOKALIKE_MEMORY) recentReports.removeFirst()
        return chosen
    }

    private val analyzedThisPick = HashMap<ReelSpec, InterestReport>()

    /**
     * The best few candidates, all measured on their own random start (remembered estimates came
     * from other starts, so a finalist can turn out worse; then the next best moves up). Sampling
     * only among finalists stops many dull candidates' small weights adding up to beat good ones.
     */
    private fun pickFinalists(pool: MutableList<ScoredReel>, likes: List<ScoredReel>): List<ScoredReel> {
        while (true) {
            pool.sortByDescending { adjustedScore(it, likes) }
            val top = pool.take(FINALISTS)
            val stale = top.filter { it.report !== analyzedThisPick[it.spec] }
            if (stale.isEmpty()) {
                val best = adjustedScore(top[0], likes)
                return top.filter { adjustedScore(it, likes) >= best - FINALIST_MARGIN }
            }
            val fresh = scoreAll(stale.map { it.spec }, useEstimates = false).associateBy { it.spec }
            pool.replaceAll { fresh[it.spec] ?: it }
        }
    }

    private fun drawCandidates(seedType: SeedType, parents: List<ScoredReel>, exclude: Set<String>): List<ReelSpec> {
        val specs = ArrayList<ReelSpec>(poolSize)
        var attempts = 0
        while (specs.size < poolSize && attempts < poolSize * 4) {
            attempts++
            val spec = if (parents.isNotEmpty() && rng.nextDouble() < EXPLOIT_MUTANT_SHARE) {
                mutate(parents[rng.nextInt(parents.size)].spec).copy(seedType = seedType)
            } else {
                randomSpec(seedType)
            }
            val key = spec.ruleKey
            if (key in recent || key in exclude || specs.any { it.ruleKey == key }) continue
            specs += spec
        }
        return specs
    }

    /**
     * Scores candidates, reusing remembered estimates in elementary mode. Simulations are independent,
     * so they run across cores; candidate generation stays sequential so a seeded Random reproduces.
     */
    private fun scoreAll(specs: List<ReelSpec>, useEstimates: Boolean = mode == FeedMode.ELEMENTARY): List<ScoredReel> {
        val known = if (useEstimates) specs.mapNotNull { s -> estimates[s.ruleKey]?.let { ScoredReel(s, it) } } else emptyList()
        val unknown = specs.filter { s -> known.none { it.spec === s } }
        val measured = unknown.parallelStream().map { ScoredReel(it, analyze(it)) }.collect(Collectors.toList())
        for (m in measured) {
            analyzedThisPick[m.spec] = m.report
            if (mode == FeedMode.ELEMENTARY) estimates[m.spec.ruleKey] = m.report
        }
        return known + measured
    }

    fun inMode(spec: ReelSpec): Boolean =
        (spec.rule.kind == RuleKind.ELEMENTARY) == (mode == FeedMode.ELEMENTARY)

    internal fun mutationChance(likeCount: Int): Double = minOf(0.6, 0.3 + 0.05 * likeCount)

    /** Score after taste and variety adjustments. The lookalike factor scales, so it can't push a good reel below a dull one. */
    internal fun adjustedScore(candidate: ScoredReel, likes: List<ScoredReel>): Double =
        candidate.report.score * (1 - lookalikePenalty(candidate.report)) + preferenceBonus(candidate, likes)

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

    /** Fraction taken off candidates that would look like something shown in the last few reels. */
    internal fun lookalikePenalty(report: InterestReport): Double =
        LOOKALIKE_PENALTY * (recentReports.maxOfOrNull { report.lookSimilarity(it) } ?: 0.0)

    private fun maxSimilarity(f: DoubleArray, others: Collection<DoubleArray>): Double {
        var best = 0.0
        for (g in others) {
            var d2 = 0.0
            for (i in f.indices) d2 += (f[i] - g[i]) * (f[i] - g[i])
            best = maxOf(best, exp(-d2 / SIMILARITY_WIDTH))
        }
        return best
    }

    fun randomSpec(seedType: SeedType = randomSeedType()): ReelSpec {
        val rule = when (mode) {
            FeedMode.ELEMENTARY -> CaRule.elementary(rng.nextInt(256))
            FeedMode.VARIED -> randomVariedRule()
        }
        return ReelSpec(
            rule = rule,
            seedType = seedType,
            seed = rng.nextLong(),
            width = randomWidth(rule.r),
            palette = rng.nextInt(Palettes.all.size),
        )
    }

    /** A nearby rule: one or two table entries changed. Keeps the family and usually the start type. */
    fun mutate(spec: ReelSpec): ReelSpec {
        val old = spec.rule
        val table = old.table.copyOf()
        // Leave entry 0 alone for quiescent rules so the background stays stable.
        val from = if (old.isQuiescent && table.size > 1) 1 else 0
        val positions = (from until table.size).shuffled(rng).take(1 + rng.nextInt(2))
        for (i in positions) table[i] = (table[i] + 1 + rng.nextInt(old.k - 1)) % old.k
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
        const val LIKE_BONUS = 0.2
        const val MAX_ROUNDS = 3
        const val FINALISTS = 3
        const val FINALIST_MARGIN = 0.15
        const val EXPLOIT_MUTANT_SHARE = 0.6
        const val GOOD_ENOUGH = 0.4
        const val SIMILARITY_WIDTH = 0.08
        const val LOOKALIKE_MEMORY = 4
        const val LOOKALIKE_PENALTY = 0.3
    }
}
