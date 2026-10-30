package com.tacts.camera.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Turns [CameraSettings] into the camera2 request keys CameraX does not set itself: manual exposure,
 * shutter angle, focus, white balance in Kelvin, gamma curves, the ISP's processing, locks,
 * antibanding, aperture, ND, OIS and face detection. Keys the camera does not list are left out, so
 * the camera keeps its own default for them.
 */
@OptIn(ExperimentalCamera2Interop::class)
object RequestOptions {

  fun build(s: CameraSettings, caps: CameraCaps, live: LiveReadout, videoFps: Int?): CaptureRequestOptions {
    val o = CaptureRequestOptions.Builder()
    fun <T : Any> set(key: CaptureRequest.Key<T>, value: T) = o.setCaptureRequestOption(key, value)

    // ---- exposure ----
    if (!s.exposureAuto && caps.manualSensor) {
      val exposure = exposureNs(s, caps, videoFps)
      set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
      set(CaptureRequest.SENSOR_SENSITIVITY, s.iso)
      set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
      // Video keeps its frame rate; a photo exposure longer than a frame stretches the frame.
      val frame = if (videoFps != null) maxOf(1_000_000_000L / videoFps, exposure) else maxOf(exposure, 33_333_333L)
      set(CaptureRequest.SENSOR_FRAME_DURATION, frame)
    }
    if (s.exposureAuto && s.aeLock) set(CaptureRequest.CONTROL_AE_LOCK, true)
    antibandingMode(s.antibanding)?.takeIf { it in caps.antibandingModes }?.let { set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, it) }
    if (caps.apertures.size > 1 && s.aperture > 0f) set(CaptureRequest.LENS_APERTURE, s.aperture)
    if (caps.ndFilters.size > 1) set(CaptureRequest.LENS_FILTER_DENSITY, s.ndFilter)

    // ---- focus ----
    if (!s.focusAuto && caps.minFocusDiopters > 0f) {
      set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
      set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopters.coerceIn(0f, caps.minFocusDiopters))
    }

    // ---- white balance ----
    when (s.wbMode) {
      WhiteBalanceMode.AUTO -> {
        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        if (s.awbLock) set(CaptureRequest.CONTROL_AWB_LOCK, true)
      }
      WhiteBalanceMode.PRESET -> {
        if (s.whiteBalance.awbMode in caps.awbModes) {
          set(CaptureRequest.CONTROL_AWB_MODE, s.whiteBalance.awbMode)
        } else applyKelvin(o, caps, live, s.whiteBalance.kelvin, 0)
      }
      WhiteBalanceMode.KELVIN -> applyKelvin(o, caps, live, s.kelvin, s.tint)
    }

    // ---- picture ----
    if (s.gamma != GammaProfile.STANDARD && caps.hasTonemapCurve) {
      val points = curve(s.gamma, minOf(64, caps.tonemapMaxPoints.coerceAtLeast(2)))
      set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
      set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(points, points, points))
    }
    processing(s.noiseReduction, NR_MODES)?.takeIf { it in caps.noiseReductionModes }
      ?.let { set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
    processing(s.sharpening, EDGE_MODES)?.takeIf { it in caps.edgeModes }?.let { set(CaptureRequest.EDGE_MODE, it) }
    processing(s.hotPixel, HOT_PIXEL_MODES)?.takeIf { it in caps.hotPixelModes }?.let { set(CaptureRequest.HOT_PIXEL_MODE, it) }
    processing(s.lensShading, SHADING_MODES)?.takeIf { it in caps.shadingModes }?.let { set(CaptureRequest.SHADING_MODE, it) }

    // ---- lens and statistics ----
    if (caps.hasOis) {
      set(
        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
        if (s.ois) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
      )
    }
    if (s.faceDetection && CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE in caps.faceDetectModes) {
      set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE)
    }
    return o.build()
  }

  /** The exposure time the settings ask for: the shutter angle in video, the shutter speed otherwise. */
  fun exposureNs(s: CameraSettings, caps: CameraCaps, videoFps: Int?): Long {
    val ns = if (videoFps != null && s.shutterMode == ShutterMode.ANGLE) s.angleExposureNs(videoFps) else s.shutterNs
    return caps.exposureRange?.let { ns.coerceIn(it.lower, it.upper) } ?: ns
  }

  // ---- white balance in Kelvin ----

  private fun applyKelvin(o: CaptureRequestOptions.Builder, caps: CameraCaps, live: LiveReadout, kelvin: Int, tint: Int) {
    if (!caps.hasManualWbGains) {
      // Without manual colour correction, the nearest preset is the best the camera can do.
      val preset = WhiteBalance.entries.filter { it.awbMode in caps.awbModes }.minByOrNull { kotlin.math.abs(it.kelvin - kelvin) }
      o.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, preset?.awbMode ?: CaptureRequest.CONTROL_AWB_MODE_AUTO)
      return
    }
    val gains = kelvinGains(caps, live, kelvin, tint)
    o.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
    o.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
    o.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
    o.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, live.ccTransform ?: IDENTITY)
  }

  /**
   * Channel gains that make light of [kelvin] neutral. With the sensor's two colour matrices, the
   * matrix for that temperature is interpolated (by inverse temperature, as DNG does) and applied to
   * the white point of that temperature on the Planckian locus; the gains are the inverse of the
   * camera's response to it. Without them, the gains auto chose are scaled by the blackbody ratio.
   * [tint] moves along green–magenta: positive is magenta.
   */
  fun kelvinGains(caps: CameraCaps, live: LiveReadout, kelvin: Int, tint: Int): RggbChannelVector {
    val m1 = caps.colorMatrix1
    val m2 = caps.colorMatrix2
    var r: Float
    var b: Float
    if (m1 != null) {
      val k1 = caps.illuminant1K.toFloat()
      val k2 = caps.illuminant2K.toFloat()
      val g = if (m2 == null || k1 == k2) 1f else ((1f / kelvin - 1f / k2) / (1f / k1 - 1f / k2)).coerceIn(0f, 1f)
      val m = FloatArray(9) { i -> if (m2 == null) m1[i] else g * m1[i] + (1f - g) * m2[i] }
      val (x, y) = planckianXy(kelvin.toFloat())
      val xyz = floatArrayOf(x / y, 1f, (1f - x - y) / y)
      val cam = FloatArray(3) { row -> m[row * 3] * xyz[0] + m[row * 3 + 1] * xyz[1] + m[row * 3 + 2] * xyz[2] }
      r = cam[1] / cam[0]
      b = cam[1] / cam[2]
    } else {
      val base = live.ccGains
      val ref = blackbody(5600f)
      val now = blackbody(kelvin.toFloat())
      r = (base?.red ?: 2f) * ref[0] / now[0]
      b = (base?.blue ?: 1.6f) * ref[2] / now[2]
    }
    var green = 2f.pow(-tint / 100f)
    // Keep every gain at 1 or above: below 1 the ISP would clip highlights in that channel.
    val min = minOf(r, green, b)
    r /= min
    b /= min
    green /= min
    return RggbChannelVector(r, green, green, b)
  }

  /** The chromaticity of a blackbody at [t] kelvin (Kim et al. cubic spline, 1667–25000 K). */
  private fun planckianXy(t: Float): Pair<Float, Float> {
    val k = t.coerceIn(1667f, 25000f).toDouble()
    val x = if (k <= 4000) -0.2661239e9 / (k * k * k) - 0.2343589e6 / (k * k) + 0.8776956e3 / k + 0.179910
    else -3.0258469e9 / (k * k * k) + 2.1070379e6 / (k * k) + 0.2226347e3 / k + 0.240390
    val y = when {
      k <= 2222 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
      k <= 4000 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
      else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
    }
    return x.toFloat() to y.toFloat()
  }

  /** Approximate sRGB of a blackbody (Tanner Helland's fit), 0..1 per channel. */
  private fun blackbody(kelvin: Float): FloatArray {
    val t = kelvin / 100f
    val r = if (t <= 66f) 255f else (329.6987f * (t - 60f).pow(-0.1332047f)).coerceIn(0f, 255f)
    val g = if (t <= 66f) (99.4708f * ln(t) - 161.11957f).coerceIn(0f, 255f) else (288.12216f * (t - 60f).pow(-0.0755148f)).coerceIn(0f, 255f)
    val b = when {
      t >= 66f -> 255f
      t <= 19f -> 0f
      else -> (138.51773f * ln(t - 10f) - 305.0448f).coerceIn(0f, 255f)
    }
    return floatArrayOf(r / 255f, g / 255f, maxOf(b, 1f) / 255f)
  }

  private val IDENTITY = ColorSpaceTransform(intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1))

  // ---- gamma ----

  /**
   * A tonemap curve as interleaved (in, out) pairs from 0 to 1, as TONEMAP_CURVE wants. Input is the
   * linear light after white balance; output is what the encoder gets.
   */
  fun curve(profile: GammaProfile, points: Int): FloatArray {
    val out = FloatArray(points * 2)
    for (i in 0 until points) {
      // Points crowded towards black, where the curves bend most.
      val x = (i / (points - 1f)).pow(2f)
      out[i * 2] = x
      out[i * 2 + 1] = transfer(profile, x).coerceIn(0f, 1f)
    }
    return out
  }

  fun transfer(profile: GammaProfile, x: Float): Float = when (profile) {
    GammaProfile.STANDARD -> srgb(x)
    GammaProfile.LINEAR -> x
    GammaProfile.FLAT -> 0.06f + 0.86f * srgb(x).pow(0.92f)
    // Cineon-style: 18 % grey near 0.42, black lifted to about 0.09, ten stops above black.
    GammaProfile.LOG -> 0.09f + 0.91f * (ln(1f + 40f * x) / ln(41f))
    GammaProfile.LOG_HC -> 0.05f + 0.95f * (ln(1f + 14f * x) / ln(15f))
    GammaProfile.HLG_LIKE -> if (x <= 1f / 12f) sqrt(3f * x) else (0.17883277f * ln(12f * x - 0.28466892f) + 0.55991073f)
  }

  private fun srgb(x: Float) = if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f

  // ---- processing modes ----

  private val NR_MODES = mapOf(
    ProcessingLevel.OFF to CaptureRequest.NOISE_REDUCTION_MODE_OFF,
    ProcessingLevel.FAST to CaptureRequest.NOISE_REDUCTION_MODE_FAST,
    ProcessingLevel.HIGH_QUALITY to CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
    ProcessingLevel.MINIMAL to CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL
  )
  private val EDGE_MODES = mapOf(
    ProcessingLevel.OFF to CaptureRequest.EDGE_MODE_OFF,
    ProcessingLevel.FAST to CaptureRequest.EDGE_MODE_FAST,
    ProcessingLevel.HIGH_QUALITY to CaptureRequest.EDGE_MODE_HIGH_QUALITY
  )
  private val HOT_PIXEL_MODES = mapOf(
    ProcessingLevel.OFF to CaptureRequest.HOT_PIXEL_MODE_OFF,
    ProcessingLevel.FAST to CaptureRequest.HOT_PIXEL_MODE_FAST,
    ProcessingLevel.HIGH_QUALITY to CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
  )
  private val SHADING_MODES = mapOf(
    ProcessingLevel.OFF to CaptureRequest.SHADING_MODE_OFF,
    ProcessingLevel.FAST to CaptureRequest.SHADING_MODE_FAST,
    ProcessingLevel.HIGH_QUALITY to CaptureRequest.SHADING_MODE_HIGH_QUALITY
  )

  /** The levels each processing step offers on this camera, for the settings sheet. */
  fun levelsFor(kind: String, caps: CameraCaps): List<ProcessingLevel> {
    val (map, available) = when (kind) {
      "nr" -> NR_MODES to caps.noiseReductionModes
      "edge" -> EDGE_MODES to caps.edgeModes
      "hot" -> HOT_PIXEL_MODES to caps.hotPixelModes
      else -> SHADING_MODES to caps.shadingModes
    }
    return listOf(ProcessingLevel.DEFAULT) + map.filterValues { it in available }.keys
  }

  private fun processing(level: ProcessingLevel, modes: Map<ProcessingLevel, Int>): Int? = modes[level]

  private fun antibandingMode(a: Antibanding): Int? = when (a) {
    Antibanding.AUTO -> null
    Antibanding.HZ50 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
    Antibanding.HZ60 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
    Antibanding.OFF -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
  }
}
