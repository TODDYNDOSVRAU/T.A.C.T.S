package com.tacts.camera.camera

import kotlin.math.roundToInt

/** The latest values the camera actually used, read from capture results. */
data class LiveReadout(
  val iso: Int? = null,
  val shutterNs: Long? = null,
  val focusDiopters: Float? = null,
  val frameDurationNs: Long? = null,
  /** The colour transform and gains auto white balance chose, for starting Kelvin mode from them. */
  val ccTransform: android.hardware.camera2.params.ColorSpaceTransform? = null,
  val ccGains: android.hardware.camera2.params.RggbChannelVector? = null,
  val faces: List<android.graphics.Rect> = emptyList(),
  /** The physical lens a logical multi-camera is streaming from, when it says. */
  val activeLens: String? = null
)

private val ISO_STOPS = listOf(
  25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000,
  2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 25600, 51200
)

private val SHUTTER_STOPS: List<Long> = listOf(
  32000, 16000, 8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250,
  200, 160, 125, 100, 96, 80, 60, 50, 48, 40, 30, 25, 24, 20, 15, 13, 10, 8, 6, 5, 4, 3, 2
).map { 1_000_000_000L / it } + listOf(1, 2, 4, 8, 15, 30, 60).map { it * 1_000_000_000L }

fun isoStops(range: android.util.Range<Int>?): List<Int> =
  if (range == null) emptyList() else (ISO_STOPS.filter { it in range.lower..range.upper } + range.upper).distinct().sorted()

fun shutterStops(range: android.util.Range<Long>?): List<Long> =
  if (range == null) emptyList() else SHUTTER_STOPS.filter { it in range.lower..range.upper }.distinct().sorted()

fun shutterLabel(ns: Long): String {
  if (ns >= 1_000_000_000L) return "${(ns / 1_000_000_000.0).roundToInt()}\""
  return "1/${(1_000_000_000.0 / ns).roundToInt()}"
}

fun angleLabel(angle: Float): String =
  (if (angle % 1f == 0f) "${angle.roundToInt()}" else "%.2f".format(java.util.Locale.US, angle).trimEnd('0').trimEnd('.')) + "°"

fun focusLabel(diopters: Float): String =
  if (diopters <= 0.01f) "∞" else {
    val m = 1f / diopters
    if (m < 1f) "${(m * 100).roundToInt()} cm" else "%.1f m".format(m)
  }

/** Index of the value in [values] closest to [target]. */
fun <T : Comparable<T>> nearestIndex(values: List<T>, target: T, distance: (T, T) -> Double): Int =
  values.indices.minByOrNull { distance(values[it], target) } ?: 0
