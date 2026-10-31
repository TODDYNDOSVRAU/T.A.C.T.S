package com.tacts.camera.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tacts.camera.camera.FrameGuide
import com.tacts.camera.camera.GridStyle
import com.tacts.camera.camera.SafeArea
import com.tacts.camera.camera.Scope
import com.tacts.camera.monitor.MonitorFrame
import com.tacts.camera.ui.theme.AppText
import com.tacts.camera.ui.theme.JetBrainsMono
import com.tacts.camera.ui.theme.Shapes
import com.tacts.camera.ui.theme.Space
import com.tacts.camera.ui.theme.entrance
import com.tacts.camera.ui.theme.floatingSurface
import kotlin.math.abs
import kotlin.math.atan2

private val Line = Color.White.copy(alpha = 0.32f)

@Composable
fun GridOverlay(style: GridStyle) {
  if (style == GridStyle.OFF) return
  Canvas(Modifier.fillMaxSize()) {
    val w = size.width
    val h = size.height
    fun v(x: Float) = drawLine(Line, Offset(x, 0f), Offset(x, h), 1f)
    fun hz(y: Float) = drawLine(Line, Offset(0f, y), Offset(w, y), 1f)
    when (style) {
      GridStyle.THIRDS -> for (i in 1..2) { v(w * i / 3f); hz(h * i / 3f) }
      GridStyle.GOLDEN -> listOf(0.382f, 0.618f).forEach { v(w * it); hz(h * it) }
      GridStyle.CROSSHAIR -> { v(w / 2f); hz(h / 2f) }
      GridStyle.SQUARES -> {
        val cell = w / 6f
        var x = cell
        while (x < w) { v(x); x += cell }
        var y = cell
        while (y < h) { hz(y); y += cell }
      }
      GridStyle.OFF -> Unit
    }
  }
}

/** The frame guide masks what falls outside the chosen delivery ratio, like a monitor's markers. */
@Composable
fun FrameGuideOverlay(guide: FrameGuide, safe: SafeArea, centerMark: Boolean) {
  Canvas(Modifier.fillMaxSize()) {
    val w = size.width
    val h = size.height
    var left = 0f
    var top = 0f
    var gw = w
    var gh = h
    if (guide != FrameGuide.NONE) {
      if (w / h > guide.ratio) {
        gw = h * guide.ratio
        left = (w - gw) / 2f
      } else {
        gh = w / guide.ratio
        top = (h - gh) / 2f
      }
      val mask = Color.Black.copy(alpha = 0.55f)
      if (top > 0f) {
        drawRect(mask, Offset(0f, 0f), Size(w, top))
        drawRect(mask, Offset(0f, top + gh), Size(w, h - top - gh))
      }
      if (left > 0f) {
        drawRect(mask, Offset(0f, 0f), Size(left, h))
        drawRect(mask, Offset(left + gw, 0f), Size(w - left - gw, h))
      }
      drawRect(Color.White.copy(alpha = 0.6f), Offset(left, top), Size(gw, gh), style = Stroke(1.5f))
    }
    if (safe != SafeArea.OFF) {
      val sw = gw * safe.fraction
      val sh = gh * safe.fraction
      drawRect(
        Color.White.copy(alpha = 0.5f),
        Offset(left + (gw - sw) / 2f, top + (gh - sh) / 2f),
        Size(sw, sh),
        style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
      )
    }
    if (centerMark) {
      val c = Offset(w / 2f, h / 2f)
      val r = 14.dp.toPx()
      drawLine(Color.White.copy(alpha = 0.8f), c - Offset(r, 0f), c + Offset(r, 0f), 1.5f)
      drawLine(Color.White.copy(alpha = 0.8f), c - Offset(0f, r), c + Offset(0f, r), 1.5f)
    }
  }
}

/**
 * A horizon line from the accelerometer: white when tilted, the accent within half a degree of level,
 * like the level of a stills camera.
 */
@Composable
fun LevelOverlay() {
  val context = LocalContext.current
  var roll by remember { mutableFloatStateOf(0f) }
  DisposableEffect(Unit) {
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val listener = object : SensorEventListener {
      override fun onSensorChanged(e: SensorEvent) {
        // Roll around the axis through the screen, in degrees, for a phone held upright.
        val angle = Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
        roll = roll * 0.8f + angle * 0.2f
      }

      override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    onDispose { manager.unregisterListener(listener) }
  }
  val level = abs(roll) < 0.5f
  val accent = MaterialTheme.colorScheme.primary
  Canvas(Modifier.fillMaxSize()) {
    val c = Offset(size.width / 2f, size.height / 2f)
    val len = size.width * 0.28f
    val color = if (level) accent else Color.White.copy(alpha = 0.85f)
    // Fixed reference ticks, and the line that turns with the phone.
    drawLine(color.copy(alpha = 0.6f), c - Offset(len + 24f, 0f), c - Offset(len + 6f, 0f), 2f)
    drawLine(color.copy(alpha = 0.6f), c + Offset(len + 6f, 0f), c + Offset(len + 24f, 0f), 2f)
    rotate(roll, c) {
      drawLine(color, c - Offset(len, 0f), c + Offset(len, 0f), if (level) 3f else 2f)
    }
  }
}

/** Peaking, zebras or false colour from the analysis frames, stretched over the preview. */
@Composable
fun MonitorOverlay(frame: MonitorFrame?) {
  val bitmap = frame?.overlay ?: return
  Image(
    bitmap.asImageBitmap(),
    contentDescription = null,
    contentScale = ContentScale.FillBounds,
    filterQuality = FilterQuality.None,
    modifier = Modifier.fillMaxSize()
  )
}

/** The chosen scope in a small floating panel: histograms drawn here, traces as bitmaps. */
@Composable
fun ScopePanel(scope: Scope, frame: MonitorFrame?, modifier: Modifier = Modifier) {
  if (scope == Scope.NONE || frame == null) return
  val colors = MaterialTheme.colorScheme
  val (w, h) = when (scope) {
    Scope.VECTORSCOPE -> 120.dp to 120.dp
    Scope.PARADE -> 192.dp to 72.dp
    else -> 144.dp to 64.dp
  }
  Box(modifier.entrance().floatingSurface(Shapes.s).padding(Space.xs)) {
    Box(Modifier.size(w, h)) {
      when (scope) {
        Scope.LUMA -> Histogram(listOf(frame.luma to Color.White.copy(alpha = 0.8f)))
        Scope.RGB -> frame.rgb?.let {
          Histogram(listOf(it[0] to Color(0xB3FF5A5A), it[1] to Color(0xB35AFF7A), it[2] to Color(0xB35AA8FF)))
        }
        Scope.WAVEFORM, Scope.PARADE -> {
          WaveGraticule()
          frame.trace?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
        }
        Scope.VECTORSCOPE -> {
          VectorGraticule(colors.primary)
          frame.trace?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
        }
        Scope.NONE -> Unit
      }
      if (frame.clipped > 0.01f) {
        Text(
          "CLIP ${(frame.clipped * 100).toInt()}%",
          style = AppText.Caption2.copy(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold),
          color = colors.error,
          modifier = Modifier.align(Alignment.TopEnd)
        )
      }
    }
  }
}

@Composable
private fun Histogram(channels: List<Pair<FloatArray, Color>>) {
  Canvas(Modifier.fillMaxSize()) {
    channels.forEach { (bins, color) ->
      val bw = size.width / bins.size
      bins.forEachIndexed { i, v ->
        val bh = v * size.height
        drawRect(color, Offset(i * bw, size.height - bh), Size(bw + 0.5f, bh))
      }
    }
  }
}

/** IRE lines at 0, 25, 50, 75 and 100 %. */
@Composable
private fun WaveGraticule() {
  Canvas(Modifier.fillMaxSize()) {
    for (i in 0..4) {
      val y = size.height * (1f - i / 4f)
      drawLine(Color.White.copy(alpha = if (i == 0 || i == 4) 0.35f else 0.18f), Offset(0f, y), Offset(size.width, y), 1f)
    }
  }
}

/** Vectorscope circle, cross and the skin-tone line (about 123° from the U axis). */
@Composable
private fun VectorGraticule(accent: Color) {
  Canvas(Modifier.fillMaxSize()) {
    val c = Offset(size.width / 2f, size.height / 2f)
    val r = size.minDimension / 2f
    drawCircle(Color.White.copy(alpha = 0.25f), r * 0.95f, c, style = Stroke(1f))
    drawCircle(Color.White.copy(alpha = 0.15f), r * 0.5f, c, style = Stroke(1f))
    drawLine(Color.White.copy(alpha = 0.15f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
    drawLine(Color.White.copy(alpha = 0.15f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
    val a = Math.toRadians(123.0)
    drawLine(accent.copy(alpha = 0.6f), c, Offset(c.x + (r * kotlin.math.cos(a)).toFloat(), c.y - (r * kotlin.math.sin(a)).toFloat()), 1.5f)
  }
}
