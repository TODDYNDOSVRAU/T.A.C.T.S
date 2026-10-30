package com.tacts.camera.ui.theme

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

/**
 * A surface floating over the viewfinder (the panels, the zoom capsule). The T.A.R.K.A.S. "Sólido"
 * style: the app's surface colour, here slightly see-through so the frame stays readable behind it.
 * Blur is left out on purpose: a blurred patch over the viewfinder hides what is being framed.
 */
@Composable
fun Modifier.floatingSurface(shape: Shape = RectangleShape): Modifier =
  this.clip(shape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f), shape)

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
