package com.tacts.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
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
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ProTab(val label: String) { EV("EV"), ISO("ISO"), SHUTTER("S"), FOCUS("F"), WB("WB") }

/** The manual controls: a row of value chips, and a dial for the selected one under it. */
@Composable
fun ProPanel(camera: TactsCamera, tab: ProTab, onTab: (ProTab) -> Unit) {
  val m = camera.manual
  val caps = camera.caps
  val live = camera.live

  Column(
    Modifier
      .fillMaxWidth()
      .background(Ink.copy(alpha = 0.72f))
      .padding(horizontal = 16.dp, vertical = 10.dp)
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      ProChip(
        ProTab.EV,
        evLabel(m.evIndex, caps.evStep),
        manual = m.evIndex != 0,
        selected = tab == ProTab.EV,
        onTab
      )
      ProChip(ProTab.ISO, if (m.exposureAuto) "${live.iso ?: "–"}" else "${m.iso}", !m.exposureAuto, tab == ProTab.ISO, onTab)
      ProChip(
        ProTab.SHUTTER,
        if (m.exposureAuto) live.shutterNs?.let(::shutterLabel) ?: "–" else shutterLabel(m.shutterNs),
        !m.exposureAuto,
        tab == ProTab.SHUTTER,
        onTab
      )
      ProChip(
        ProTab.FOCUS,
        if (m.focusAuto) "AF" else focusLabel(m.focusDiopters),
        !m.focusAuto,
        tab == ProTab.FOCUS,
        onTab
      )
      ProChip(ProTab.WB, m.whiteBalance.label.take(4), m.whiteBalance != WhiteBalance.AUTO, tab == ProTab.WB, onTab)
    }
    Spacer(Modifier.height(10.dp))
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
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        caps.whiteBalances.forEach { wb ->
          Pill(wb.label, selected = m.whiteBalance == wb, accent = if (wb == WhiteBalance.AUTO) Ice else Amber) {
            camera.updateManual { it.copy(whiteBalance = wb) }
          }
        }
      }
    }
  }
}

private fun evLabel(index: Int, step: Float): String {
  val ev = index * step
  return if (abs(ev) < 0.01f) "±0" else (if (ev > 0) "+" else "−") + "%.1f".format(abs(ev))
}

@Composable
private fun ProChip(tab: ProTab, value: String, manual: Boolean, selected: Boolean, onTab: (ProTab) -> Unit) {
  Column(
    Modifier
      .clip(RoundedCornerShape(10.dp))
      .background(if (selected) InkHigh else Color.Transparent)
      .clickable { onTab(tab) }
      .padding(horizontal = 10.dp, vertical = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(tab.label, color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    Text(
      value,
      color = if (manual) Amber else TextPrimary,
      fontSize = 14.sp,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Medium
    )
  }
}

@Composable
private fun EvDial(camera: TactsCamera, m: ManualSettings) {
  val caps = camera.caps
  if (caps.evRange.first == caps.evRange.last) return Unsupported("This camera has no exposure compensation")
  if (!m.exposureAuto) return Unsupported("Exposure compensation works with auto ISO and shutter")
  DialRow(label = "EV ${evLabel(m.evIndex, caps.evStep)}", leading = {
    Pill("0", selected = m.evIndex == 0, accent = Ice) { camera.updateManual { it.copy(evIndex = 0) } }
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
    Pill("A", selected = auto, accent = Ice) { camera.updateManual { it.copy(exposureAuto = !it.exposureAuto) } }
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
    Pill("AF", selected = m.focusAuto, accent = Ice) { camera.updateManual { it.copy(focusAuto = !it.focusAuto) } }
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
  Row(verticalAlignment = Alignment.CenterVertically) {
    leading()
    Spacer(Modifier.width(10.dp))
    Box(Modifier.weight(1f)) { slider() }
    Spacer(Modifier.width(10.dp))
    Text(
      label,
      color = TextPrimary,
      fontSize = 13.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.width(72.dp)
    )
  }
}

@Composable
private fun sliderColors(manual: Boolean) = SliderDefaults.colors(
  thumbColor = if (manual) Amber else TextPrimary,
  activeTrackColor = if (manual) Amber else Ice,
  inactiveTrackColor = InkLine,
  activeTickColor = Color.Transparent,
  inactiveTickColor = Color.Transparent
)

@Composable
private fun Unsupported(text: String = "This camera has no manual sensor control") {
  Text(text, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 14.dp))
}

@Composable
fun Pill(text: String, selected: Boolean, accent: Color = Ice, onClick: () -> Unit) {
  Text(
    text,
    color = if (selected) Ink else TextPrimary,
    fontSize = 13.sp,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier
      .clip(CircleShape)
      .background(if (selected) accent else InkHigh)
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 7.dp)
  )
}

/** The look picker: a swatch for each look, drawn through its own colour matrix. */
@Composable
fun LookStrip(current: Look, onLook: (Look) -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .background(Ink.copy(alpha = 0.72f))
      .horizontalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    Look.entries.forEach { look ->
      val filter = look.matrix()?.let { ColorFilter.colorMatrix(ColorMatrix(it.array)) }
      Column(
        Modifier.clickable { onLook(look) },
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Canvas(
          Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, if (look == current) Ice else Color.Transparent, RoundedCornerShape(12.dp))
        ) {
          drawRect(SWATCH, size = size, colorFilter = filter)
          drawCircle(Color(0xFFF4D1B0), radius = size.minDimension * 0.18f, center = Offset(size.width * 0.35f, size.height * 0.62f), colorFilter = filter)
          drawRect(Color(0xFF3E7D4A), topLeft = Offset(0f, size.height * 0.8f), size = Size(size.width, size.height * 0.2f), colorFilter = filter)
        }
        Spacer(Modifier.height(4.dp))
        Text(look.label, color = if (look == current) Ice else TextSecondary, fontSize = 11.sp)
      }
    }
  }
}

private val SWATCH = Brush.verticalGradient(listOf(Color(0xFF4FA3E0), Color(0xFFF2B84B), Color(0xFFE0604F)))
