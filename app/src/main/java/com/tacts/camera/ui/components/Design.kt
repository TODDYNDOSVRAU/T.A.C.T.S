package com.tacts.camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tacts.camera.ui.theme.AppText
import com.tacts.camera.ui.theme.Highlight
import com.tacts.camera.ui.theme.Shapes
import com.tacts.camera.ui.theme.Size
import com.tacts.camera.ui.theme.Space

/*
 * The building blocks, from T.A.R.K.A.S.: same type roles, same heights, same press feedback, same
 * accessibility semantics. Colours come from the theme, sizes from Tokens.kt.
 */

// ---- press feedback ----

/** Click with the app's one press feedback ([Highlight]), for rows that draw no surface of their own. */
fun Modifier.highlightClickable(
  enabled: Boolean = true,
  role: Role = Role.Button,
  onClickLabel: String? = null,
  onClick: () -> Unit
): Modifier = composed {
  this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = Highlight,
    enabled = enabled,
    role = role,
    onClickLabel = onClickLabel,
    onClick = onClick
  )
}

// ---- buttons ----

/** The one main action of a screen: filled with the accent. */
@Composable
fun PrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  enabled: Boolean = true
) {
  val colors = MaterialTheme.colorScheme
  ButtonLayout(
    text = text,
    icon = icon,
    container = if (enabled) colors.primary else colors.surfaceContainerHigh,
    content = if (enabled) colors.onPrimary else colors.onSurface.copy(alpha = 0.38f),
    enabled = enabled,
    onClick = onClick,
    modifier = modifier
  )
}

@Composable
private fun ButtonLayout(
  text: String,
  icon: ImageVector?,
  container: Color,
  content: Color,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier
) {
  Row(
    modifier = modifier
      .heightIn(min = 50.dp)
      .clip(Shapes.m)
      .background(container)
      .highlightClickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = Space.l, vertical = Space.m),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center
  ) {
    if (icon != null) {
      Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(Size.iconSmall))
      Spacer(Modifier.width(Space.s))
    }
    Text(text, style = AppText.Headline, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

/** A text-only action in the accent colour, 48 dp tall. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  Box(
    modifier = modifier
      .heightIn(min = Size.touch)
      .clip(Shapes.s)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .padding(horizontal = Space.s),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text,
      style = AppText.Body,
      color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
      maxLines = 1
    )
  }
}

/** A round icon button with a label for screen readers. 48 dp touch, [size] visible. */
@Composable
fun IconAction(
  icon: ImageVector,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  tint: Color = LocalContentColor.current,
  filled: Boolean = false,
  enabled: Boolean = true,
  size: Dp = 40.dp
) {
  Box(
    modifier = modifier
      .size(maxOf(size, Size.touch))
      .clip(CircleShape)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .semantics { this.contentDescription = contentDescription },
    contentAlignment = Alignment.Center
  ) {
    Box(
      modifier = Modifier
        .size(size)
        .clip(CircleShape)
        .then(if (filled) Modifier.background(MaterialTheme.colorScheme.surfaceContainer) else Modifier),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        icon,
        contentDescription = null,
        tint = if (enabled) tint else tint.copy(alpha = 0.38f),
        modifier = Modifier.size(Size.icon)
      )
    }
  }
}

/**
 * One choice among a few (Photo / Video, a white balance, a look's name). Announced as a tab; the
 * selected one is filled with the text colour, as in T.A.R.K.A.S.
 */
@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  val colors = MaterialTheme.colorScheme
  Box(
    modifier = modifier
      .minimumInteractiveComponentSize()
      .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    Box(
      modifier = Modifier
        .heightIn(min = 34.dp)
        .clip(Shapes.pill)
        .background(if (selected) colors.onSurface else colors.surfaceContainer)
        .padding(horizontal = 14.dp, vertical = 7.dp),
      contentAlignment = Alignment.Center
    ) {
      Text(
        label,
        style = AppText.Subhead.copy(fontWeight = FontWeight.Medium),
        color = if (selected) colors.background else colors.onSurface,
        maxLines = 1
      )
    }
  }
}
