package com.tacts.camera.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacts.camera.camera.CaptureMode
import com.tacts.camera.camera.DynamicRangeChoice
import com.tacts.camera.camera.PhotoFormat
import com.tacts.camera.camera.TactsCamera
import com.tacts.camera.camera.formatFactor
import com.tacts.camera.ui.theme.AppMotion
import com.tacts.camera.ui.theme.AppText
import com.tacts.camera.ui.theme.JetBrainsMono
import com.tacts.camera.ui.theme.Shapes
import com.tacts.camera.ui.theme.Space
import com.tacts.camera.ui.theme.floatingSurface
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/** Damping 1, response 0.3 s: stiffness (2π / 0.3)². */
private val ChromeSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 439f)

// ---- format panel ----

/**
 * The recording format in one glass card under the format pills, as the iOS 26 camera shows it: a row
 * per choice (format, resolution, frame rate), every option the camera offers written out, the chosen
 * one in the accent. It grows from the pills it belongs to.
 */
@Composable
internal fun FormatPanel(camera: TactsCamera, visible: Boolean, modifier: Modifier = Modifier) {
  val reduced = AppMotion.reduced
  val origin = TransformOrigin(0f, 0f)
  AnimatedVisibility(
    visible = visible,
    modifier = modifier,
    enter = if (reduced) fadeIn(ChromeSpring) else fadeIn(ChromeSpring) + scaleIn(ChromeSpring, initialScale = 0.85f, transformOrigin = origin),
    exit = if (reduced) fadeOut(ChromeSpring) else fadeOut(ChromeSpring) + scaleOut(ChromeSpring, targetScale = 0.85f, transformOrigin = origin)
  ) {
    val s = camera.settings
    val caps = camera.caps
    Column(
      Modifier
        .padding(horizontal = Space.m)
        .widthIn(max = 420.dp)
        .fillMaxWidth()
        .floatingSurface(Shapes.m, thick = true)
        .padding(horizontal = Space.m, vertical = Space.s),
      verticalArrangement = Arrangement.spacedBy(Space.xxs)
    ) {
      when (s.mode) {
        CaptureMode.PHOTO -> {
          FormatRow("FORMAT", PhotoFormat.entries.filter { it in caps.photoFormats }, s.photoFormat, { it.shortLabel }) { f ->
            camera.update { it.copy(photoFormat = f) }
          }
        }
        CaptureMode.VIDEO -> {
          FormatRow("RANGE", DynamicRangeChoice.entries.filter { it in caps.dynamicRanges }, s.dynamicRange, { it.shortLabel }) { r ->
            camera.update { it.copy(dynamicRange = r) }
          }
          FormatRow("RESOLUTION", caps.videoQualities, s.videoQuality, { it.label }) { q ->
            camera.update { it.copy(videoQuality = q) }
          }
          FormatRow("FPS", caps.frameRates, s.frameRate, { "$it" }) { f -> camera.update { it.copy(frameRate = f) } }
        }
        CaptureMode.SLOW_MO -> FormatRow("FPS", caps.highSpeedFps, s.slowMoFps, { "$it" }) { f -> camera.update { it.copy(slowMoFps = f) } }
        CaptureMode.RAW_VIDEO -> FormatRow("FPS", caps.frameRates, s.frameRate, { "$it" }) { f -> camera.update { it.copy(frameRate = f) } }
      }
    }
  }
}

private val DynamicRangeChoice.shortLabel: String
  get() = when (this) {
    DynamicRangeChoice.SDR -> "SDR"
    DynamicRangeChoice.HLG -> "HLG"
    DynamicRangeChoice.HDR10 -> "HDR10"
    DynamicRangeChoice.HDR10_PLUS -> "HDR10+"
    DynamicRangeChoice.DOLBY_VISION -> "DV"
  }

@Composable
private fun <T> FormatRow(title: String, options: List<T>, chosen: T, label: (T) -> String, onChoose: (T) -> Unit) {
  if (options.isEmpty()) return
  val colors = MaterialTheme.colorScheme
  Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(
      title,
      style = AppText.Caption2.copy(fontFamily = JetBrainsMono, letterSpacing = 0.6.sp),
      color = colors.onSurfaceVariant,
      modifier = Modifier.width(92.dp)
    )
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.xxs), verticalAlignment = Alignment.CenterVertically) {
      options.forEach { option ->
        val selected = option == chosen
        Row(
          Modifier
            .height(40.dp)
            .clip(Shapes.pill)
            .clickable(role = Role.RadioButton) { onChoose(option) }
            .semantics { this.selected = selected; contentDescription = "$title ${label(option)}" }
            .padding(horizontal = Space.s),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // The chosen option carries a dot, so it reads without colour too.
          Box(Modifier.size(5.dp).clip(CircleShape).then(if (selected) Modifier.background(colors.primary) else Modifier))
          Spacer(Modifier.width(Space.xxs))
          Text(
            label(option),
            style = AppText.Caption1.copy(fontFamily = JetBrainsMono, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
            color = if (selected) colors.primary else colors.onSurface,
            maxLines = 1
          )
        }
      }
    }
  }
}

// ---- mode bar ----

/**
 * The modes as the iOS 26 camera shows them: only photo and video (and the mode in use, when it is
 * another), so the bar stays small. Holding a mode, sliding across the bar or tapping the "+" shows
 * every mode; picking one, or four seconds without a touch, folds it back.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CompactModeSwitch(
  camera: TactsCamera,
  modes: List<CaptureMode>,
  label: (CaptureMode) -> String,
  modifier: Modifier
) {
  val colors = MaterialTheme.colorScheme
  val current = camera.settings.mode
  var expanded by remember { mutableStateOf(false) }
  var touch by remember { mutableIntStateOf(0) }
  val compact = modes.filter { it == CaptureMode.PHOTO || it == CaptureMode.VIDEO || it == current }
  val hidden = modes.size > compact.size
  val shown = if (expanded) modes else compact
  val slop = with(LocalDensity.current) { 10.dp.toPx() }

  LaunchedEffect(expanded, touch) {
    if (expanded) {
      delay(4000)
      expanded = false
    }
  }

  Box(modifier, contentAlignment = Alignment.Center) {
    Row(
      Modifier
        .floatingSurface(Shapes.pill)
        .then(if (AppMotion.reduced) Modifier else Modifier.animateContentSize(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 439f)))
        .pointerInput(hidden) {
          if (!hidden) return@pointerInput
          var dragged = 0f
          detectHorizontalDragGestures(
            onDragStart = { dragged = 0f },
            onHorizontalDrag = { _, dx ->
              dragged += dx
              if (abs(dragged) > slop && !expanded) expanded = true
              touch++
            }
          )
        }
        .padding(Space.xxs),
      verticalAlignment = Alignment.CenterVertically
    ) {
      shown.forEach { m ->
        val selected = current == m
        Box(
          Modifier
            .height(44.dp)
            .clip(Shapes.pill)
            .then(if (selected) Modifier.background(colors.onSurface.copy(alpha = 0.14f), Shapes.pill) else Modifier)
            .combinedClickable(
              enabled = !camera.isRecording,
              role = Role.Tab,
              onLongClickLabel = if (hidden) "Show all modes" else null,
              onLongClick = {
                if (hidden) {
                  expanded = true
                  touch++
                }
              },
              onClick = {
                camera.update { it.copy(mode = m) }
                expanded = false
              }
            )
            .semantics { this.selected = selected; contentDescription = m.label }
            .padding(horizontal = Space.m),
          contentAlignment = Alignment.Center
        ) {
          Text(
            label(m),
            style = AppText.Caption1.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
            color = when {
              selected -> colors.primary
              camera.isRecording -> colors.onSurfaceVariant.copy(alpha = 0.5f)
              else -> colors.onSurface
            },
            maxLines = 1
          )
        }
      }
      if (hidden && !expanded) {
        Box(
          Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = !camera.isRecording, role = Role.Button) {
              expanded = true
              touch++
            }
            .semantics { contentDescription = "Show all modes" },
          contentAlignment = Alignment.Center
        ) {
          Text("+", style = AppText.Body.copy(fontWeight = FontWeight.Medium), color = colors.onSurfaceVariant)
        }
      }
    }
  }
}

// ---- zoom ----

/**
 * The lens buttons, and the zoom wheel of the iOS 26 camera behind them: hold a lens, or slide across
 * the buttons, and an arc of ticks takes their place, turning under the finger; the zoom follows the
 * finger 1:1 on a log scale, so every doubling takes the same distance. A tick is felt at each lens.
 * The wheel folds back 1.5 s after the finger lifts.
 */
@Composable
internal fun ZoomControl(camera: TactsCamera, chips: @Composable (Modifier) -> Unit) {
  val haptics = LocalHapticFeedback.current
  val density = LocalDensity.current
  var wheel by remember { mutableStateOf(false) }
  var lifted by remember { mutableIntStateOf(0) }
  // One doubling of zoom per 120 dp of travel.
  val pxPerLn = with(density) { 120.dp.toPx() } / ln(2f)
  val slop = with(density) { 10.dp.toPx() }
  val pinned = camera.settings.cameraId
  val marks = camera.lensStops.filter { it.cameraId == pinned }.map { it.zoom }

  LaunchedEffect(wheel, lifted) {
    if (wheel && lifted > 0) {
      delay(1500)
      wheel = false
    }
  }

  fun turn(dx: Float) {
    val before = camera.zoomRatio
    val next = exp(ln(before) - dx / pxPerLn).coerceIn(camera.minZoom, camera.maxZoom)
    camera.setZoom(next)
    if (marks.any { (before - it) * (next - it) < 0f || (next == it && before != it) }) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
  }

  val gestures = Modifier
    .pointerInput(Unit) {
      detectDragGesturesAfterLongPress(
        onDragStart = {
          wheel = true
          lifted = 0
          haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        },
        onDrag = { change, drag ->
          change.consume()
          turn(drag.x)
        },
        onDragEnd = { lifted++ },
        onDragCancel = { lifted++ }
      )
    }
    .pointerInput(Unit) {
      var travelled = 0f
      detectHorizontalDragGestures(
        onDragStart = { travelled = 0f },
        onHorizontalDrag = { change, dx ->
          travelled += dx
          if (!wheel && abs(travelled) > slop) {
            wheel = true
            lifted = 0
          }
          if (wheel) {
            change.consume()
            turn(dx)
          }
        },
        onDragEnd = { lifted++ },
        onDragCancel = { lifted++ }
      )
    }

  Box(gestures, contentAlignment = Alignment.BottomCenter) {
    if (wheel) ZoomWheel(camera, marks) else chips(Modifier)
  }
}

@Composable
private fun ZoomWheel(camera: TactsCamera, marks: List<Float>) {
  val colors = MaterialTheme.colorScheme
  val density = LocalDensity.current
  val zoom = camera.zoomRatio
  val lnMin = ln(camera.minZoom)
  val lnMax = ln(camera.maxZoom)
  val shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 6f)
  val measurer = rememberTextMeasurer()
  val markStyle = AppText.Caption2.copy(fontFamily = JetBrainsMono, color = colors.onSurface)
  val factorOf = camera.zoomFactor / zoom
  Column(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = Space.l)
      .semantics { contentDescription = "Zoom ${formatFactor(camera.zoomFactor)} times" },
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(
      formatFactor(camera.zoomFactor) + "×",
      style = AppText.Subhead.copy(fontFamily = JetBrainsMono, fontWeight = FontWeight.SemiBold, shadow = shadow),
      color = colors.primary
    )
    Spacer(Modifier.height(Space.xxs))
    Canvas(
      Modifier
        .fillMaxWidth()
        .height(72.dp)
        .floatingSurface(androidx.compose.foundation.shape.RoundedCornerShape(topStartPercent = 100, topEndPercent = 100))
    ) {
      // The ticks sit on an arc whose centre is below the wheel; the current zoom is at the top.
      val radius = size.width * 0.75f
      val centre = Offset(size.width / 2f, radius + with(density) { 14.dp.toPx() })
      val pxPerLn = with(density) { 120.dp.toPx() } / ln(2f)
      val now = ln(zoom)
      val step = 0.035f
      var v = lnMin
      while (v <= lnMax + 1e-4f) {
        val angle = ((v - now) * pxPerLn / radius) - (PI / 2).toFloat()
        // Lens marks are drawn on their own below; a regular tick next to one would double it.
        val nearMark = marks.any { abs(ln(it) - v) < step / 2f }
        if (!nearMark && angle > -PI.toFloat() + 0.25f && angle < -0.25f) {
          val len = with(density) { 7.dp.toPx() }
          val outer = Offset(centre.x + cos(angle) * radius, centre.y + sin(angle) * radius)
          val inner = Offset(centre.x + cos(angle) * (radius - len), centre.y + sin(angle) * (radius - len))
          drawLine(colors.onSurface.copy(alpha = 0.45f), inner, outer, strokeWidth = with(density) { 1.dp.toPx() }, cap = StrokeCap.Round)
        }
        v += step
      }
      // Lens marks snap ticks to their exact place, between the regular steps.
      marks.forEach { m ->
        val angle = ((ln(m) - now) * pxPerLn / radius) - (PI / 2).toFloat()
        if (angle > -PI.toFloat() + 0.25f && angle < -0.25f) {
          val len = with(density) { 14.dp.toPx() }
          drawLine(
            colors.onSurface,
            Offset(centre.x + cos(angle) * (radius - len), centre.y + sin(angle) * (radius - len)),
            Offset(centre.x + cos(angle) * radius, centre.y + sin(angle) * radius),
            strokeWidth = with(density) { 1.5.dp.toPx() },
            cap = StrokeCap.Round
          )
          if (angle < -PI.toFloat() + 0.45f || angle > -0.45f) return@forEach
          val text = measurer.measure(formatFactor(m * factorOf), markStyle)
          val r = radius - len - with(density) { 10.dp.toPx() }
          val at = Offset(centre.x + cos(angle) * r, centre.y + sin(angle) * r)
          drawText(text, topLeft = Offset(at.x - text.size.width / 2f, at.y - text.size.height / 2f))
        }
      }
      // The needle: where the zoom is read.
      val top = Offset(centre.x, centre.y - radius - with(density) { 4.dp.toPx() })
      drawLine(colors.primary, top, Offset(centre.x, top.y + with(density) { 22.dp.toPx() }), strokeWidth = with(density) { 2.dp.toPx() }, cap = StrokeCap.Round)
    }
  }
}
