package com.tacts.camera.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * One button of the zoom capsule: a real lens, named by how much it magnifies against the main one
 * (0.6, 1, 3, 10...). [zoom] is the ratio set on the camera that reaches it: the default camera when
 * [cameraId] is null, or a camera of its own (a lens the default camera cannot zoom to) at [zoom] 1.
 */
data class LensStop(val factor: Float, val zoom: Float, val cameraId: String?) {
  val label: String get() = formatFactor(factor)
}

fun formatFactor(f: Float): String =
  if (f < 1f || abs(f - f.roundToInt()) > 0.05f) "%.1f".format(f).trimEnd('0').trimEnd('.', ',') else "${f.roundToInt()}"

object Lenses {

  /**
   * The lenses facing [facing], measured from each camera's focal length and sensor size, so it works on
   * any phone: lenses behind the default logical camera are reached through its zoom range; lenses the
   * phone lists as cameras of their own, outside that range, get their own [LensStop.cameraId].
   *
   * [ownIds] are the camera ids CameraX can open for this facing, the default one first.
   */
  fun stops(context: Context, ownIds: List<String>): List<LensStop> {
    val manager = context.getSystemService(CameraManager::class.java) ?: return fallback(1f, 1f)
    val defaultId = ownIds.firstOrNull() ?: return fallback(1f, 1f)
    val main = runCatching { manager.getCameraCharacteristics(defaultId) }.getOrNull() ?: return fallback(1f, 1f)
    val range = if (Build.VERSION.SDK_INT >= 30) main.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
    val minZoom = range?.lower ?: 1f
    val maxZoom = range?.upper ?: main.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
    val mainFocal = equivalentFocal(main) ?: return fallback(minZoom, maxZoom)

    val stops = mutableListOf(LensStop(1f, 1f.coerceIn(minZoom, maxZoom), null))
    fun addIfNew(stop: LensStop) {
      // Two cameras closer than 30 % are the same lens seen twice (a crop, a logical copy).
      if (stops.none { abs(it.factor - stop.factor) / maxOf(it.factor, stop.factor) < 0.3f }) stops += stop
    }

    // Lenses behind the default camera, reached by its zoom.
    val physical = if (Build.VERSION.SDK_INT >= 28) runCatching { main.physicalCameraIds.toList() }.getOrDefault(emptyList()) else emptyList()
    for (id in physical) {
      val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
      val ratio = (equivalentFocal(c) ?: continue) / mainFocal
      zoomStop(ratio, minZoom, maxZoom)?.let(::addIfNew)
    }

    // Lenses listed as cameras of their own.
    for (id in ownIds.drop(1)) {
      val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
      val ratio = (equivalentFocal(c) ?: continue) / mainFocal
      val reachable = zoomStop(ratio, minZoom, maxZoom)
      addIfNew(reachable ?: LensStop(round(ratio), 1f, id))
    }
    return stops.sortedBy { it.factor }
  }

  /** A lens reached by zooming the default camera, or null when its ratio is outside the zoom range. */
  private fun zoomStop(ratio: Float, minZoom: Float, maxZoom: Float): LensStop? {
    if (ratio < 1f) {
      // The widest ratio is the ultra-wide itself; the HAL rounds its own figure to it.
      if (ratio < minZoom - 0.1f) return null
      val zoom = if (abs(ratio - minZoom) < 0.12f) minZoom else round(ratio).coerceAtLeast(minZoom)
      return LensStop(round(zoom), zoom, null)
    }
    val factor = round(ratio)
    if (factor > maxZoom + 0.05f) return null
    return LensStop(factor, factor.coerceAtMost(maxZoom), null)
  }

  /** 2.93 → 3, 0.594 → 0.6, 4.6 → 4.6: the figure printed on the phone's own camera app. */
  private fun round(ratio: Float): Float = when {
    ratio < 1f -> (ratio * 10f).roundToInt() / 10f
    abs(ratio - ratio.roundToInt()) <= 0.25f -> ratio.roundToInt().toFloat()
    else -> (ratio * 10f).roundToInt() / 10f
  }

  /** The 35 mm equivalent focal length of the shortest focal length the camera lists. */
  fun equivalentFocal(c: CameraCharacteristics): Float? {
    val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
    val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
    // The physical size covers the whole pixel array; only the active part makes the picture.
    val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
    val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    val crop = if (pixels != null && active != null && pixels.width > 0) active.width().toFloat() / pixels.width else 1f
    val diagonal = hypot(size.width * crop, size.height * crop)
    if (diagonal <= 0f || focal <= 0f) return null
    return focal * FULL_FRAME_DIAGONAL / diagonal
  }

  /** Without lens data: the widest zoom, 1 and 2, as far as the range goes. */
  private fun fallback(minZoom: Float, maxZoom: Float): List<LensStop> = buildList {
    if (minZoom < 0.95f) add(LensStop(round(minZoom), minZoom, null))
    add(LensStop(1f, 1f.coerceIn(minZoom, maxZoom), null))
    if (maxZoom >= 2f) add(LensStop(2f, 2f, null))
  }

  private const val FULL_FRAME_DIAGONAL = 43.27f
}
