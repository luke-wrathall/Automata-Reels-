package com.automata.reels.ca

import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Measurements of a reel's space-time diagram, and the interest score derived from them.
 *
 * Nothing here knows about particular rules: every number comes from simulating the reel.
 */
data class InterestReport(
    /** Overall interest in [0, 1]. */
    val score: Double,
    /**
     * Compressed size of the diagram divided by the compressed size of the same cells shuffled.
     * ~0 for regular patterns, ~1 for noise; structured-but-irregular patterns sit in between.
     */
    val compression: Double,
    /**
     * Share of the diagram's variation that survives coarse-graining past the finest scale.
     * ~0.25 for noise, ~0 for fine stripes, high when there is structure at many scales (nested
     * triangles, gliders against a background, large ordered regions next to chaotic ones).
     */
    val multiscale: Double,
    /** Mean normalised entropy of the rule-table entries used per generation. */
    val inputEntropyMean: Double,
    /** Standard deviation over time of that entropy (high for rules with interacting structures). */
    val inputEntropyStd: Double,
    /** How far a single-cell perturbation spreads (fraction of the ring that ends up different). */
    val damage: Double,
    /** Mean fraction of cells that change between consecutive generations. */
    val changeRate: Double,
    /** Mean fraction of non-background cells. */
    val density: Double,
    /** True if the pattern dies out to a uniform row. */
    val dies: Boolean,
    /** Temporal period of the final behaviour (0 if none found). */
    val period: Int,
    /** Generation at which the periodic behaviour sets in. */
    val transient: Int,
    /**
     * Coarse picture of the diagram: share of non-background cells in each block of a
     * [SILHOUETTE_SIZE] x [SILHOUETTE_SIZE] grid over time x space. Used to tell reels that would
     * look alike on screen (e.g. many rules all drawing nested triangles) apart from ones that don't.
     */
    val silhouette: DoubleArray = DoubleArray(SILHOUETTE_SIZE * SILHOUETTE_SIZE),
) {
    /** 1 when two reels would look about the same on screen, falling towards 0 as they differ. */
    fun lookSimilarity(other: InterestReport): Double {
        var d = 0.0
        for (i in silhouette.indices) d += abs(silhouette[i] - other.silhouette[i])
        d /= silhouette.size
        return exp(-sq(d / 0.12))
    }

    /** Feature vector used to compare reels with each other (for like-based tuning). */
    fun features(): DoubleArray = doubleArrayOf(
        compression, multiscale, inputEntropyMean, inputEntropyStd * 4, damage, changeRate, density,
    )
}

const val SILHOUETTE_SIZE = 4

private fun sq(x: Double) = x * x

object Interest {
    const val DEFAULT_STEPS = 360

    fun analyze(spec: ReelSpec, steps: Int = DEFAULT_STEPS): InterestReport {
        val rule = spec.rule
        val w = spec.width
        val sim = CaSimulator(rule, spec.initialRow())
        val rows = ArrayList<IntArray>(steps + 1)
        rows += sim.current.copyOf()

        val tableSize = rule.table.size
        val counts = IntArray(tableSize)
        val entropyNorm = ln(min(tableSize, w).toDouble())
        val entropies = DoubleArray(steps)
        var changed = 0L
        for (t in 0 until steps) {
            counts.fill(0)
            sim.step(counts)
            val row = sim.current.copyOf()
            val prev = rows[rows.size - 1]
            for (x in 0 until w) if (row[x] != prev[x]) changed++
            rows += row
            var h = 0.0
            for (c in counts) if (c > 0) {
                val p = c.toDouble() / w
                h -= p * ln(p)
            }
            entropies[t] = if (entropyNorm > 0) h / entropyNorm else 0.0
        }

        val changeRate = changed.toDouble() / (steps.toLong() * w)
        var nonZero = 0L
        for (row in rows) for (v in row) if (v != 0) nonZero++
        val density = nonZero.toDouble() / (rows.size.toLong() * w)

        // Input entropy statistics, measured once information from any cell could have reached every
        // other cell. Before that, a pattern growing from a seed changes entropy just by expanding,
        // which says nothing about structures interacting.
        val warm = min(steps / 2, (w + 2 * rule.r - 1) / (2 * rule.r))
        val ie = entropies.copyOfRange(warm, steps)
        val ieMean = ie.average()
        val ieStd = sqrt(ie.sumOf { (it - ieMean) * (it - ieMean) } / ie.size)

        val (period, transient) = findPeriod(rows, rule.r)
        val last = rows[rows.size - 1]
        val dies = last.all { it == last[0] } && (period == 1)

        val compression = compressionRatio(rows, spec.seed)
        val multiscale = multiscaleStructure(rows, rule.k)
        val damage = damageSpread(spec)

        val score = combine(
            compression = compression,
            multiscale = multiscale,
            ieStd = ieStd,
            ieMean = ieMean,
            damage = damage,
            changeRate = changeRate,
            dies = dies,
            period = period,
            transient = transient,
            steps = steps,
        )
        return InterestReport(
            score = score,
            compression = compression,
            multiscale = multiscale,
            inputEntropyMean = ieMean,
            inputEntropyStd = ieStd,
            damage = damage,
            changeRate = changeRate,
            density = density,
            dies = dies,
            period = period,
            transient = transient,
            silhouette = silhouette(rows),
        )
    }

    internal fun silhouette(rows: List<IntArray>): DoubleArray {
        val n = SILHOUETTE_SIZE
        val w = rows[0].size
        val counts = HashMap<Int, Int>()
        for (row in rows) for (v in row) counts[v] = (counts[v] ?: 0) + 1
        val background = counts.maxByOrNull { it.value }!!.key
        val filled = DoubleArray(n * n)
        val total = DoubleArray(n * n)
        for (t in rows.indices) {
            val by = t * n / rows.size
            val row = rows[t]
            for (x in 0 until w) {
                val i = by * n + x * n / w
                total[i]++
                if (row[x] != background) filled[i]++
            }
        }
        return DoubleArray(n * n) { if (total[it] > 0) filled[it] / total[it] else 0.0 }
    }

    /**
     * Combines the measurements into one score. Shape, not rule identity, drives every term:
     * - patterns that die or lock into a short cycle early are heavily penalised;
     * - compression ratio is rewarded in the middle band (structure without pure repetition or noise);
     * - structure that persists across spatial scales is rewarded (noise averages away, nesting doesn't);
     * - temporal variance of input entropy rewards interacting localised structures;
     * - perturbations that spread partially (not zero, not maximally) indicate edge-of-chaos dynamics.
     */
    internal fun combine(
        compression: Double,
        multiscale: Double,
        ieStd: Double,
        ieMean: Double,
        damage: Double,
        changeRate: Double,
        dies: Boolean,
        period: Int,
        transient: Int,
        steps: Int,
    ): Double {
        if (dies && transient < steps / 3) return 0.02 * transient / steps
        if (changeRate < 0.002) return 0.01

        val complexity = exp(-sq((compression - 0.6) / 0.25))
        val scales = min(1.0, multiscale / 0.6)
        val variance = min(1.0, ieStd / 0.1)
        val edge = exp(-sq((damage - 0.35) / 0.35))
        val activity = min(1.0, changeRate / 0.05) * min(1.0, ieMean / 0.15)

        var s = 0.3 * complexity + 0.35 * scales + 0.2 * variance + 0.15 * edge
        s *= 0.4 + 0.6 * activity

        if (period > 0) {
            // A cycle that sets in late still gave the viewer something to watch.
            val lateness = min(1.0, transient / (0.6 * steps))
            s *= 0.15 + 0.85 * lateness * lateness
        }
        return s.coerceIn(0.0, 1.0)
    }

    /**
     * Looks for the final row repeating an earlier row up to a spatial shift (covers still lifes,
     * oscillators and uniformly moving patterns). Returns (period, transient) or (0, 0).
     */
    internal fun findPeriod(rows: List<IntArray>, r: Int): Pair<Int, Int> {
        val n = rows.size
        val w = rows[0].size
        val last = n - 1
        val maxP = min(64, last / 2)
        for (p in 1..maxP) {
            val maxShift = min(p * r, w / 2)
            for (s in -maxShift..maxShift) {
                if (!equalShifted(rows[last], rows[last - p], s)) continue
                var t = last
                while (t - p >= 0 && equalShifted(rows[t], rows[t - p], s)) t--
                return p to t - p + 1
            }
        }
        return 0 to 0
    }

    private fun equalShifted(a: IntArray, b: IntArray, shift: Int): Boolean {
        val w = a.size
        val s = Math.floorMod(shift, w)
        for (x in 0 until w) if (a[x] != b[(x + s) % w]) return false
        return true
    }

    /**
     * Coarse-grains each state's indicator field by repeated 2x2 averaging and measures how much
     * variance each step removes. Returns the fraction removed beyond the first (finest) step.
     */
    internal fun multiscaleStructure(rows: List<IntArray>, k: Int): Double {
        var h = rows.size
        var w = rows[0].size
        h -= h % 2; w -= w % 2
        if (h < 4 || w < 4) return 0.0
        var fine = 0.0
        var coarse = 0.0
        for (state in (if (k == 2) 1..1 else 0 until k)) {
            var fh = h
            var fw = w
            var field = DoubleArray(fh * fw) { i -> if (rows[i / fw][i % fw] == state) 1.0 else 0.0 }
            var level = 0
            while (fh >= 4 && fw >= 4) {
                val ch = fh / 2
                val cw = fw / 2
                val next = DoubleArray(ch * cw)
                var loss = 0.0
                for (y in 0 until ch) for (x in 0 until cw) {
                    val a = field[(2 * y) * fw + 2 * x]
                    val b = field[(2 * y) * fw + 2 * x + 1]
                    val c = field[(2 * y + 1) * fw + 2 * x]
                    val d = field[(2 * y + 1) * fw + 2 * x + 1]
                    val m = (a + b + c + d) / 4
                    next[y * cw + x] = m
                    loss += sq(a - m) + sq(b - m) + sq(c - m) + sq(d - m)
                }
                // Each level's loss, per original cell, so levels are comparable.
                loss /= (fh * fw).toDouble()
                if (level == 0) fine += loss else coarse += loss
                field = next
                fh = ch - ch % 2
                fw = cw - cw % 2
                if (fh != ch || fw != cw) field = crop(field, cw, fh, fw)
                level++
            }
        }
        val total = fine + coarse
        return if (total <= 1e-12) 0.0 else coarse / total
    }

    private fun crop(field: DoubleArray, srcW: Int, h: Int, w: Int): DoubleArray =
        DoubleArray(h * w) { i -> field[(i / w) * srcW + i % w] }

    /** Deflate size of the diagram relative to a shuffled copy with identical cell statistics. */
    internal fun compressionRatio(rows: List<IntArray>, seed: Long): Double {
        val w = rows[0].size
        val bytes = ByteArray(rows.size * w)
        var i = 0
        for (row in rows) for (v in row) bytes[i++] = v.toByte()
        val shuffled = bytes.copyOf()
        val rng = Random(seed xor 0x5DEECE66DL)
        for (j in shuffled.size - 1 downTo 1) {
            val o = rng.nextInt(j + 1)
            val tmp = shuffled[j]; shuffled[j] = shuffled[o]; shuffled[o] = tmp
        }
        val a = deflatedSize(bytes)
        val b = deflatedSize(shuffled)
        return if (b == 0) 0.0 else (a.toDouble() / b).coerceIn(0.0, 1.5)
    }

    private fun deflatedSize(data: ByteArray): Int {
        val d = Deflater(6)
        d.setInput(data)
        d.finish()
        val buf = ByteArray(8192)
        var total = 0
        while (!d.finished()) total += d.deflate(buf)
        d.end()
        return total
    }

    /** Flips one cell and measures how much of the ring ends up different. */
    internal fun damageSpread(spec: ReelSpec): Double {
        val init = spec.initialRow()
        val k = spec.rule.k
        val mutated = init.copyOf()
        val c = spec.width / 2
        mutated[c] = (mutated[c] + 1) % k
        val a = CaSimulator(spec.rule, init)
        val b = CaSimulator(spec.rule, mutated)
        val steps = min(spec.width / 2, 100)
        var acc = 0.0
        var samples = 0
        for (t in 1..steps) {
            a.step(); b.step()
            if (t > steps - 10) {
                var diff = 0
                for (x in 0 until spec.width) if (a.current[x] != b.current[x]) diff++
                acc += diff.toDouble() / spec.width
                samples++
            }
        }
        val avg = if (samples == 0) 0.0 else acc / samples
        // Normalise by the difference expected between two unrelated random rows.
        val expected = max(0.2, 1.0 - 1.0 / k)
        return min(1.0, avg / expected)
    }

}
