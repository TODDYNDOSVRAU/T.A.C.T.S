package com.tacts.camera.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/**
 * The one press feedback of the app, from T.A.R.K.A.S.: the pressed surface takes a light wash of
 * Precision White the instant the finger lands, and the wash fades out over 220 ms after it lifts. It
 * is the theme's default indication, so every clickable, selectable and toggleable gets it instead of
 * the Material ripple. It draws inside the element's clip, so round buttons stay round.
 */
object Highlight : IndicationNodeFactory {
  private val wash = TalWhite.copy(alpha = 0.10f)

  override fun create(interactionSource: InteractionSource): DelegatableNode = HighlightNode(interactionSource, wash)

  override fun equals(other: Any?) = other === this
  override fun hashCode() = 7
}

private class HighlightNode(private val source: InteractionSource, private val wash: Color) : Modifier.Node(), DrawModifierNode {
  private val alpha = Animatable(0f)

  override fun onAttach() {
    coroutineScope.launch {
      var presses = 0
      source.interactions.collect { interaction ->
        when (interaction) {
          is PressInteraction.Press -> presses++
          is PressInteraction.Release, is PressInteraction.Cancel -> presses = (presses - 1).coerceAtLeast(0)
          else -> return@collect
        }
        launch {
          // In at once, on the press; out gently once no finger is left on it.
          if (presses > 0) alpha.snapTo(1f) else alpha.animateTo(0f, tween(220))
        }
      }
    }
    coroutineScope.launch {
      androidx.compose.runtime.snapshotFlow { alpha.value }.collect { invalidateDraw() }
    }
  }

  override fun ContentDrawScope.draw() {
    drawContent()
    if (alpha.value > 0f) drawRect(wash.copy(alpha = wash.alpha * alpha.value))
  }
}
