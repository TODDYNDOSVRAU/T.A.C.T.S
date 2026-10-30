package com.tacts.camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tacts.camera.ui.theme.AppText
import com.tacts.camera.ui.theme.JetBrainsMono
import com.tacts.camera.ui.theme.Shapes
import com.tacts.camera.ui.theme.Size
import com.tacts.camera.ui.theme.Space

/*
 * Grouped lists, from T.A.R.K.A.S.: the settings are built of these, so a row, a group, a header and
 * a switch look and behave as they do there.
 */

/** The top of a sub-page: a back control naming where it goes, then the page's large title. */
@Composable
fun SubPageHeader(
  title: String,
  backLabel: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  trailing: @Composable RowScope.() -> Unit = {}
) {
  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = Space.s, end = Space.s),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(
        modifier = Modifier
          .heightIn(min = Size.touch)
          .clip(Shapes.s)
          .clickable(role = Role.Button, onClickLabel = "Back to $backLabel", onClick = onBack)
          .padding(horizontal = Space.s),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(backLabel, style = AppText.Body, color = MaterialTheme.colorScheme.primary, maxLines = 1)
      }
      Spacer(Modifier.weight(1f))
      trailing()
    }
    Text(
      text = title,
      style = AppText.LargeTitle,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(start = Space.page, end = Space.page, top = Space.xs, bottom = Space.m).semantics { heading() }
    )
  }
}

/** The small label above a group of rows. */
@Composable
fun GroupHeader(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    style = AppText.Footnote,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier.padding(start = Space.page + Space.l, end = Space.page, bottom = Space.s, top = Space.xl).semantics { heading() }
  )
}

/** The note under a group of rows, explaining what its setting does. */
@Composable
fun GroupFooter(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    style = AppText.Footnote,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier.padding(start = Space.page + Space.l, end = Space.page + Space.l, top = Space.s)
  )
}

/** A rounded group of rows on the page. */
@Composable
fun InsetGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = Space.page)
      .clip(Shapes.m)
      .background(MaterialTheme.colorScheme.surfaceContainer),
    content = content
  )
}

/** Hairline between two rows of a group, inset to where the row's text starts. */
@Composable
fun GroupDivider(inset: Dp = Space.l) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = inset)
      .height(0.5.dp)
      .background(MaterialTheme.colorScheme.outline)
  )
}

/** A rounded square holding a row's icon. */
@Composable
fun RowIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary) {
  Box(
    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(tint.copy(alpha = 0.16f)),
    contentAlignment = Alignment.Center
  ) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
  }
}

@Composable
private fun RowLayout(
  title: String,
  subtitle: String?,
  icon: ImageVector?,
  titleColor: Color,
  modifier: Modifier,
  trailing: @Composable RowScope.() -> Unit
) {
  Row(
    modifier = modifier.fillMaxWidth().heightIn(min = Size.row).padding(horizontal = Space.l, vertical = Space.s),
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (icon != null) {
      RowIcon(icon)
      Spacer(Modifier.width(Space.m))
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(title, style = AppText.Body, color = titleColor)
      if (subtitle != null) Text(subtitle, style = AppText.Footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    trailing()
  }
}

/** A row that opens something. [value] shows the current choice at the trailing edge. */
@Composable
fun ListRow(
  title: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  icon: ImageVector? = null,
  value: String? = null,
  chevron: Boolean = true,
  enabled: Boolean = true
) {
  val colors = MaterialTheme.colorScheme
  RowLayout(
    title = title,
    subtitle = subtitle,
    icon = icon,
    titleColor = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
    modifier = modifier.highlightClickable(enabled = enabled, onClick = onClick)
  ) {
    if (value != null) {
      Text(
        value,
        style = AppText.Body,
        color = colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = Space.s)
      )
    }
    if (chevron) {
      Icon(
        Icons.Rounded.ChevronRight,
        contentDescription = null,
        tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(start = Space.xs).size(Size.iconSmall)
      )
    }
  }
}

/** A row with a text that only informs. */
@Composable
fun InfoRow(title: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = Size.row)
      .padding(horizontal = Space.l, vertical = Space.s)
      .semantics(mergeDescendants = true) {}
  ) {
    Text(title, style = AppText.Subhead, color = MaterialTheme.colorScheme.onSurface)
    Text(
      value,
      style = if (mono) AppText.Footnote.copy(fontFamily = JetBrainsMono) else AppText.Footnote,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

/** A setting that is on or off. The whole row toggles. */
@Composable
fun SwitchRow(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  icon: ImageVector? = null,
  enabled: Boolean = true
) {
  val colors = MaterialTheme.colorScheme
  RowLayout(
    title = title,
    subtitle = subtitle,
    icon = icon,
    titleColor = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
    modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
  ) {
    Spacer(Modifier.width(Space.m))
    Switch(
      checked = checked,
      onCheckedChange = null,
      enabled = enabled,
      colors = SwitchDefaults.colors(
        checkedTrackColor = colors.primary,
        checkedThumbColor = colors.onPrimary,
        uncheckedTrackColor = colors.surfaceContainerHigh,
        uncheckedBorderColor = Color.Transparent,
        uncheckedThumbColor = colors.onSurfaceVariant
      )
    )
  }
}

/** One choice among several. The chosen one carries a check mark. */
@Composable
fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
  val colors = MaterialTheme.colorScheme
  RowLayout(
    title = title,
    subtitle = subtitle,
    icon = null,
    titleColor = colors.onSurface,
    modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
  ) {
    Box(Modifier.size(Size.icon), contentAlignment = Alignment.Center) {
      if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
    }
  }
}

/** A number with − and + buttons, for counts and seconds. */
@Composable
fun StepperRow(
  title: String,
  value: Int,
  onValue: (Int) -> Unit,
  range: IntRange,
  modifier: Modifier = Modifier,
  step: Int = 1,
  format: (Int) -> String = { it.toString() },
  subtitle: String? = null
) {
  val colors = MaterialTheme.colorScheme
  RowLayout(title = title, subtitle = subtitle, icon = null, titleColor = colors.onSurface, modifier = modifier) {
    IconAction(Icons.Rounded.Remove, "Less", onClick = { onValue((value - step).coerceIn(range)) }, enabled = value > range.first, filled = true, size = 32.dp, tint = colors.primary)
    Text(
      format(value),
      style = AppText.Body.copy(fontFamily = JetBrainsMono),
      color = colors.onSurface,
      modifier = Modifier.width(72.dp),
      textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
    IconAction(Icons.Rounded.Add, "More", onClick = { onValue((value + step).coerceIn(range)) }, enabled = value < range.last, filled = true, size = 32.dp, tint = colors.primary)
  }
}

/** A continuous value with a slider under its title and the value at the trailing edge. */
@Composable
fun SliderRow(
  title: String,
  value: Float,
  onValue: (Float) -> Unit,
  range: ClosedFloatingPointRange<Float>,
  label: String,
  modifier: Modifier = Modifier,
  steps: Int = 0
) {
  val colors = MaterialTheme.colorScheme
  Column(modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(title, style = AppText.Body, color = colors.onSurface, modifier = Modifier.weight(1f))
      Text(label, style = AppText.Body.copy(fontFamily = JetBrainsMono), color = colors.onSurfaceVariant)
    }
    Slider(
      value = value.coerceIn(range),
      onValueChange = onValue,
      valueRange = range,
      steps = steps,
      colors = SliderDefaults.colors(
        thumbColor = colors.primary,
        activeTrackColor = colors.primary,
        inactiveTrackColor = colors.outline,
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent
      )
    )
  }
}
