package com.automata.reels.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.automata.reels.feed.FeedMode
import com.automata.reels.feed.ScoredReel

private sealed interface Screen {
    data object Feed : Screen
    data object Favorites : Screen
    data class FavoriteViewer(val start: Int) : Screen
}

@Composable
fun AutomataReelsApp(vm: FeedViewModel) {
    var screen by remember { mutableStateOf<Screen>(Screen.Feed) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = screen) {
            Screen.Feed -> FeedScreen(vm, onOpenFavorites = { screen = Screen.Favorites })
            Screen.Favorites -> {
                BackHandler { screen = Screen.Feed }
                FavoritesScreen(
                    favorites = vm.favorites,
                    onBack = { screen = Screen.Feed },
                    onOpen = { screen = Screen.FavoriteViewer(it) },
                )
            }
            is Screen.FavoriteViewer -> {
                BackHandler { screen = Screen.Favorites }
                FavoriteViewer(vm, s.start, onBack = { screen = Screen.Favorites })
            }
        }
    }
}

@Composable
fun FeedScreen(vm: FeedViewModel, onOpenFavorites: () -> Unit) {
    val mode = vm.mode
    Box(Modifier.fillMaxSize()) {
        key(mode) {
            val reels = vm.feed(mode)
            LaunchedEffect(Unit) { vm.ensureAhead(mode, vm.savedPage(mode)) }
            if (reels.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("Searching rule space…", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                }
            } else {
                val pager = rememberPagerState(initialPage = vm.savedPage(mode).coerceAtMost(reels.size - 1)) { reels.size }
                LaunchedEffect(pager) {
                    snapshotFlow { pager.settledPage }.collect { vm.onPageVisible(mode, it) }
                }

                VerticalPager(
                    state = pager,
                    beyondViewportPageCount = 1,
                    flingBehavior = reelFling(pager),
                    modifier = Modifier.fillMaxSize().testTag("feed-pager"),
                ) { page ->
                    val reel = reels[page]
                    ReelPage(
                        reel = reel,
                        active = page == pager.settledPage,
                        liked = vm.isLiked(reel.spec),
                        onToggleLike = { vm.toggleLike(reel) },
                        onDoubleTapLike = { vm.like(reel) },
                        tag = "reel-$page",
                    )
                }
            }
        }
        FeedTopBar(
            mode = mode,
            favoriteCount = vm.favorites.size,
            onSelectMode = vm::selectMode,
            onOpenFavorites = onOpenFavorites,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/** Like Reels, a short flick (a quarter of the screen) is enough to move to the next reel. */
@Composable
private fun reelFling(state: PagerState) =
    PagerDefaults.flingBehavior(state = state, snapPositionalThreshold = 0.25f)

@Composable
private fun FeedTopBar(
    mode: FeedMode,
    favoriteCount: Int,
    onSelectMode: (FeedMode) -> Unit,
    onOpenFavorites: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0x88000000), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            for (m in FeedMode.entries) {
                val selected = m == mode
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onSelectMode(m) }
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                        .testTag("mode-${m.name}"),
                ) {
                    Text(
                        m.label,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        ),
                        modifier = Modifier.alpha(if (selected) 1f else 0.6f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .width(22.dp)
                            .height(2.dp)
                            .background(if (selected) Color.White else Color.Transparent)
                    )
                }
            }
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenFavorites, modifier = Modifier.testTag("open-favorites")) {
                Icon(Icons.Filled.Favorite, contentDescription = "Liked automata", tint = Color.White)
            }
            if (favoriteCount > 0) {
                Text(
                    "$favoriteCount",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(end = 6.dp).testTag("favorite-count"),
                )
            }
        }
    }
}

@Composable
fun FavoritesScreen(favorites: List<ScoredReel>, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Liked automata", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        if (favorites.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing liked yet.\nDouble-tap a reel or press the heart to save it here.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.testTag("favorites-empty"),
                )
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.testTag("favorites-grid"),
        ) {
            itemsIndexed(favorites, key = { _, r -> r.spec.encode() }) { i, reel ->
                FavoriteTile(reel, Modifier.clickable { onOpen(i) }.testTag("favorite-$i"))
            }
        }
    }
}

@Composable
private fun FavoriteTile(reel: ScoredReel, modifier: Modifier) {
    val spec = reel.spec
    val thumb = remember(spec) { renderThumbnail(spec, spec.width * 16 / 9) }
    Box(modifier.aspectRatio(9f / 16f)) {
        Image(
            bitmap = thumb,
            contentDescription = spec.rule.title,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            spec.rule.title,
            color = Color.White,
            fontSize = 10.sp,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
                .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 3.dp),
        )
    }
}

@Composable
fun FavoriteViewer(vm: FeedViewModel, start: Int, onBack: () -> Unit) {
    // Freeze the list so un-liking inside the viewer doesn't yank pages away.
    val items = remember { vm.favorites.toList() }
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.size - 1)) { items.size }
    Box(Modifier.fillMaxSize()) {
        VerticalPager(
            state = pager,
            beyondViewportPageCount = 1,
            flingBehavior = reelFling(pager),
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val reel = items[page]
            ReelPage(
                reel = reel,
                active = page == pager.settledPage,
                liked = vm.isLiked(reel.spec),
                onToggleLike = { vm.toggleLike(reel) },
                onDoubleTapLike = { vm.like(reel) },
                tag = "fav-reel-$page",
            )
        }
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(4.dp)
                .size(48.dp)
                .testTag("viewer-back"),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
    }
}
