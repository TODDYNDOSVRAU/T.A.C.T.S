package com.tacts.camera.monitor

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.tacts.camera.camera.CameraSettings
import com.tacts.camera.camera.Scope
import kotlin.math.abs

/** What the monitoring tools need from the settings, copied so the analysis thread reads it safely. */
data class MonitorConfig(
  val peaking: Boolean = false,
  val peakingColor: Int = 0xFFFF3B30.toInt(),
  val peakingThreshold: Int = 60,
  val zebras: Boolean = false,
  val zebraLevel: Int = 95,
  val falseColor: Boolean = false,
  val scope: Scope = Scope.NONE,
  val mirror: Boolean = false
) {
  val overlay get() = peaking || zebras || falseColor

  companion object {
    fun from(s: CameraSettings, mirror: Boolean) = MonitorConfig(
      peaking = s.peaking,
      peakingColor = s.peakingColor.argb.toInt(),
      peakingThreshold = s.peakingThreshold,
      zebras = s.zebras,
      zebraLevel = s.zebraLevel,
      falseColor = s.falseColor,
      scope = s.scope,
      mirror = mirror
    )
  }
}

/** One analysed frame, already turned to screen orientation. */
class MonitorFrame(
  /** Peaking, zebras or false colour, the size of the sampled grid, drawn stretched over the preview. */
  val overlay: Bitmap?,
  /** Luma histogram, 64 bins, tallest = 1. */
  val luma: FloatArray,
  /** R, G and B histograms, 64 bins each, for [Scope.RGB]. */
  val rgb: Array<FloatArray>?,
  /** Waveform, parade or vectorscope trace, white on transparent. */
  val trace: Bitmap?,
  /** Fraction of samples clipped at the top (above 98 %), for the clipping warning. */
  val clipped: Float
)

/**
 * The scopes of a cinema monitor, from the analysis frames: focus peaking, zebras, false colour,
 * waveform, RGB parade, vectorscope and histograms. The frame is sampled on a grid of about 240 points
 * across and at most 12 times a second, so it costs a few milliseconds on the analysis thread.
 */
class ScopeAnalyzer(
  private val config: () -> MonitorConfig,
  private val onFrame: (MonitorFrame) -> Unit
) : ImageAnalysis.Analyzer {
  private var last = 0L

  override fun analyze(image: ImageProxy) {
    val now = SystemClock.elapsedRealtime()
    if (now - last < 80) {
      image.close()
      return
    }
    last = now
    val cfg = config()
    try {
      onFrame(process(image, cfg))
    } catch (_: Exception) {
    } finally {
      image.close()
    }
  }

  private fun process(image: ImageProxy, cfg: MonitorConfig): MonitorFrame {
    val rotation = image.imageInfo.rotationDegrees
    val iw = image.width
    val ih = image.height
    // The sampled grid, in the buffer's own orientation.
    val gw = GRID
    val gh = (GRID * ih / iw).coerceAtLeast(1)

    val yp = image.planes[0]
    val up = image.planes[1]
    val vp = image.planes[2]
    val yBuf = yp.buffer
    val uBuf = up.buffer
    val vBuf = vp.buffer
    val needColor = cfg.scope == Scope.RGB || cfg.scope == Scope.PARADE || cfg.scope == Scope.VECTORSCOPE

    val luma = IntArray(gw * gh)
    val us = if (needColor) IntArray(gw * gh) else null
    val vs = if (needColor) IntArray(gw * gh) else null
    for (y in 0 until gh) {
      val sy = y * ih / gh
      val yRow = sy * yp.rowStride
      val cRow = (sy / 2) * up.rowStride
      val cRowV = (sy / 2) * vp.rowStride
      for (x in 0 until gw) {
        val sx = x * iw / gw
        val i = y * gw + x
        luma[i] = yBuf.get(yRow + sx * yp.pixelStride).toInt() and 0xFF
        if (us != null && vs != null) {
          us[i] = (uBuf.get(cRow + (sx / 2) * up.pixelStride).toInt() and 0xFF) - 128
          vs[i] = (vBuf.get(cRowV + (sx / 2) * vp.pixelStride).toInt() and 0xFF) - 128
        }
      }
    }

    // Display orientation: the grid as it appears on screen.
    val swap = rotation == 90 || rotation == 270
    val dw = if (swap) gh else gw
    val dh = if (swap) gw else gh
    fun toDisplay(x: Int, y: Int): Int {
      var dx: Int
      val dy: Int
      when (rotation) {
        90 -> { dx = gh - 1 - y; dy = x }
        180 -> { dx = gw - 1 - x; dy = gh - 1 - y }
        270 -> { dx = y; dy = gw - 1 - x }
        else -> { dx = x; dy = y }
      }
      if (cfg.mirror) dx = dw - 1 - dx
      return dy * dw + dx
    }

    // ---- histograms ----
    val lumaBins = IntArray(BINS)
    var clipped = 0
    for (l in luma) {
      lumaBins[l * BINS / 256]++
      if (l >= 250) clipped++
    }

    // ---- overlay ----
    val overlay = if (cfg.overlay) {
      val px = IntArray(dw * dh)
      val zebraMin = cfg.zebraLevel * 255 / 100
      for (y in 0 until gh) for (x in 0 until gw) {
        val i = y * gw + x
        val l = luma[i]
        val d = toDisplay(x, y)
        var c = if (cfg.falseColor) falseColor(l) else 0
        if (cfg.zebras && l >= zebraMin) {
          val dx = d % dw
          val dy = d / dw
          // Diagonal stripes, in screen space so they do not turn with the phone.
          if (((dx + dy) / 3) % 2 == 0) c = 0xCCFFFFFF.toInt()
        }
        if (cfg.peaking && x in 1 until gw - 1 && y in 1 until gh - 1) {
          val gx = -luma[i - gw - 1] - 2 * luma[i - 1] - luma[i + gw - 1] + luma[i - gw + 1] + 2 * luma[i + 1] + luma[i + gw + 1]
          val gy = -luma[i - gw - 1] - 2 * luma[i - gw] - luma[i - gw + 1] + luma[i + gw - 1] + 2 * luma[i + gw] + luma[i + gw + 1]
          if (abs(gx) + abs(gy) > cfg.peakingThreshold * 4) c = cfg.peakingColor
        }
        px[d] = c
      }
      Bitmap.createBitmap(px, dw, dh, Bitmap.Config.ARGB_8888)
    } else null

    // ---- scopes ----
    var rgb: Array<FloatArray>? = null
    var trace: Bitmap? = null
    when (cfg.scope) {
      Scope.RGB -> {
        val r = IntArray(BINS)
        val g = IntArray(BINS)
        val b = IntArray(BINS)
        for (i in luma.indices) {
          val (rr, gg, bb) = yuvToRgb(luma[i], us!![i], vs!![i])
          r[rr * BINS / 256]++
          g[gg * BINS / 256]++
          b[bb * BINS / 256]++
        }
        rgb = arrayOf(normalize(r), normalize(g), normalize(b))
      }
      Scope.WAVEFORM -> trace = waveform(dw, luma.size) { i, put ->
        val y = i / gw
        val x = i % gw
        put(toDisplay(x, y) % dw, luma[i], 0xFFFFFFFF.toInt())
      }
      Scope.PARADE -> {
        val third = dw
        trace = waveform(third * 3, luma.size * 3) { k, put ->
          val i = k / 3
          val ch = k % 3
          val y = i / gw
          val x = i % gw
          val (rr, gg, bb) = yuvToRgb(luma[i], us!![i], vs!![i])
          val col = toDisplay(x, y) % dw
          when (ch) {
            0 -> put(col, rr, 0xFFFF5A5A.toInt())
            1 -> put(third + col, gg, 0xFF5AFF7A.toInt())
            else -> put(third * 2 + col, bb, 0xFF5AA8FF.toInt())
          }
        }
      }
      Scope.VECTORSCOPE -> {
        val size = VECTOR
        val counts = IntArray(size * size)
        for (i in luma.indices) {
          // U to the right, V up, as a broadcast vectorscope draws them.
          val px = (size / 2 + us!![i] * size / 256).coerceIn(0, size - 1)
          val py = (size / 2 - vs!![i] * size / 256).coerceIn(0, size - 1)
          counts[py * size + px]++
        }
        trace = intensity(counts, size, size, 0xFF7EE3EA.toInt())
      }
      Scope.LUMA, Scope.NONE -> Unit
    }

    return MonitorFrame(overlay, normalize(lumaBins), rgb, trace, clipped.toFloat() / luma.size)
  }

  /** A waveform: for each sample, [plot] puts a dot at a column and a level (0..255). */
  private inline fun waveform(width: Int, samples: Int, plot: (Int, (Int, Int, Int) -> Unit) -> Unit): Bitmap {
    val height = WAVE_H
    val counts = IntArray(width * height)
    val colors = IntArray(width * height)
    for (k in 0 until samples) {
      plot(k) { col, level, color ->
        val row = (height - 1 - level * (height - 1) / 255).coerceIn(0, height - 1)
        val idx = row * width + col.coerceIn(0, width - 1)
        counts[idx]++
        colors[idx] = color
      }
    }
    val max = (counts.max()).coerceAtLeast(1)
    val px = IntArray(width * height) { i ->
      val c = counts[i]
      if (c == 0) 0 else {
        val a = (80 + 175 * minOf(1f, c * 4f / max)).toInt()
        (a shl 24) or (colors[i] and 0xFFFFFF)
      }
    }
    return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
  }

  private fun intensity(counts: IntArray, w: Int, h: Int, color: Int): Bitmap {
    val max = counts.max().coerceAtLeast(1)
    val px = IntArray(w * h) { i ->
      val c = counts[i]
      if (c == 0) 0 else ((80 + 175 * minOf(1f, c * 6f / max)).toInt() shl 24) or (color and 0xFFFFFF)
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
  }

  private fun normalize(bins: IntArray): FloatArray {
    val max = bins.max().coerceAtLeast(1).toFloat()
    return FloatArray(bins.size) { bins[it] / max }
  }

  private fun yuvToRgb(y: Int, u: Int, v: Int): Triple<Int, Int, Int> {
    val r = (y + 1.402f * v).toInt().coerceIn(0, 255)
    val g = (y - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255)
    val b = (y + 1.772f * u).toInt().coerceIn(0, 255)
    return Triple(r, g, b)
  }

  companion object {
    const val GRID = 240
    const val BINS = 64
    const val WAVE_H = 128
    const val VECTOR = 128

    /**
     * False colour after the cinema convention: purple and blue for crushed blacks, green at 18 %
     * grey, pink around skin, yellow near clipping, red when clipped; everything else in grey.
     */
    fun falseColor(l: Int): Int {
      val ire = l * 100 / 255
      return when {
        ire < 3 -> 0xFF8A2BE2.toInt()
        ire < 10 -> 0xFF1E5BFF.toInt()
        ire in 38..44 -> 0xFF2ECC40.toInt()
        ire in 52..58 -> 0xFFFF7EB6.toInt()
        ire in 94..97 -> 0xFFFFD60A.toInt()
        ire >= 98 -> 0xFFFF3B30.toInt()
        else -> {
          val g = l
          (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
      }
    }
  }
}
