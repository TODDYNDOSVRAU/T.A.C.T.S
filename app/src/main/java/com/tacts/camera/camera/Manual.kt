package com.tacts.camera.camera

import android.hardware.camera2.CaptureRequest
import kotlin.math.roundToInt

enum class CaptureMode { PHOTO, VIDEO }

enum class Flash { OFF, AUTO, ON }

enum class VideoQuality(val label: String) { HD("HD"), FHD("FHD"), UHD("4K") }

enum class WhiteBalance(val label: String, val awbMode: Int) {
  AUTO("Auto", CaptureRequest.CONTROL_AWB_MODE_AUTO),
  DAYLIGHT("Daylight", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
  CLOUDY("Cloudy", CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
  SHADE("Shade", CaptureRequest.CONTROL_AWB_MODE_SHADE),
  TUNGSTEN("Tungsten", CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT),
  FLUORESCENT("Fluorescent", CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT)
}

/** What the user dialled in. "Auto" flags hand the value back to the camera's own 3A. */
data class ManualSettings(
  val evIndex: Int = 0,
  val exposureAuto: Boolean = true,
  val iso: Int = 100,
  val shutterNs: Long = 16_666_667L,
  val focusAuto: Boolean = true,
  /** Lens focus distance in diopters: 0 is infinity, larger is closer. */
  val focusDiopters: Float = 0f,
  val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
  val raw: Boolean = false
) {
  val isAllAuto get() = exposureAuto && focusAuto && whiteBalance == WhiteBalance.AUTO && evIndex == 0
}

/** What the open camera can do, read from its camera2 characteristics. */
data class ManualCapabilities(
  val manualSensor: Boolean = false,
  val isoValues: List<Int> = emptyList(),
  val shutterValues: List<Long> = emptyList(),
  /** 0 means a fixed-focus lens. */
  val minFocusDiopters: Float = 0f,
  val evRange: IntRange = 0..0,
  val evStep: Float = 0f,
  val whiteBalances: List<WhiteBalance> = listOf(WhiteBalance.AUTO),
  val rawSupported: Boolean = false,
  val hasFlash: Boolean = false
)

/** The latest values the camera actually used, read from capture results. */
data class LiveReadout(val iso: Int? = null, val shutterNs: Long? = null, val focusDiopters: Float? = null)

private val ISO_STOPS = listOf(
  25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000,
  2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800
)

private val SHUTTER_STOPS: List<Long> = listOf(
  8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2
).map { 1_000_000_000L / it } + listOf(1, 2, 4, 8, 15, 30).map { it * 1_000_000_000L }

fun isoStops(range: android.util.Range<Int>?): List<Int> =
  if (range == null) emptyList() else ISO_STOPS.filter { it in range.lower..range.upper }

fun shutterStops(range: android.util.Range<Long>?): List<Long> =
  if (range == null) emptyList() else SHUTTER_STOPS.filter { it in range.lower..range.upper }

fun shutterLabel(ns: Long): String {
  if (ns >= 1_000_000_000L) return "${(ns / 1_000_000_000.0).roundToInt()}\""
  return "1/${(1_000_000_000.0 / ns).roundToInt()}"
}

fun focusLabel(diopters: Float): String =
  if (diopters <= 0.01f) "∞" else {
    val m = 1f / diopters
    if (m < 1f) "${(m * 100).roundToInt()} cm" else "%.1f m".format(m)
  }

/** Index of the value in [values] closest to [target]. */
fun <T : Comparable<T>> nearestIndex(values: List<T>, target: T, distance: (T, T) -> Double): Int =
  values.indices.minByOrNull { distance(values[it], target) } ?: 0
