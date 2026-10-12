package com.automata.reels.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.automata.reels.feed.ScoredReel
import kotlin.math.roundToInt

private val LikeRed = Color(0xFFFF3040)
private val TextShadow = Shadow(Color(0xAA000000), Offset(0f, 1f), 4f)

/**
 * One full-screen reel: the animated automaton plus Reels-style overlays.
 * Tap pauses/resumes, double-tap likes (with a heart burst), the side rail has like and replay.
 */
@Composable
fun ReelPage(
    reel: ScoredReel,
    active: Boolean,
    liked: Boolean,
    onToggleLike: () -> Unit,
    onDoubleTapLike: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "reel",
) {
    var paused by remember(reel) { mutableStateOf(false) }
    var restartKey by remember(reel) { mutableIntStateOf(0) }
    val bursts = remember { mutableStateListOf<Pair<Long, Offset>>() }

    // Leaving a reel resets pause, like Reels.
    LaunchedEffect(active) { if (!active) paused = false }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(tag)
            .pointerInput(reel) {
                detectTapGestures(
                    onTap = { paused = !paused },
                    onDoubleTap = { pos ->
                        bursts += System.nanoTime() to pos
                        onDoubleTapLike()
                    },
                )
            }
    ) {
        AutomatonView(
            spec = reel.spec,
            playing = active && !paused,
            restartKey = restartKey,
            modifier = Modifier.fillMaxSize(),
        )

        // Scrim so the overlay text stays readable over bright patterns.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(240.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x80000000), Color(0xB3000000))))
        )

        AnimatedVisibility(
            visible = paused && active,
            enter = fadeIn() + scaleIn(initialScale = 1.3f),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                Modifier.size(72.dp).background(Color(0x66000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Paused", tint = Color.White, modifier = Modifier.size(44.dp))
            }
        }

        for (burst in bursts) {
            key(burst.first) {
                HeartBurst(burst.second) { bursts.remove(burst) }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(end = 8.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            RailButton(
                label = if (liked) "Liked" else "Like",
                onClick = onToggleLike,
                testTag = "$tag-like",
                stateDescription = if (liked) "liked" else "not liked",
            ) {
                Icon(
                    if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    tint = if (liked) LikeRed else Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
            RailButton(label = "Replay", onClick = { restartKey++; paused = false }, testTag = "$tag-replay") {
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }

        RuleCaption(
            reel,
            Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 14.dp, end = 88.dp, bottom = 30.dp)
                .testTag("$tag-caption"),
        )
    }
}

@Composable
private fun RuleCaption(reel: ScoredReel, modifier: Modifier) {
    val rule = reel.spec.rule
    val base = TextStyle(color = Color.White, shadow = TextShadow)
    Column(modifier) {
        Text(
            rule.title,
            style = base.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.alpha(0.85f).testTag("rule-title"),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "${rule.familyLabel} · ${reel.spec.seedType.label}",
            style = base.copy(fontSize = 12.sp),
            modifier = Modifier.alpha(0.6f),
        )
        Text(
            "interest " + "%.2f".format(reel.report.score),
            style = base.copy(fontSize = 11.sp),
            modifier = Modifier.alpha(0.45f),
        )
    }
}

@Composable
private fun RailButton(
    label: String,
    onClick: () -> Unit,
    testTag: String,
    stateDescription: String? = null,
    icon: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .testTag(testTag)
                .semantics {
                    contentDescription = label
                    if (stateDescription != null) this.stateDescription = stateDescription
                },
        ) { icon() }
        Text(label, style = TextStyle(color = Color.White, fontSize = 11.sp, shadow = TextShadow))
    }
}

@Composable
private fun HeartBurst(at: Offset, onDone: () -> Unit) {
    val scale = remember { Animatable(0.3f) }
    val alpha = remember { Animatable(1f) }
    val sizeDp = 96.dp
    val half = with(LocalDensity.current) { (sizeDp / 2).toPx() }
    LaunchedEffect(Unit) {
        scale.animateTo(1.15f, spring(dampingRatio = 0.45f, stiffness = 500f))
        alpha.animateTo(0f, tween(350))
        onDone()
    }
    Icon(
        Icons.Filled.Favorite,
        contentDescription = null,
        tint = LikeRed,
        modifier = Modifier
            .offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }
            .size(sizeDp)
            .scale(scale.value)
            .alpha(alpha.value),
    )
}
