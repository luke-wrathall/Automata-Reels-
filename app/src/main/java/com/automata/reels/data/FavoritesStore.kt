package com.automata.reels.data

import android.content.Context
import com.automata.reels.ca.ReelSpec

/** Persists liked reels (newest first) in SharedPreferences. */
class FavoritesStore(context: Context) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun load(): List<ReelSpec> = FavoritesCodec.decode(prefs.getString(KEY, null))

    fun save(specs: List<ReelSpec>) {
        prefs.edit().putString(KEY, FavoritesCodec.encode(specs)).apply()
    }

    private companion object {
        const val KEY = "specs"
    }
}
