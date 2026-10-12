package com.automata.reels.ca

import kotlin.random.Random

enum class SeedType(val label: String) {
    SINGLE("single cell"),
    RANDOM("random start"),
    SPARSE("sparse start"),
}

/** Everything needed to reproduce one reel exactly. */
data class ReelSpec(
    val rule: CaRule,
    val seedType: SeedType,
    val seed: Long,
    val width: Int,
    val palette: Int,
) {
    /** Identity of the rule + start condition, ignoring the random seed. */
    val ruleKey: String get() = rule.encode() + "/" + seedType.name

    fun initialRow(): IntArray {
        val rng = Random(seed)
        val k = rule.k
        val row = IntArray(width)
        when (seedType) {
            SeedType.SINGLE -> row[width / 2] = if (k == 2) 1 else 1 + rng.nextInt(k - 1)
            SeedType.RANDOM -> for (i in row.indices) row[i] = rng.nextInt(k)
            SeedType.SPARSE -> for (i in row.indices) if (rng.nextDouble() < 0.08) row[i] = 1 + rng.nextInt(k - 1)
        }
        return row
    }

    fun encode(): String = listOf(rule.encode(), seedType.name, seed, width, palette).joinToString("|")

    companion object {
        fun decode(s: String): ReelSpec {
            val p = s.split("|")
            require(p.size == 5) { "bad spec encoding: $s" }
            return ReelSpec(CaRule.decode(p[0]), SeedType.valueOf(p[1]), p[2].toLong(), p[3].toInt(), p[4].toInt())
        }
    }
}

/** Runs a rule on a ring of cells (periodic boundary). */
class CaSimulator(val rule: CaRule, initial: IntArray) {
    val width = initial.size
    var current: IntArray = initial.copyOf()
        private set
    private var next = IntArray(width)
    var generation = 0
        private set

    /**
     * Advances one generation. If [inputCounts] is given (size = rule.table.size), it is incremented
     * with how often each table entry was used during this step.
     */
    fun step(inputCounts: IntArray? = null) {
        val cur = current
        val dst = next
        val w = width
        val r = rule.r
        val table = rule.table
        if (rule.isTotalistic) {
            var sum = 0
            for (j in -r..r) sum += cur[Math.floorMod(j, w)]
            for (x in 0 until w) {
                dst[x] = table[sum]
                if (inputCounts != null) inputCounts[sum]++
                sum += cur[(x + r + 1) % w] - cur[Math.floorMod(x - r, w)]
            }
        } else {
            val k = rule.k
            val mod = CaRule.pow(k, 2 * r)
            var idx = 0
            for (j in -r..r) idx = idx * k + cur[Math.floorMod(j, w)]
            for (x in 0 until w) {
                dst[x] = table[idx]
                if (inputCounts != null) inputCounts[idx]++
                idx = (idx % mod) * k + cur[(x + r + 1) % w]
            }
        }
        next = cur
        current = dst
        generation++
    }
}

/** Runs [spec] for [steps] generations and returns every row, including the initial one. */
fun runSpaceTime(spec: ReelSpec, steps: Int): Array<IntArray> {
    val sim = CaSimulator(spec.rule, spec.initialRow())
    return Array(steps + 1) { t ->
        if (t > 0) sim.step()
        sim.current.copyOf()
    }
}
