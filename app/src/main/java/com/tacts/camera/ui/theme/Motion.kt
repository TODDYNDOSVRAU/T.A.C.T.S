package com.tacts.camera.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
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
 * Motion, from T.A.R.K.A.S. Of its animations T.A.C.T.S. keeps one: "Quicar", the bounce its lists
 * can arrive with. Everything else switches without movement. The bounce runs in graphicsLayer only
 * (alpha and translation, drawn by the GPU), so it never re-measures or recomposes anything.
 */
object Motion {
  /** The "Quicar" spring: under-damped, so it drops past its place and settles back. */
  const val BounceDamping = 0.45f
  const val BounceStiffness = 420f

  /** How many items of a group arrive one after another, and the gap between them. */
  const val StaggerItems = 8
  const val StaggerStepMs = 30L
}

/**
 * What the system asks of motion. With animations removed (animation scale zero, Samsung's "Remove
 * animations") nothing bounces. Read again on every resume, so a change in the system settings
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
 * The item arrives with the T.A.R.K.A.S. "Quicar" animation each time it enters the composition: it
 * drops in from 56 dp below, overshoots and settles, fading in on the way. The first
 * [Motion.StaggerItems] items of a group come one after another by [index].
 */
@Composable
fun Modifier.bounceEntrance(index: Int = 0): Modifier {
  if (AppMotion.reduced) return this
  val progress = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    if (index in 1 until Motion.StaggerItems) delay(index * Motion.StaggerStepMs)
    progress.animateTo(1f, spring(dampingRatio = Motion.BounceDamping, stiffness = Motion.BounceStiffness))
  }
  return this.graphicsLayer {
    val p = progress.value
    // A bounce overshoots past 1; the fade never does.
    alpha = p.coerceIn(0f, 1f)
    translationY = (1f - p) * 56.dp.toPx()
  }
}
