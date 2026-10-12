package com.automata.reels.ca

import java.math.BigInteger

enum class RuleKind {
    /** k=2, r=1. Table indexed by the 3-cell neighbourhood (Wolfram numbering). */
    ELEMENTARY,

    /** Table indexed by the sum of the neighbourhood's states. */
    TOTALISTIC,

    /** Table indexed by the full neighbourhood, read as a base-k number (leftmost cell most significant). */
    GENERAL,
}

/**
 * A 1D cellular automaton rule with [k] states and radius [r].
 *
 * [table] maps a neighbourhood index (or neighbourhood sum, for totalistic rules) to the next state.
 * The rule's Wolfram code is `sum(table[i] * k^i)`.
 */
class CaRule(val kind: RuleKind, val k: Int, val r: Int, val table: IntArray) {

    init {
        require(k in 2..9) { "k must be in 2..9" }
        require(r in 1..4) { "r must be in 1..4" }
        require(table.size == tableSize(kind, k, r)) { "table size ${table.size} != ${tableSize(kind, k, r)}" }
        require(table.all { it in 0 until k }) { "table entries must be states" }
        if (kind == RuleKind.ELEMENTARY) require(k == 2 && r == 1)
    }

    val span get() = 2 * r + 1
    val isTotalistic get() = kind == RuleKind.TOTALISTIC

    /** Wolfram code of the rule. */
    val code: BigInteger by lazy {
        var c = BigInteger.ZERO
        val bk = BigInteger.valueOf(k.toLong())
        for (i in table.indices.reversed()) c = c.multiply(bk).add(BigInteger.valueOf(table[i].toLong()))
        c
    }

    /** True if an all-zero background stays all-zero. */
    val isQuiescent get() = table[0] == 0

    /** Fraction of table entries that map to a non-zero state (Langton's lambda). */
    val lambda: Double get() = table.count { it != 0 }.toDouble() / table.size

    val title: String
        get() = when (kind) {
            RuleKind.ELEMENTARY -> "Rule $code"
            RuleKind.TOTALISTIC -> "Totalistic $code"
            RuleKind.GENERAL -> if (code.bitLength() <= 24) "Rule $code" else "Rule 0x" + code.toString(16).uppercase()
        }

    val familyLabel: String
        get() = when (kind) {
            RuleKind.ELEMENTARY -> "elementary"
            RuleKind.TOTALISTIC -> "totalistic · $k colours · r=$r"
            RuleKind.GENERAL -> "$k colours · r=$r"
        }

    /** Compact serialisation: kind, k, r and the table as base-k digits. */
    fun encode(): String = "${kind.name}:$k:$r:" + table.joinToString("")

    override fun equals(other: Any?): Boolean =
        other is CaRule && other.kind == kind && other.k == k && other.r == r && other.table.contentEquals(table)

    override fun hashCode(): Int = (kind.hashCode() * 31 + k) * 31 + r + 997 * table.contentHashCode()

    override fun toString(): String = "$title ($familyLabel)"

    companion object {
        fun tableSize(kind: RuleKind, k: Int, r: Int): Int = when (kind) {
            RuleKind.ELEMENTARY -> 8
            RuleKind.TOTALISTIC -> (2 * r + 1) * (k - 1) + 1
            RuleKind.GENERAL -> pow(k, 2 * r + 1)
        }

        fun elementary(number: Int): CaRule {
            require(number in 0..255)
            return CaRule(RuleKind.ELEMENTARY, 2, 1, IntArray(8) { (number shr it) and 1 })
        }

        fun fromCode(kind: RuleKind, k: Int, r: Int, code: BigInteger): CaRule {
            val size = tableSize(kind, k, r)
            val bk = BigInteger.valueOf(k.toLong())
            var c = code
            val table = IntArray(size) {
                val d = c.mod(bk).toInt()
                c = c.divide(bk)
                d
            }
            require(c.signum() == 0) { "code too large for table" }
            return CaRule(kind, k, r, table)
        }

        fun decode(s: String): CaRule {
            val parts = s.split(":")
            require(parts.size == 4) { "bad rule encoding: $s" }
            val kind = RuleKind.valueOf(parts[0])
            val table = IntArray(parts[3].length) { parts[3][it] - '0' }
            return CaRule(kind, parts[1].toInt(), parts[2].toInt(), table)
        }

        internal fun pow(b: Int, e: Int): Int {
            var x = 1
            repeat(e) { x *= b }
            return x
        }
    }
}
