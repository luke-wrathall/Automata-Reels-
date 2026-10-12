package com.automata.reels.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.automata.reels.ca.Interest
import com.automata.reels.ca.ReelSpec
import com.automata.reels.data.FavoritesStore
import com.automata.reels.feed.FeedEngine
import com.automata.reels.feed.FeedMode
import com.automata.reels.feed.ScoredReel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FeedViewModel(app: Application) : AndroidViewModel(app) {
    private val store = FavoritesStore(app)
    private val prefs = app.getSharedPreferences("settings", Application.MODE_PRIVATE)

    var mode by mutableStateOf(
        runCatching { FeedMode.valueOf(prefs.getString("mode", null) ?: "") }.getOrDefault(FeedMode.ELEMENTARY)
    )
        private set

    /** Liked reels, newest first. */
    val favorites: SnapshotStateList<ScoredReel> = mutableStateListOf()

    private val engines = FeedMode.entries.associateWith { FeedEngine(it) }
    private val feeds = FeedMode.entries.associateWith { mutableStateListOf<ScoredReel>() }
    private val generating = HashMap<FeedMode, Job>()
    private val pagePositions = HashMap<FeedMode, Int>()

    init {
        viewModelScope.launch {
            val specs = withContext(Dispatchers.IO) { store.load() }
            val scored = withContext(Dispatchers.Default) { specs.map { ScoredReel(it, Interest.analyze(it)) } }
            favorites.addAll(0, scored.filter { s -> favorites.none { it.spec == s.spec } })
        }
    }

    fun feed(mode: FeedMode): List<ScoredReel> = feeds.getValue(mode)

    fun selectMode(newMode: FeedMode) {
        mode = newMode
        prefs.edit().putString("mode", newMode.name).apply()
    }

    fun savedPage(mode: FeedMode): Int = pagePositions[mode] ?: 0

    /** Records the visible page and makes sure reels further down the feed are ready. */
    fun onPageVisible(mode: FeedMode, page: Int) {
        pagePositions[mode] = page
        ensureAhead(mode, page)
    }

    fun ensureAhead(mode: FeedMode, page: Int) {
        val list = feeds.getValue(mode)
        if (list.size > page + PREFETCH || generating[mode]?.isActive == true) return
        generating[mode] = viewModelScope.launch {
            while (list.size <= pagePositions.getOrDefault(mode, page) + PREFETCH) {
                val likes = favorites.toList()
                val engine = engines.getValue(mode)
                list += withContext(Dispatchers.Default) { engine.next(likes) }
            }
        }
    }

    fun isLiked(spec: ReelSpec): Boolean = favorites.any { it.spec == spec }

    fun toggleLike(reel: ScoredReel) {
        val i = favorites.indexOfFirst { it.spec == reel.spec }
        if (i >= 0) favorites.removeAt(i) else favorites.add(0, reel)
        persist()
    }

    fun like(reel: ScoredReel) {
        if (!isLiked(reel.spec)) toggleLike(reel)
    }

    private fun persist() {
        val specs = favorites.map { it.spec }
        viewModelScope.launch(Dispatchers.IO) { store.save(specs) }
    }

    private companion object {
        const val PREFETCH = 3
    }
}
