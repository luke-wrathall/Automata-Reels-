package com.automata.reels.data

import com.automata.reels.ca.ReelSpec

object FavoritesCodec {
    fun encode(specs: List<ReelSpec>): String = specs.joinToString("\n") { it.encode() }

    /** Skips malformed lines rather than losing every favourite. */
    fun decode(text: String?): List<ReelSpec> =
        text.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { runCatching { ReelSpec.decode(it) }.getOrNull() }
}
