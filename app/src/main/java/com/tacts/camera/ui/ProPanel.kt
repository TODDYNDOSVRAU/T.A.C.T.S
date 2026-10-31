package com.tacts.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Close
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
import com.tacts.camera.camera.CaptureMode
import com.tacts.camera.camera.FrameGuide
import com.tacts.camera.camera.GridStyle
import com.tacts.camera.camera.SHUTTER_ANGLES
import com.tacts.camera.camera.Scope
import com.tacts.camera.camera.ShutterMode
import com.tacts.camera.camera.TactsCamera
import com.tacts.camera.camera.WhiteBalance
import com.tacts.camera.camera.WhiteBalanceMode
import com.tacts.camera.camera.angleLabel
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
import kotlin.math.ln
import kotlin.math.roundToInt

enum class ProTab(val label: String) { EV("EV"), ISO("ISO"), SHUTTER("S"), FOCUS("F"), WB("WB") }

private fun isVideo(camera: TactsCamera) = camera.settings.mode != CaptureMode.PHOTO

/** One manual value as shown on screen: its text, and whether it is set by hand (drawn in the accent). */
data class ProReading(val tab: ProTab, val value: String, val manual: Boolean)

/** The five manual values, as the camera uses them right now. */
fun proReadings(camera: TactsCamera): List<ProReading> {
  val s = camera.settings
  val caps = camera.caps
  val live = camera.live
  val angle = isVideo(camera) && s.shutterMode == ShutterMode.ANGLE
  return listOf(
    ProReading(ProTab.EV, if (s.aeLock) "AE-L" else evLabel(s.evIndex, caps.evStep), s.evIndex != 0 || s.aeLock),
    ProReading(ProTab.ISO, if (s.exposureAuto) "${live.iso ?: "–"}" else "${s.iso}", !s.exposureAuto),
    ProReading(
      ProTab.SHUTTER,
      when {
        angle && !s.exposureAuto -> angleLabel(s.shutterAngle)
        s.exposureAuto -> live.shutterNs?.let(::shutterLabel) ?: "–"
        else -> shutterLabel(s.shutterNs)
      },
      !s.exposureAuto
    ),
    ProReading(ProTab.FOCUS, if (s.focusAuto) "AF" else focusLabel(s.focusDiopters), !s.focusAuto),
    ProReading(
      ProTab.WB,
      when (s.wbMode) {
        WhiteBalanceMode.AUTO -> if (s.awbLock) "AWB-L" else "AWB"
        WhiteBalanceMode.PRESET -> "${s.whiteBalance.kelvin}K"
        WhiteBalanceMode.KELVIN -> "${s.kelvin}K"
      },
      s.wbMode != WhiteBalanceMode.AUTO || s.awbLock
    )
  )
}

/** A soft shadow under text drawn straight on the picture, so it reads over bright scenes. */
private val READABLE = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 6f)

/** What each dial sets, as the sheet's title. */
val ProTab.title: String
  get() = when (this) {
    ProTab.EV -> "EXPOSURE"
    ProTab.ISO -> "ISO"
    ProTab.SHUTTER -> "SHUTTER SPEED"
    ProTab.FOCUS -> "FOCUS"
    ProTab.WB -> "WHITE BALANCE"
  }

/**
 * The manual controls as a glass sheet: the selected value's dial under a title, with a close button.
 * The values themselves are on the readout row at the top of the screen, which picks the tab.
 */
@Composable
fun ProPanel(camera: TactsCamera, tab: ProTab, onClose: () -> Unit, modifier: Modifier = Modifier) {
  val s = camera.settings
  val caps = camera.caps
  val angle = isVideo(camera) && s.shutterMode == ShutterMode.ANGLE

  Column(
    modifier
      .fillMaxWidth()
      .padding(horizontal = Space.s)
      .bounceEntrance()
      .floatingSurface(Shapes.l)
      .padding(horizontal = Space.m, vertical = Space.s)
  ) {
    Box(Modifier.fillMaxWidth().height(40.dp)) {
      com.tacts.camera.ui.components.IconAction(
        androidx.compose.material.icons.Icons.Rounded.Close,
        contentDescription = "Close",
        onClick = onClose,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.align(Alignment.CenterStart)
      )
      Text(
        tab.title,
        style = AppText.Caption1.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.align(Alignment.Center)
      )
    }
    Spacer(Modifier.height(Space.xs))
    when (tab) {
      ProTab.EV -> EvDial(camera)
      ProTab.ISO -> if (caps.isoValues.isEmpty()) Unsupported() else StepDial(
        auto = s.exposureAuto,
        autoLabel = "A",
        count = caps.isoValues.size,
        index = nearestIndex(caps.isoValues, s.iso) { a, b -> abs(a - b).toDouble() },
        label = { "ISO ${caps.isoValues[it]}" },
        onAuto = { camera.update { it.copy(exposureAuto = !it.exposureAuto) } },
        onIndex = { i -> camera.update { it.copy(exposureAuto = false, iso = caps.isoValues[i]) } }
      )
      ProTab.SHUTTER -> when {
        caps.shutterValues.isEmpty() -> Unsupported()
        angle -> StepDial(
          auto = s.exposureAuto,
          autoLabel = "A",
          count = SHUTTER_ANGLES.size,
          index = SHUTTER_ANGLES.indexOf(s.shutterAngle).coerceAtLeast(0),
          label = { "${angleLabel(SHUTTER_ANGLES[it])} · ${shutterLabel(s.copy(shutterAngle = SHUTTER_ANGLES[it]).angleExposureNs())}" },
          onAuto = { camera.update { it.copy(exposureAuto = !it.exposureAuto) } },
          onIndex = { i -> camera.update { it.copy(exposureAuto = false, shutterAngle = SHUTTER_ANGLES[i]) } }
        )
        else -> StepDial(
          auto = s.exposureAuto,
          autoLabel = "A",
          count = caps.shutterValues.size,
          index = nearestIndex(caps.shutterValues, s.shutterNs) { a, b -> abs(ln(a.toDouble() / b)) },
          label = { shutterLabel(caps.shutterValues[it]) },
          onAuto = { camera.update { it.copy(exposureAuto = !it.exposureAuto) } },
          onIndex = { i -> camera.update { it.copy(exposureAuto = false, shutterNs = caps.shutterValues[i]) } }
        )
      }
      ProTab.FOCUS -> if (caps.minFocusDiopters <= 0f) Unsupported("This lens has fixed focus") else FocusDial(camera)
      ProTab.WB -> WbDial(camera)
    }
  }
}

private fun evLabel(index: Int, step: Float): String {
  val ev = index * step
  return if (abs(ev) < 0.01f) "±0" else (if (ev > 0) "+" else "−") + "%.1f".format(abs(ev))
}

/**
 * The readout row at the top of the screen: each value over its name, in the accent when set by hand.
 * Tapping one opens its dial; the open one sits on a quiet fill.
 */
@Composable
fun ProReadoutRow(camera: TactsCamera, selected: ProTab?, onTab: (ProTab) -> Unit, modifier: Modifier = Modifier) {
  val colors = MaterialTheme.colorScheme
  Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
    proReadings(camera).forEachIndexed { i, r ->
      val isSelected = r.tab == selected
      Column(
        Modifier
          .bounceEntrance(i)
          .clip(Shapes.s)
          .then(if (isSelected) Modifier.background(colors.onSurface.copy(alpha = 0.14f), Shapes.s) else Modifier)
          .selectable(selected = isSelected, role = Role.Tab) { onTab(r.tab) }
          .padding(horizontal = Space.s, vertical = Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          r.value,
          style = AppText.Subhead.copy(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, shadow = READABLE),
          color = if (r.manual) colors.primary else colors.onSurface,
          maxLines = 1
        )
        Text(r.tab.label, style = AppText.Caption2.copy(fontWeight = FontWeight.SemiBold, shadow = READABLE), color = colors.onSurface.copy(alpha = 0.7f))
      }
    }
  }
}

@Composable
private fun EvDial(camera: TactsCamera) {
  val s = camera.settings
  val caps = camera.caps
  if (caps.evRange.first == caps.evRange.last) return Unsupported("This camera has no exposure compensation")
  if (!s.exposureAuto) return Unsupported("Exposure compensation works with auto ISO and shutter")
  DialRow(label = "EV ${evLabel(s.evIndex, caps.evStep)}", leading = {
    FilterChip("AE-L", selected = s.aeLock, onClick = { camera.update { it.copy(aeLock = !it.aeLock) } })
  }) {
    val span = caps.evRange.last - caps.evRange.first
    Slider(
      value = s.evIndex.toFloat(),
      onValueChange = { v -> camera.update { it.copy(evIndex = v.roundToInt()) } },
      valueRange = caps.evRange.first.toFloat()..caps.evRange.last.toFloat(),
      steps = (span - 1).coerceAtLeast(0),
      colors = sliderColors(s.evIndex != 0)
    )
  }
}

@Composable
private fun StepDial(
  auto: Boolean,
  autoLabel: String,
  count: Int,
  index: Int,
  label: (Int) -> String,
  onAuto: () -> Unit,
  onIndex: (Int) -> Unit
) {
  DialRow(label = if (auto) "AUTO" else label(index), leading = { FilterChip(autoLabel, selected = auto, onClick = onAuto) }) {
    Slider(
      value = index.toFloat(),
      onValueChange = { v -> onIndex(v.roundToInt().coerceIn(0, count - 1)) },
      valueRange = 0f..(count - 1).coerceAtLeast(1).toFloat(),
      steps = (count - 2).coerceAtLeast(0),
      colors = sliderColors(!auto)
    )
  }
}

/** Manual focus, plus the A and B marks of a focus pull and the button that runs it. */
@Composable
private fun FocusDial(camera: TactsCamera) {
  val s = camera.settings
  val max = camera.caps.minFocusDiopters
  Column {
    DialRow(label = if (s.focusAuto) "AUTO" else focusLabel(s.focusDiopters), leading = {
      FilterChip("AF", selected = s.focusAuto, onClick = { camera.update { it.copy(focusAuto = !it.focusAuto) } })
    }) {
      // Left is infinity, right is the closest the lens can focus.
      Slider(
        value = s.focusDiopters.coerceIn(0f, max),
        onValueChange = { v -> camera.update { it.copy(focusAuto = false, focusDiopters = v) } },
        valueRange = 0f..max,
        colors = sliderColors(!s.focusAuto)
      )
    }
    Row(Modifier.bounceEntrance(6).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
      FilterChip(
        "A ${s.focusPullA?.let(::focusLabel) ?: "set"}",
        selected = s.focusPullA != null,
        onClick = { camera.update { it.copy(focusPullA = if (it.focusAuto) camera.live.focusDiopters ?: 0f else it.focusDiopters) } }
      )
      FilterChip(
        "B ${s.focusPullB?.let(::focusLabel) ?: "set"}",
        selected = s.focusPullB != null,
        onClick = { camera.update { it.copy(focusPullB = if (it.focusAuto) camera.live.focusDiopters ?: 0f else it.focusDiopters) } }
      )
      FilterChip(
        "${s.focusPullMs / 1000f}s",
        selected = false,
        onClick = { camera.update { it.copy(focusPullMs = PULL_DURATIONS[(PULL_DURATIONS.indexOf(it.focusPullMs) + 1) % PULL_DURATIONS.size]) } }
      )
      FilterChip(
        if (camera.focusPulling) "Pulling…" else "Pull A↔B",
        selected = camera.focusPulling,
        enabled = s.focusPullA != null && s.focusPullB != null,
        onClick = { camera.pullFocus() }
      )
    }
  }
}

private val PULL_DURATIONS = listOf(500, 1000, 2000, 3000, 5000, 8000)

/** White balance: auto (with lock), the presets, or Kelvin and tint. */
@Composable
private fun WbDial(camera: TactsCamera) {
  val s = camera.settings
  val caps = camera.caps
  Column {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xxs)) {
      FilterChip("Auto", selected = s.wbMode == WhiteBalanceMode.AUTO && !s.awbLock, onClick = { camera.update { it.copy(wbMode = WhiteBalanceMode.AUTO, awbLock = false) } }, modifier = Modifier.bounceEntrance(0))
      FilterChip("AWB-L", selected = s.wbMode == WhiteBalanceMode.AUTO && s.awbLock, onClick = { camera.update { it.copy(wbMode = WhiteBalanceMode.AUTO, awbLock = true) } }, modifier = Modifier.bounceEntrance(1))
      FilterChip("Kelvin", selected = s.wbMode == WhiteBalanceMode.KELVIN, onClick = { camera.update { it.copy(wbMode = WhiteBalanceMode.KELVIN) } }, modifier = Modifier.bounceEntrance(2))
      WhiteBalance.entries.filter { it.awbMode in caps.awbModes || caps.hasManualWbGains }.forEachIndexed { i, wb ->
        FilterChip(
          wb.label,
          selected = s.wbMode == WhiteBalanceMode.PRESET && s.whiteBalance == wb,
          onClick = { camera.update { it.copy(wbMode = WhiteBalanceMode.PRESET, whiteBalance = wb, kelvin = wb.kelvin) } },
          modifier = Modifier.bounceEntrance(i + 3)
        )
      }
    }
    if (s.wbMode == WhiteBalanceMode.KELVIN) {
      DialRow(label = "${s.kelvin} K", leading = { KelvinSwatch(s.kelvin) }) {
        Slider(
          value = s.kelvin.toFloat(),
          onValueChange = { v -> camera.update { it.copy(kelvin = (v / 50f).roundToInt() * 50) } },
          valueRange = 2000f..10000f,
          colors = sliderColors(true)
        )
      }
      DialRow(label = "Tint ${if (s.tint > 0) "+" else ""}${s.tint}", leading = { Spacer(Modifier.width(34.dp)) }) {
        Slider(
          value = s.tint.toFloat(),
          onValueChange = { v -> camera.update { it.copy(tint = v.roundToInt()) } },
          valueRange = -50f..50f,
          colors = sliderColors(s.tint != 0)
        )
      }
      if (!caps.hasManualWbGains) {
        Text("This camera has no manual colour gains: the nearest preset is used.", style = AppText.Caption1, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun KelvinSwatch(kelvin: Int) {
  val t = ((kelvin - 2000) / 8000f).coerceIn(0f, 1f)
  val warm = Color(0xFFFFA24A)
  val cool = Color(0xFF9CC8FF)
  Box(
    Modifier
      .size(34.dp)
      .clip(Shapes.pill)
      .border(1.dp, MaterialTheme.colorScheme.outline, Shapes.pill)
  ) {
    Canvas(Modifier.size(34.dp)) { drawRect(lerp(warm, cool, t)) }
  }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
  a.red + (b.red - a.red) * t,
  a.green + (b.green - a.green) * t,
  a.blue + (b.blue - a.blue) * t
)

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
      modifier = Modifier.width(96.dp),
      maxLines = 1
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

/** Quick switches for the monitoring tools, like the assist menu of a cinema camera. */
@Composable
fun MonitorPanel(camera: TactsCamera, modifier: Modifier = Modifier) {
  val s = camera.settings
  Column(
    modifier
      .fillMaxWidth()
      .padding(horizontal = Space.s)
      .bounceEntrance()
      .floatingSurface(Shapes.l)
      .padding(Space.s)
  ) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
      FilterChip("Peaking", s.peaking, { camera.update { it.copy(peaking = !it.peaking) } }, Modifier.bounceEntrance(0))
      FilterChip("Zebras ${s.zebraLevel}%", s.zebras, { camera.update { it.copy(zebras = !it.zebras) } }, Modifier.bounceEntrance(1))
      FilterChip("False colour", s.falseColor, { camera.update { it.copy(falseColor = !it.falseColor) } }, Modifier.bounceEntrance(2))
      FilterChip("Level", s.level, { camera.update { it.copy(level = !it.level) } }, Modifier.bounceEntrance(3))
      FilterChip("Centre", s.centerMark, { camera.update { it.copy(centerMark = !it.centerMark) } }, Modifier.bounceEntrance(4))
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
      Scope.entries.forEachIndexed { i, sc ->
        FilterChip(if (sc == Scope.NONE) "No scope" else sc.label, s.scope == sc, { camera.update { it.copy(scope = sc) } }, Modifier.bounceEntrance(i))
      }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
      FrameGuide.entries.forEachIndexed { i, g ->
        FilterChip(if (g == FrameGuide.NONE) "No guide" else g.label, s.frameGuide == g, { camera.update { it.copy(frameGuide = g) } }, Modifier.bounceEntrance(i))
      }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
      GridStyle.entries.forEachIndexed { i, g ->
        FilterChip(if (g == GridStyle.OFF) "No grid" else g.label, s.grid == g, { camera.update { it.copy(grid = g) } }, Modifier.bounceEntrance(i))
      }
    }
  }
}

/** The look picker: a swatch for each look, then the LUTs. */
@Composable
fun LookStrip(camera: TactsCamera, modifier: Modifier = Modifier) {
  val colors = MaterialTheme.colorScheme
  val s = camera.settings
  Column(
    modifier
      .fillMaxWidth()
      .padding(horizontal = Space.s)
      .bounceEntrance()
      .floatingSurface(Shapes.l)
      .padding(vertical = Space.s)
  ) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.xxs)) {
      Look.entries.forEachIndexed { i, look ->
        val selected = look.name == s.look
        val filter = look.matrix()?.let { ColorFilter.colorMatrix(ColorMatrix(it.array)) }
        Column(
          Modifier
            .bounceEntrance(i)
            .clip(Shapes.m)
            .highlightClickable(role = Role.Tab) { camera.update { it.copy(look = look.name) } }
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
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Space.s)) {
      FilterChip("No LUT", s.lutName == null, { camera.update { it.copy(lutName = null) } }, Modifier.bounceEntrance(0))
      camera.luts.names().forEachIndexed { i, name ->
        FilterChip(name, s.lutName == name, { camera.update { it.copy(lutName = name) } }, Modifier.bounceEntrance(i + 1))
      }
    }
  }
}

private val SWATCH = Brush.verticalGradient(listOf(Color(0xFF4FA3E0), Color(0xFFF2B84B), Color(0xFFE0604F)))
