package com.tacts.camera.camera

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Luminance histogram from the Y plane of each analysis frame, sampled every 4th pixel and at most
 * ten times a second. Bins are normalised so the tallest one is 1.
 */
class HistogramAnalyzer(private val onHistogram: (FloatArray) -> Unit) : ImageAnalysis.Analyzer {
  private var last = 0L

  override fun analyze(image: ImageProxy) {
    val now = SystemClock.elapsedRealtime()
    if (now - last < 100) {
      image.close()
      return
    }
    last = now
    val plane = image.planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val counts = IntArray(BINS)
    for (y in 0 until image.height step 4) {
      val row = y * rowStride
      for (x in 0 until image.width step 4) {
        val luma = buffer.get(row + x * pixelStride).toInt() and 0xFF
        counts[luma * BINS / 256]++
      }
    }
    image.close()
    val max = counts.max().coerceAtLeast(1).toFloat()
    onHistogram(FloatArray(BINS) { counts[it] / max })
  }

  companion object {
    const val BINS = 64
  }
}
