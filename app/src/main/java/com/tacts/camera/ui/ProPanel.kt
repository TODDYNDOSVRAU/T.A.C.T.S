package com.tacts.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacts.camera.camera.ManualSettings
import com.tacts.camera.camera.TactsCamera
import com.tacts.camera.camera.WhiteBalance
import com.tacts.camera.camera.focusLabel
import com.tacts.camera.camera.nearestIndex
import com.tacts.camera.camera.shutterLabel
import com.tacts.camera.filters.Look
import com.tacts.camera.ui.components.FilterChip
import com.tacts.camera.ui.components.highlightClickable
import com.tacts.camera.ui.theme.AppText
import com.tacts.camera.ui.theme.JetBrainsMono
import com.tacts.camera.ui.theme.Shapes
import com.tacts.camera.ui.theme.Space
import com.tacts.camera.ui.theme.bounceEntrance
import com.tacts.camera.ui.theme.floatingSurface
import com.tacts.camera.ui.theme.glassControl
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ProTab(val label: String) { EV("EV"), ISO("ISO"), SHUTTER("S"), FOCUS("F"), WB("WB") }

/** The manual controls: a row of value chips, and a dial for the selected one under it. */
@Composable
fun ProPanel(camera: TactsCamera, tab: ProTab, onTab: (ProTab) -> Unit, modifier: Modifier = Modifier) {
  val m = camera.manual
  val caps = camera.caps
  val live = camera.live

  Column(
    modifier
      .fillMaxWidth()
      .padding(horizontal = Space.s)
      .bounceEntrance()
      .floatingSurface(Shapes.l)
      .padding(horizontal = Space.m, vertical = Space.m)
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      ProChip(ProTab.EV, evLabel(m.evIndex, caps.evStep), m.evIndex != 0, tab == ProTab.EV, 0, onTab)
      ProChip(ProTab.ISO, if (m.exposureAuto) "${live.iso ?: "–"}" else "${m.iso}", !m.exposureAuto, tab == ProTab.ISO, 1, onTab)
      ProChip(
        ProTab.SHUTTER,
        if (m.exposureAuto) live.shutterNs?.let(::shutterLabel) ?: "–" else shutterLabel(m.shutterNs),
        !m.exposureAuto,
        tab == ProTab.SHUTTER,
        2,
        onTab
      )
      ProChip(ProTab.FOCUS, if (m.focusAuto) "AF" else focusLabel(m.focusDiopters), !m.focusAuto, tab == ProTab.FOCUS, 3, onTab)
      ProChip(ProTab.WB, m.whiteBalance.label.take(4), m.whiteBalance != WhiteBalance.AUTO, tab == ProTab.WB, 4, onTab)
    }
    Spacer(Modifier.height(Space.s))
    when (tab) {
      ProTab.EV -> EvDial(camera, m)
      ProTab.ISO -> if (caps.isoValues.isEmpty()) Unsupported() else ExposureDial(
        camera,
        auto = m.exposureAuto,
        count = caps.isoValues.size,
        index = nearestIndex(caps.isoValues, m.iso) { a, b -> abs(a - b).toDouble() },
        label = { "ISO ${caps.isoValues[it]}" },
        onIndex = { i -> camera.updateManual { s -> s.copy(iso = caps.isoValues[i]) } }
      )
      ProTab.SHUTTER -> if (caps.shutterValues.isEmpty()) Unsupported() else ExposureDial(
        camera,
        auto = m.exposureAuto,
        count = caps.shutterValues.size,
        index = nearestIndex(caps.shutterValues, m.shutterNs) { a, b -> abs(kotlin.math.ln(a.toDouble() / b)) },
        label = { shutterLabel(caps.shutterValues[it]) },
        onIndex = { i -> camera.updateManual { s -> s.copy(shutterNs = caps.shutterValues[i]) } }
      )
      ProTab.FOCUS -> if (caps.minFocusDiopters <= 0f) Unsupported("This lens has fixed focus") else FocusDial(camera, m)
      ProTab.WB -> Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Space.xs)
      ) {
        caps.whiteBalances.forEachIndexed { i, wb ->
          FilterChip(
            wb.label,
            selected = m.whiteBalance == wb,
            onClick = { camera.updateManual { it.copy(whiteBalance = wb) } },
            modifier = Modifier.bounceEntrance(i)
          )
        }
      }
    }
  }
}

private fun evLabel(index: Int, step: Float): String {
  val ev = index * step
  return if (abs(ev) < 0.01f) "±0" else (if (ev > 0) "+" else "−") + "%.1f".format(abs(ev))
}

/** One manual value: its name over its reading, the reading in the accent once it is set by hand. */
@Composable
private fun ProChip(tab: ProTab, value: String, manual: Boolean, selected: Boolean, index: Int, onTab: (ProTab) -> Unit) {
  val colors = MaterialTheme.colorScheme
  Column(
    Modifier
      .bounceEntrance(index)
      .glassControl(Shapes.s, selected)
      .selectable(selected = selected, role = Role.Tab) { onTab(tab) }
      .padding(horizontal = Space.m, vertical = Space.s),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(tab.label, style = AppText.Caption2.copy(fontWeight = FontWeight.SemiBold), color = colors.onSurfaceVariant)
    Text(
      value,
      style = AppText.Subhead.copy(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium),
      color = if (manual) colors.primary else colors.onSurface
    )
  }
}

@Composable
private fun EvDial(camera: TactsCamera, m: ManualSettings) {
  val caps = camera.caps
  if (caps.evRange.first == caps.evRange.last) return Unsupported("This camera has no exposure compensation")
  if (!m.exposureAuto) return Unsupported("Exposure compensation works with auto ISO and shutter")
  DialRow(label = "EV ${evLabel(m.evIndex, caps.evStep)}", leading = {
    FilterChip("0", selected = m.evIndex == 0, onClick = { camera.updateManual { it.copy(evIndex = 0) } })
  }) {
    val span = caps.evRange.last - caps.evRange.first
    Slider(
      value = m.evIndex.toFloat(),
      onValueChange = { v -> camera.updateManual { it.copy(evIndex = v.roundToInt()) } },
      valueRange = caps.evRange.first.toFloat()..caps.evRange.last.toFloat(),
      steps = (span - 1).coerceAtLeast(0),
      colors = sliderColors(m.evIndex != 0)
    )
  }
}

@Composable
private fun ExposureDial(
  camera: TactsCamera,
  auto: Boolean,
  count: Int,
  index: Int,
  label: (Int) -> String,
  onIndex: (Int) -> Unit
) {
  DialRow(label = if (auto) "AUTO" else label(index), leading = {
    FilterChip("A", selected = auto, onClick = { camera.updateManual { it.copy(exposureAuto = !it.exposureAuto) } })
  }) {
    Slider(
      value = index.toFloat(),
      onValueChange = { v ->
        if (auto) camera.updateManual { it.copy(exposureAuto = false) }
        onIndex(v.roundToInt().coerceIn(0, count - 1))
      },
      valueRange = 0f..(count - 1).toFloat(),
      steps = (count - 2).coerceAtLeast(0),
      colors = sliderColors(!auto)
    )
  }
}

@Composable
private fun FocusDial(camera: TactsCamera, m: ManualSettings) {
  val max = camera.caps.minFocusDiopters
  DialRow(label = if (m.focusAuto) "AUTO" else focusLabel(m.focusDiopters), leading = {
    FilterChip("AF", selected = m.focusAuto, onClick = { camera.updateManual { it.copy(focusAuto = !it.focusAuto) } })
  }) {
    // Left is infinity, right is the closest the lens can focus.
    Slider(
      value = m.focusDiopters.coerceIn(0f, max),
      onValueChange = { v -> camera.updateManual { it.copy(focusAuto = false, focusDiopters = v) } },
      valueRange = 0f..max,
      colors = sliderColors(!m.focusAuto)
    )
  }
}

@Composable
private fun DialRow(label: String, leading: @Composable () -> Unit, slider: @Composable () -> Unit) {
  Row(Modifier.bounceEntrance(5), verticalAlignment = Alignment.CenterVertically) {
    leading()
    Spacer(Modifier.width(Space.s))
    Box(Modifier.weight(1f)) { slider() }
    Spacer(Modifier.width(Space.s))
    Text(
      label,
      style = AppText.Footnote.copy(fontFamily = JetBrainsMono),
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.width(76.dp)
    )
  }
}

@Composable
private fun sliderColors(manual: Boolean): androidx.compose.material3.SliderColors {
  val colors = MaterialTheme.colorScheme
  return SliderDefaults.colors(
    thumbColor = if (manual) colors.primary else colors.onSurface,
    activeTrackColor = if (manual) colors.primary else colors.onSurface.copy(alpha = 0.6f),
    inactiveTrackColor = colors.outline,
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent
  )
}

@Composable
private fun Unsupported(text: String = "This camera has no manual sensor control") {
  Text(
    text,
    style = AppText.Footnote,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.bounceEntrance(5).padding(vertical = Space.m, horizontal = Space.xs)
  )
}

/** The look picker: a swatch for each look, drawn through its own colour matrix. */
@Composable
fun LookStrip(current: Look, onLook: (Look) -> Unit, modifier: Modifier = Modifier) {
  val colors = MaterialTheme.colorScheme
  Row(
    modifier
      .fillMaxWidth()
      .padding(horizontal = Space.s)
      .bounceEntrance()
      .floatingSurface(Shapes.l)
      .horizontalScroll(rememberScrollState())
      .padding(horizontal = Space.s, vertical = Space.s),
    horizontalArrangement = Arrangement.spacedBy(Space.xxs)
  ) {
    Look.entries.forEachIndexed { i, look ->
      val selected = look == current
      val filter = look.matrix()?.let { ColorFilter.colorMatrix(ColorMatrix(it.array)) }
      Column(
        Modifier
          .bounceEntrance(i)
          .clip(Shapes.m)
          .highlightClickable(role = Role.Tab) { onLook(look) }
          .padding(Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Canvas(
          Modifier
            .size(52.dp)
            .clip(Shapes.s)
            .border(2.dp, if (selected) colors.primary else Color.Transparent, Shapes.s)
        ) {
          drawRect(SWATCH, size = size, colorFilter = filter)
          drawCircle(Color(0xFFF4D1B0), radius = size.minDimension * 0.18f, center = Offset(size.width * 0.35f, size.height * 0.62f), colorFilter = filter)
          drawRect(Color(0xFF3E7D4A), topLeft = Offset(0f, size.height * 0.8f), size = Size(size.width, size.height * 0.2f), colorFilter = filter)
        }
        Spacer(Modifier.height(Space.xs))
        Text(
          look.label,
          style = AppText.Caption1.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
          color = if (selected) colors.primary else colors.onSurfaceVariant,
          fontSize = 12.sp
        )
      }
    }
  }
}

private val SWATCH = Brush.verticalGradient(listOf(Color(0xFF4FA3E0), Color(0xFFF2B84B), Color(0xFFE0604F)))
