package com.tacts.camera.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.tacts.camera.camera.GlassStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/*
 * The glass of T.A.R.K.A.S.: frosted glass blurs the viewfinder behind a control, liquid glass bends
 * it at the rim with a lit edge (Android 13+), solid is the plain surface. The viewfinder is the only
 * blur source, so glass over the black bars is simply dark.
 */

val LocalGlassStyle = staticCompositionLocalOf { GlassStyle.SOLID }

/** The viewfinder, recorded for frosted glass. Null when blur is off. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/** The viewfinder, recorded for liquid glass. Null when liquid glass is off or unsupported. */
val LocalBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** Whether this device can draw real liquid glass (the lens shader needs Android 13). */
val liquidGlassSupported: Boolean get() = Build.VERSION.SDK_INT >= 33

/**
 * A surface floating over the viewfinder (panels, capsules, round buttons), drawn in the chosen
 * [GlassStyle]. Without a blur source it falls back to the app surface, slightly see-through.
 */
@Composable
fun Modifier.floatingSurface(shape: Shape = RectangleShape): Modifier {
  val style = LocalGlassStyle.current
  val haze = LocalHazeState.current
  val backdrop = LocalBackdrop.current
  val surface = MaterialTheme.colorScheme.surface

  if (style == GlassStyle.LIQUID && backdrop != null && shape is CornerBasedShape && liquidGlassSupported) {
    return this.drawBackdrop(
      backdrop = backdrop,
      shape = { shape },
      effects = {
        vibrancy()
        // Enough blur that the picture never competes with the labels on the glass.
        blur(8.dp.toPx())
        // Height of the bent rim, how far it bends, depth, and the colour fringe at the edge.
        lens(10.dp.toPx(), 20.dp.toPx(), true, true)
      },
      onDrawSurface = { drawRect(surface.copy(alpha = 0.36f)) }
    )
  }

  if (style == GlassStyle.SOLID || haze == null) {
    return this.clip(shape).background(surface.copy(alpha = 0.78f), shape)
  }

  val hazeStyle = HazeStyle(
    backgroundColor = surface,
    tint = HazeTint(surface.copy(alpha = 0.5f)),
    blurRadius = 24.dp,
    noiseFactor = 0.05f
  )
  // Only a faint lift at the top: the capsule reads as a surface without a drawn outline.
  val lift = Color.White.copy(alpha = 0.05f)
  return this
    .clip(shape)
    .hazeEffect(state = haze, style = hazeStyle)
    .drawBehind { drawRect(Brush.verticalGradient(listOf(lift, Color.Transparent), endY = size.height * 0.5f)) }
}

/**
 * Controls on a surface (chips, round buttons): a plain, quiet fill, as in T.A.R.K.A.S. Selected, it
 * takes a light wash of the accent.
 */
@Composable
fun Modifier.glassControl(shape: Shape, selected: Boolean = false): Modifier {
  val colors = MaterialTheme.colorScheme
  val fill = if (selected) colors.primary.copy(alpha = 0.18f) else colors.surfaceContainer
  return this.clip(shape).background(fill, shape)
}

/** Text and icon colour that reads on [glassControl]. */
@Composable
fun glassContentColor(selected: Boolean = false): Color {
  val colors = MaterialTheme.colorScheme
  return if (selected) colors.primary else colors.onSurface
}
