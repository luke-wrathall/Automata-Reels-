package com.automata.reels.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.automata.reels.ca.CaSimulator
import com.automata.reels.ca.ReelSpec
import com.automata.reels.feed.Palettes
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Keeps the most recent [rows] generations of a reel in a ring-buffer bitmap (one pixel per cell).
 * Generations fill the screen top-down; once it is full the diagram scrolls upward.
 */
class AutomatonRenderer(val spec: ReelSpec, val rows: Int) {
    private val sim = CaSimulator(spec.rule, spec.initialRow())
    private val colors = Palettes[spec.palette].forStates(spec.rule.k)
    private val bitmap: Bitmap = Bitmap.createBitmap(spec.width, rows, Bitmap.Config.ARGB_8888)
    val image: ImageBitmap = bitmap.asImageBitmap()
    private val rowPixels = IntArray(spec.width)

    /** Ring index the next generation is written to. */
    private var head = 0

    /** Generations written so far (saturates at [rows]). */
    var filled = 0
        private set

    /** Fractional progress toward the next generation, used for smooth scrolling. */
    private var phase = 0f

    val generation get() = sim.generation
    val background get() = colors[0]

    private fun writeRow() {
        val cur = sim.current
        for (x in rowPixels.indices) rowPixels[x] = colors[cur[x]]
        bitmap.setPixels(rowPixels, 0, spec.width, 0, head, spec.width, 1)
        head = (head + 1) % rows
        if (filled < rows) filled++
        sim.step()
    }

    fun advanceRows(n: Int) = repeat(n) { writeRow() }

    /** Advances by [rowsDelta] generations' worth of time, writing at most [maxRows] rows. */
    fun advanceTime(rowsDelta: Float, maxRows: Int = 12) {
        phase += rowsDelta
        var written = 0
        while (phase >= 1f) {
            phase -= 1f
            if (written++ < maxRows) writeRow()
        }
        if (filled < rows) phase = 0f
    }

    fun draw(scope: DrawScope) = with(scope) {
        val w = size.width.roundToInt()
        val cell = size.width / spec.width
        if (filled < rows) {
            if (filled == 0) return@with
            drawImage(
                image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(spec.width, filled),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(w, (filled * cell).roundToInt()),
                filterQuality = FilterQuality.None,
            )
            return@with
        }
        // Oldest row is at `head`; draw head..end, then 0..head-1, shifted up by the scroll phase.
        val yOff = -phase * cell
        val n1 = rows - head
        val top1 = yOff.roundToInt()
        val bottom1 = (yOff + n1 * cell).roundToInt()
        val bottom2 = (yOff + rows * cell).roundToInt()
        drawImage(
            image,
            srcOffset = IntOffset(0, head),
            srcSize = IntSize(spec.width, n1),
            dstOffset = IntOffset(0, top1),
            dstSize = IntSize(w, bottom1 - top1),
            filterQuality = FilterQuality.None,
        )
        if (head > 0) {
            drawImage(
                image,
                srcOffset = IntOffset(0, 0),
                srcSize = IntSize(spec.width, head),
                dstOffset = IntOffset(0, bottom1),
                dstSize = IntSize(w, bottom2 - bottom1),
                filterQuality = FilterQuality.None,
            )
        }
    }
}

/**
 * Draws [spec] filling the available space, growing downward while [playing].
 * Changing [restartKey] restarts the reel from its first generation.
 */
@Composable
fun AutomatonView(
    spec: ReelSpec,
    playing: Boolean,
    modifier: Modifier = Modifier,
    restartKey: Int = 0,
    rowsPerSecond: Float = 45f,
    prefillFraction: Float = 0.3f,
    onGeneration: (Int) -> Unit = {},
) {
    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        if (widthPx <= 0 || heightPx <= 0 || widthPx == Int.MAX_VALUE || heightPx == Int.MAX_VALUE) return@BoxWithConstraints
        val cell = widthPx.toFloat() / spec.width
        val rows = ceil(heightPx / cell).toInt() + 1
        val renderer = remember(spec, rows, restartKey) {
            AutomatonRenderer(spec, rows).also { it.advanceRows(min(rows, (rows * prefillFraction).toInt())) }
        }
        var frame by remember { mutableIntStateOf(0) }

        LaunchedEffect(renderer, playing) {
            if (!playing) return@LaunchedEffect
            var last = -1L
            while (true) {
                withFrameNanos { now ->
                    if (last > 0) {
                        val dt = (now - last) / 1_000_000_000f
                        renderer.advanceTime(min(dt, 0.1f) * rowsPerSecond)
                    }
                    last = now
                }
                frame++
                onGeneration(renderer.generation)
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            frame // read so the canvas redraws every frame
            drawRect(androidx.compose.ui.graphics.Color(renderer.background))
            renderer.draw(this)
        }
    }
}

/** A static picture of a reel's first generations, sized to [rows] x width cells. */
fun renderThumbnail(spec: ReelSpec, rows: Int): ImageBitmap {
    val r = AutomatonRenderer(spec, rows)
    r.advanceRows(rows)
    return r.image
}
