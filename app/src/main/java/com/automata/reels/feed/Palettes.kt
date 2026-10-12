package com.automata.reels.feed

/** Colour schemes for reels. State 0 is the background; states 1..k-1 use [colors] in order. */
class Palette(val background: Int, val colors: IntArray) {
    fun colorFor(state: Int): Int = if (state == 0) background else colors[(state - 1) % colors.size]

    /** ARGB colour per state for a k-state rule. */
    fun forStates(k: Int): IntArray = IntArray(k) { colorFor(it) }
}

object Palettes {
    private fun c(rgb: Long): Int = (0xFF000000 or rgb).toInt()

    val all: List<Palette> = listOf(
        Palette(c(0x0B0B10), intArrayOf(c(0xF2F2F2), c(0xFF4F81), c(0x4FC3F7), c(0xFFD54F))),
        Palette(c(0x101820), intArrayOf(c(0xFEE715), c(0xF2668B), c(0x48CAE4), c(0xE9ECEF))),
        Palette(c(0x0D1B2A), intArrayOf(c(0x7FDBDA), c(0xFFB4A2), c(0xE5989B), c(0xB5838D))),
        Palette(c(0x14110F), intArrayOf(c(0xF4A261), c(0xE76F51), c(0x2A9D8F), c(0xE9C46A))),
        Palette(c(0x0F0F0F), intArrayOf(c(0x9EF01A), c(0x38B000), c(0xCCFF33), c(0x70E000))),
        Palette(c(0x1A1423), intArrayOf(c(0xF0E6EF), c(0xB8BEDD), c(0xA0A0FF), c(0xEFC3E6))),
        Palette(c(0x03071E), intArrayOf(c(0xFFBA08), c(0xF48C06), c(0xDC2F02), c(0x9D0208))),
        Palette(c(0x001219), intArrayOf(c(0x94D2BD), c(0x0A9396), c(0xE9D8A6), c(0xEE9B00))),
        Palette(c(0xF4F1EA), intArrayOf(c(0x1D1D1D), c(0xC1121F), c(0x003049), c(0x669BBC))),
        Palette(c(0x111111), intArrayOf(c(0xFF6B6B), c(0xFFE66D), c(0x4ECDC4), c(0xF7FFF7))),
    )

    operator fun get(index: Int): Palette = all[Math.floorMod(index, all.size)]
}
