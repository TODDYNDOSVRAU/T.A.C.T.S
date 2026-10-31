package com.tacts.camera.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Motion. One spring for everything that appears: critically damped (no overshoot), response 0.3 s,
 * so it settles inside the 150–300 ms of the T.A.L. identity. A bounce would only be right after a
 * flick, and nothing here is thrown. It runs in graphicsLayer only (alpha, translation, scale, drawn
 * by the GPU), so it never re-measures or recomposes anything.
 */
object Motion {
  /** Damping 1 (no overshoot); stiffness (2π / 0.3 s)² for a 0.3 s response. */
  const val Damping = 1f
  const val Stiffness = 439f
  /** How far an arriving item rises: enough to read as arriving, too little to look like it moved. */
  val Rise = 12.dp

  /** How many items of a group arrive one after another, and the gap between them. */
  const val StaggerItems = 8
  const val StaggerStepMs = 30L
}

/**
 * What the system asks of motion. With animations removed (animation scale zero, Samsung's "Remove
 * animations") nothing moves. Read again on every resume, so a change in the system settings
 * applies when the user comes back.
 */
object AppMotion {
  var reduced by mutableStateOf(false)
    private set

  fun readSystem(context: Context) {
    reduced = try {
      Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) {
      false
    }
  }
}

/**
 * The item arrives each time it enters the composition: it fades in while rising [Motion.Rise] into
 * place, without overshoot. The first [Motion.StaggerItems] items of a group come one after another
 * by [index].
 */
@Composable
fun Modifier.entrance(index: Int = 0, enabled: Boolean = true): Modifier {
  if (AppMotion.reduced) return this
  val progress = remember { Animatable(if (enabled) 0f else 1f) }
  LaunchedEffect(Unit) {
    if (!enabled) return@LaunchedEffect
    if (index in 1 until Motion.StaggerItems) delay(index * Motion.StaggerStepMs)
    progress.animateTo(1f, spring(dampingRatio = Motion.Damping, stiffness = Motion.Stiffness))
  }
  return this.graphicsLayer {
    val p = progress.value
    alpha = p
    translationY = (1f - p) * Motion.Rise.toPx()
  }
}

/**
 * A mark placed by a touch (the focus square, the exposure point) appears where the finger is: it
 * settles from slightly larger onto the spot, fading in, without moving away from it.
 */
@Composable
fun Modifier.placedMark(): Modifier {
  if (AppMotion.reduced) return this
  val progress = remember { Animatable(0f) }
  LaunchedEffect(Unit) { progress.animateTo(1f, spring(dampingRatio = Motion.Damping, stiffness = Motion.Stiffness)) }
  return this.graphicsLayer {
    val p = progress.value
    alpha = p
    val scale = 1.25f - 0.25f * p
    scaleX = scale
    scaleY = scale
  }
}

/**
 * How one panel gives its place to another (or to nothing): the old one fades and sinks [Motion.Rise]
 * while the new one fades in rising the same distance, on the same spring, and the space between
 * them resizes on it too, so nothing around them jumps. Enter and exit run along the same path.
 * With reduced motion it is a cross-fade and the size changes at once.
 */
fun panelSwap(risePx: Int, reduced: Boolean): ContentTransform {
  val fade = spring<Float>(dampingRatio = Motion.Damping, stiffness = Motion.Stiffness)
  if (reduced) return ContentTransform(fadeIn(fade), fadeOut(fade), sizeTransform = SizeTransform(clip = true) { _, _ -> snap() })
  val slide = spring(dampingRatio = Motion.Damping, stiffness = Motion.Stiffness, visibilityThreshold = IntOffset.VisibilityThreshold)
  return ContentTransform(
    fadeIn(fade) + slideInVertically(slide) { risePx },
    fadeOut(fade) + slideOutVertically(slide) { risePx },
    sizeTransform = SizeTransform(clip = true) { _, _ -> spring(dampingRatio = Motion.Damping, stiffness = Motion.Stiffness, visibilityThreshold = IntSize.VisibilityThreshold) }
  )
}

/** A row that appears in a stack: it opens its space and fades in, and closes the same way. */
fun rowEnter(reduced: Boolean): EnterTransition =
  if (reduced) fadeIn(spring(stiffness = Motion.Stiffness))
  else fadeIn(spring(stiffness = Motion.Stiffness)) + expandVertically(spring(dampingRatio = Motion.Damping, stiffness = Motion.Stiffness, visibilityThreshold = IntSize.VisibilityThreshold))

fun rowExit(reduced: Boolean): ExitTransition =
  if (reduced) fadeOut(spring(stiffness = Motion.Stiffness))
  else fadeOut(spring(stiffness = Motion.Stiffness)) + shrinkVertically(spring(dampingRatio = Motion.Damping, stiffness = Motion.Stiffness, visibilityThreshold = IntSize.VisibilityThreshold))
