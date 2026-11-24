package com.martin.ruby.ui.orb

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.isActive

/**
 * Ruby's orb: the Aurora Orb, drawn by [AuroraRenderer].
 *
 * Light on battery:
 * - It animates only while it can be seen: Ruby open and in front (it stops when the screen
 *   turns off, the app goes to the background, or another of Ruby's screens is shown), and
 *   the orb not scrolled out of view.
 * - Slow states (ready, connecting, error) redraw at about 20 frames a second, not 60 or 120.
 * - With Android's "Remove animations" on, it's drawn still, once per state change.
 *
 * [level] is read every frame: the live audio level 0..1, or negative for none (each state
 * then has its own made-up motion).
 */
@Composable
fun AuroraOrb(
    state: OrbState,
    level: () -> Float,
    modifier: Modifier = Modifier,
    size: Dp = 168.dp,
    speed: Float = 1f,
    theme: OrbTheme = OrbTheme.AURORA,
    onLongPress: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val orbPx = with(density) { size.toPx() }
    val renderer = remember(orbPx, theme) { AuroraRenderer(orbPx, theme) }
    val animator = remember { OrbAnimator(state) }
    val currentState by rememberUpdatedState(state)
    val currentLevel by rememberUpdatedState(level)
    var frame by remember { mutableLongStateOf(0L) }

    // Is Ruby in front? (Stopped covers the screen turning off and the app going to the background.)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Is the orb on screen, or scrolled away?
    var onScreen by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    val running = started && onScreen && !still
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = -1L
        while (isActive) {
            withFrameNanos { now ->
                // Calm states look the same at about 20 frames a second: skip the frames between.
                if (animator.calm && last >= 0 && now - last < CALM_FRAME_GAP_NANOS) return@withFrameNanos
                val dt = if (last < 0) 0f else (now - last) / 1e9f
                last = now
                animator.step(currentState, currentLevel(), dt, speed)
                frame = now
            }
        }
    }
    if (still) {
        LaunchedEffect(state) {
            animator.snap(state)
            frame++
        }
    }

    Canvas(
        modifier
            .size(size * 1.6f) // room for the halo and the listening swell
            // Its own layer, so each frame redraws only the orb, not the screen around it.
            .graphicsLayer()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                onScreen = bounds.width > 0f && bounds.height > 0f
            }
            .semantics { contentDescription = "Ruby: ${state.label}" }
            .then(
                if (onLongPress == null) Modifier
                else Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
            )
    ) {
        frame // redraw when the animation steps
        drawIntoCanvas { canvas -> renderer.draw(canvas.nativeCanvas, center.x, center.y, animator) }
    }
}

/** About 20 frames a second: every third frame at 60 Hz, every sixth at 120 Hz. The slowest-moving parts shift only about 24 px a second, so it looks the same as 30 and saves a third of the work (measured on the phone). */
private const val CALM_FRAME_GAP_NANOS = 45_000_000L
