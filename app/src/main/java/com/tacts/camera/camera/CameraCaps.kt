package com.tacts.camera.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageCapture
import androidx.camera.video.ExperimentalHighSpeedVideo
import androidx.camera.video.Quality
import androidx.camera.video.Recorder

/**
 * What the open camera can do, read once from its camera2 characteristics and from CameraX. Every
 * option in the app asks this before it is offered or applied, so a phone that lacks a key simply
 * does not show it.
 */
data class CameraCaps(
  val cameraId: String = "",
  val hardwareLevel: Int = CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
  val manualSensor: Boolean = false,
  val manualPostProcessing: Boolean = false,
  val rawCapable: Boolean = false,
  val logicalMultiCamera: Boolean = false,
  val physicalCameraIds: List<String> = emptyList(),

  val isoValues: List<Int> = emptyList(),
  val shutterValues: List<Long> = emptyList(),
  val exposureRange: Range<Long>? = null,
  val maxFrameDurationNs: Long = 0L,
  /** 0 means a fixed-focus lens. */
  val minFocusDiopters: Float = 0f,
  val hyperfocalDiopters: Float = 0f,
  val focusCalibrated: Boolean = false,
  val evRange: IntRange = 0..0,
  val evStep: Float = 0f,

  val awbModes: Set<Int> = emptySet(),
  val antibandingModes: Set<Int> = emptySet(),
  val noiseReductionModes: Set<Int> = emptySet(),
  val edgeModes: Set<Int> = emptySet(),
  val hotPixelModes: Set<Int> = emptySet(),
  val shadingModes: Set<Int> = emptySet(),
  val tonemapModes: Set<Int> = emptySet(),
  val tonemapMaxPoints: Int = 0,
  val oisModes: Set<Int> = emptySet(),
  val videoStabilizationModes: Set<Int> = emptySet(),
  val faceDetectModes: Set<Int> = emptySet(),
  val maxAeRegions: Int = 0,
  val maxAfRegions: Int = 0,
  val apertures: List<Float> = emptyList(),
  val ndFilters: List<Float> = emptyList(),
  val focalLengths: List<Float> = emptyList(),
  val sensorSizeMm: android.util.SizeF? = null,
  val activeArray: android.graphics.Rect? = null,
  val rawSizes: List<Size> = emptyList(),
  val aeFpsRanges: List<Range<Int>> = emptyList(),
  val highSpeedFps: List<Int> = emptyList(),
  val zoomRange: Range<Float>? = null,
  val hasFlash: Boolean = false,
  val maxTorchStrength: Int = 0,
  val lowLightBoost: Boolean = false,
  /** XYZ to camera colour matrices for two reference illuminants, for Kelvin white balance. */
  val colorMatrix1: FloatArray? = null,
  val colorMatrix2: FloatArray? = null,
  val illuminant1K: Int = 2856,
  val illuminant2K: Int = 6504,

  // From CameraX
  val photoFormats: Set<PhotoFormat> = setOf(PhotoFormat.JPEG),
  val videoQualities: List<VideoQuality> = emptyList(),
  val dynamicRanges: Set<DynamicRangeChoice> = setOf(DynamicRangeChoice.SDR),
  val frameRates: List<Int> = emptyList(),
  val videoStabilization: Boolean = false,
  val previewStabilization: Boolean = false
) {
  val hasTonemapCurve get() = android.hardware.camera2.CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE in tonemapModes
  val hasManualWbGains get() = manualPostProcessing
  val hasOis get() = android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON in oisModes
  val hardwareLevelLabel: String
    get() = when (hardwareLevel) {
      CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
      CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
      CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
      CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL 3"
      CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
      else -> "?"
    }

  companion object {
    @OptIn(ExperimentalCamera2Interop::class)
    fun read(info: CameraInfo): CameraCaps {
      val c2 = Camera2CameraInfo.from(info)
      fun <T> get(key: CameraCharacteristics.Key<T>): T? = try {
        c2.getCameraCharacteristic(key)
      } catch (e: Exception) {
        null
      }
      fun ints(key: CameraCharacteristics.Key<IntArray>) = get(key)?.toSet() ?: emptySet()

      val capabilities = ints(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
      val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
      val streamMap = get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
      val exposure = info.exposureState

      val photoFormats = buildSet {
        add(PhotoFormat.JPEG)
        try {
          val formats = ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
          if (ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR in formats) add(PhotoFormat.ULTRA_HDR)
          if (ImageCapture.OUTPUT_FORMAT_RAW in formats) add(PhotoFormat.RAW)
          if (ImageCapture.OUTPUT_FORMAT_RAW_JPEG in formats) add(PhotoFormat.RAW_JPEG)
        } catch (_: Exception) {
        }
      }

      val videoCaps = try {
        Recorder.getVideoCapabilities(info)
      } catch (e: Exception) {
        null
      }
      val dynamicRanges = buildSet {
        add(DynamicRangeChoice.SDR)
        val supported = videoCaps?.supportedDynamicRanges ?: emptySet()
        if (supported.any { it.encoding == DynamicRange.ENCODING_HLG }) add(DynamicRangeChoice.HLG)
        if (supported.any { it.encoding == DynamicRange.ENCODING_HDR10 }) add(DynamicRangeChoice.HDR10)
        if (supported.any { it.encoding == DynamicRange.ENCODING_HDR10_PLUS }) add(DynamicRangeChoice.HDR10_PLUS)
        if (supported.any { it.encoding == DynamicRange.ENCODING_DOLBY_VISION }) add(DynamicRangeChoice.DOLBY_VISION)
      }
      val qualities = (videoCaps?.getSupportedQualities(DynamicRange.SDR) ?: emptyList()).mapNotNull {
        when (it) {
          Quality.SD -> VideoQuality.SD
          Quality.HD -> VideoQuality.HD
          Quality.FHD -> VideoQuality.FHD
          Quality.UHD -> VideoQuality.UHD
          else -> null
        }
      }.sortedBy { it.ordinal }

      val aeRanges = get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
      val cxRanges = try {
        info.supportedFrameRateRanges.toList()
      } catch (e: Exception) {
        emptyList()
      }
      val fixedRates = (aeRanges + cxRanges).filter { it.lower == it.upper }.map { it.upper }.toSet()
      val maxRate = (aeRanges + cxRanges).maxOfOrNull { it.upper } ?: 30
      // A rate is offered when the camera lists it as a fixed range, or when it fits under the top of
      // a variable range (the encoder then holds it).
      val frameRates = FRAME_RATES.filter { it in fixedRates || it <= maxRate }

      // Slow motion runs through CameraX, which needs a high-speed profile for the camera on top of the
      // Camera2 stream configuration; some phones list the latter only.
      val highSpeed = try {
        @OptIn(ExperimentalHighSpeedVideo::class)
        val cameraXQualities = Recorder.getHighSpeedVideoCapabilities(info)?.getSupportedQualities(DynamicRange.SDR).orEmpty()
        if (cameraXQualities.isEmpty()) emptyList()
        else streamMap?.highSpeedVideoFpsRanges?.map { it.upper }?.filter { it >= 120 }?.distinct()?.sorted() ?: emptyList()
      } catch (e: Exception) {
        emptyList()
      }

      val torchMax = if (Build.VERSION.SDK_INT >= 33) get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 0 else 0

      return CameraCaps(
        cameraId = c2.cameraId,
        hardwareLevel = get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
        manualSensor = manualSensor,
        manualPostProcessing = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities,
        rawCapable = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities,
        logicalMultiCamera = Build.VERSION.SDK_INT >= 28 &&
          CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in capabilities,
        physicalCameraIds = try {
          info.physicalCameraInfos.map { Camera2CameraInfo.from(it).cameraId }.filter { it != c2.cameraId }.sorted()
        } catch (e: Exception) {
          emptyList()
        },
        isoValues = if (manualSensor) isoStops(get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)) else emptyList(),
        shutterValues = if (manualSensor) shutterStops(get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)) else emptyList(),
        exposureRange = if (manualSensor) get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) else null,
        maxFrameDurationNs = get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 0L,
        minFocusDiopters = if (manualSensor) get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f else 0f,
        hyperfocalDiopters = get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f,
        focusCalibrated = get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION) ==
          CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED,
        evRange = if (exposure.isExposureCompensationSupported) exposure.exposureCompensationRange.let { it.lower..it.upper } else 0..0,
        evStep = exposure.exposureCompensationStep.toFloat(),
        awbModes = ints(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),
        antibandingModes = ints(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES),
        noiseReductionModes = ints(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
        edgeModes = ints(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
        hotPixelModes = ints(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES),
        shadingModes = ints(CameraCharacteristics.SHADING_AVAILABLE_MODES),
        tonemapModes = ints(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES),
        tonemapMaxPoints = get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0,
        oisModes = ints(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
        videoStabilizationModes = ints(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
        faceDetectModes = ints(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES),
        maxAeRegions = get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
        maxAfRegions = get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
        apertures = get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList() ?: emptyList(),
        ndFilters = get(CameraCharacteristics.LENS_INFO_AVAILABLE_FILTER_DENSITIES)?.toList() ?: emptyList(),
        focalLengths = get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList(),
        sensorSizeMm = get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE),
        activeArray = get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
        rawSizes = streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList() ?: emptyList(),
        aeFpsRanges = aeRanges,
        highSpeedFps = highSpeed,
        zoomRange = if (Build.VERSION.SDK_INT >= 30) get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null,
        hasFlash = info.hasFlashUnit(),
        maxTorchStrength = torchMax,
        lowLightBoost = try {
          info.isLowLightBoostSupported
        } catch (e: Exception) {
          false
        },
        colorMatrix1 = get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.toFloats(),
        colorMatrix2 = get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.toFloats(),
        illuminant1K = illuminantKelvin(get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt(), 2856),
        illuminant2K = illuminantKelvin(get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt(), 6504),
        photoFormats = photoFormats,
        videoQualities = qualities,
        dynamicRanges = dynamicRanges,
        frameRates = frameRates.ifEmpty { listOf(30) },
        videoStabilization = videoCaps?.isStabilizationSupported == true,
        previewStabilization = try {
          androidx.camera.core.Preview.getPreviewCapabilities(info).isStabilizationSupported
        } catch (e: Exception) {
          false
        }
      )
    }
  }
}

private fun android.hardware.camera2.params.ColorSpaceTransform.toFloats(): FloatArray =
  FloatArray(9) { i -> getElement(i % 3, i / 3).toFloat() }

/** Colour temperature of a camera2 reference illuminant (the EXIF LightSource codes). */
private fun illuminantKelvin(code: Int?, fallback: Int): Int = when (code) {
  1, 4, 9 -> 5500 // daylight, flash, fine weather
  2 -> 4200 // fluorescent
  3, 17 -> 2856 // tungsten, standard A
  10 -> 6500 // cloudy
  11 -> 7500 // shade
  12 -> 6430 // daylight fluorescent
  13 -> 5000 // day white fluorescent
  14 -> 4150 // cool white fluorescent
  15 -> 3450 // white fluorescent
  18 -> 4874 // standard B
  19 -> 6774 // standard C
  20 -> 5503 // D55
  21 -> 6504 // D65
  22 -> 7504 // D75
  23 -> 5003 // D50
  24 -> 3200 // ISO studio tungsten
  else -> fallback
}
