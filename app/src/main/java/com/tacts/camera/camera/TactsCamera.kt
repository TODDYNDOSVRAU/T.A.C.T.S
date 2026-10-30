package com.tacts.camera.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.tacts.camera.filters.Look
import com.tacts.camera.filters.applyTo
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The camera engine: CameraX for preview, photo and video, camera2 interop for the manual controls.
 * Its fields are Compose state, so the UI reads them directly and every setter rebinds or re-applies
 * what it has to.
 */
@OptIn(ExperimentalCamera2Interop::class)
class TactsCamera(private val context: Context) {

  var mode by mutableStateOf(CaptureMode.PHOTO)
    private set
  var lensFacing by mutableIntStateOf(CameraSelector.LENS_FACING_BACK)
    private set
  var hasFrontAndBack by mutableStateOf(false)
    private set
  var flash by mutableStateOf(Flash.OFF)
    private set
  var videoQuality by mutableStateOf(VideoQuality.FHD)
    private set
  var look by mutableStateOf(Look.NATURAL)
    private set
  var manual by mutableStateOf(ManualSettings())
    private set
  var caps by mutableStateOf(ManualCapabilities())
    private set
  var live by mutableStateOf(LiveReadout())
    private set
  var histogram by mutableStateOf<FloatArray?>(null)
    private set

  var zoomRatio by mutableFloatStateOf(1f)
    private set
  var minZoom by mutableFloatStateOf(1f)
    private set
  var maxZoom by mutableFloatStateOf(1f)
    private set

  var isRecording by mutableStateOf(false)
    private set
  var recordedSeconds by mutableLongStateOf(0L)
    private set
  var busy by mutableStateOf(false)
    private set
  var lastCapture by mutableStateOf<Uri?>(null)
    private set
  var lastIsVideo by mutableStateOf(false)
    private set
  var lastThumb by mutableStateOf<Bitmap?>(null)
    private set
  /** A one-line notice for the UI (errors, "RAW saved"); the UI clears it. */
  var notice by mutableStateOf<String?>(null)

  private val mainExecutor = ContextCompat.getMainExecutor(context)
  private val worker = Executors.newSingleThreadExecutor()
  private var provider: ProcessCameraProvider? = null
  private var owner: LifecycleOwner? = null
  private var previewView: PreviewView? = null
  private var camera: Camera? = null
  private var imageCapture: ImageCapture? = null
  private var videoCapture: VideoCapture<Recorder>? = null
  private var analysis: ImageAnalysis? = null
  private var recording: Recording? = null
  private var lastReadout = 0L

  private val orientation = object : OrientationEventListener(context) {
    override fun onOrientationChanged(degrees: Int) {
      if (degrees == ORIENTATION_UNKNOWN) return
      val rotation = when (degrees) {
        in 45 until 135 -> Surface.ROTATION_270
        in 135 until 225 -> Surface.ROTATION_180
        in 225 until 315 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
      }
      imageCapture?.targetRotation = rotation
      videoCapture?.targetRotation = rotation
      analysis?.targetRotation = rotation
    }
  }

  fun attach(owner: LifecycleOwner, view: PreviewView) {
    this.owner = owner
    this.previewView = view
    applyLookToPreview()
    orientation.enable()
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
      provider = future.get()
      hasFrontAndBack = hasLens(CameraSelector.DEFAULT_BACK_CAMERA) && hasLens(CameraSelector.DEFAULT_FRONT_CAMERA)
      if (!hasLens(selector())) {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
        else CameraSelector.LENS_FACING_BACK
      }
      rebind()
    }, mainExecutor)
  }

  fun detach() {
    recording?.stop()
    recording = null
    orientation.disable()
    provider?.unbindAll()
    camera = null
  }

  fun release() {
    detach()
    worker.shutdown()
  }

  private fun hasLens(selector: CameraSelector) = try {
    provider?.hasCamera(selector) == true
  } catch (e: Exception) {
    false
  }

  private fun selector() = CameraSelector.Builder().requireLensFacing(lensFacing).build()

  // ---- Binding --------------------------------------------------------------------------------

  private fun rebind() {
    val provider = provider ?: return
    val owner = owner ?: return
    val view = previewView ?: return
    val selector = selector()

    caps = readCapabilities(provider, selector)
    if (manual.raw && !caps.rawSupported) manual = manual.copy(raw = false)

    val aspect = ResolutionSelector.Builder()
      .setAspectRatioStrategy(
        if (mode == CaptureMode.PHOTO) AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        else AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
      )
      .build()

    val previewBuilder = Preview.Builder().setResolutionSelector(aspect)
    // Read back the ISO, shutter and focus the camera really used, for the readouts and so manual
    // mode starts from what auto had chosen.
    Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
      override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReadout < 200) return
        lastReadout = now
        val readout = LiveReadout(
          iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
          shutterNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
          focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
        )
        mainExecutor.execute { live = readout }
      }
    })
    val preview = previewBuilder.build().also { it.setSurfaceProvider(view.surfaceProvider) }

    provider.unbindAll()
    imageCapture = null
    videoCapture = null
    analysis = null
    histogram = null

    camera = try {
      if (mode == CaptureMode.PHOTO) {
        val capture = ImageCapture.Builder()
          .setResolutionSelector(aspect)
          .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
          .setFlashMode(flashModeFor(flash))
          .apply { if (manual.raw) setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG) }
          .build()
        val analyzer = ImageAnalysis.Builder()
          .setResolutionSelector(aspect)
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .build()
          .also { a -> a.setAnalyzer(worker, HistogramAnalyzer { h -> mainExecutor.execute { histogram = h } }) }
        imageCapture = capture
        analysis = analyzer
        try {
          provider.bindToLifecycle(owner, selector, preview, capture, analyzer)
        } catch (e: IllegalArgumentException) {
          // Some cameras cannot run analysis next to a RAW or full-size capture: drop the histogram.
          analysis = null
          provider.bindToLifecycle(owner, selector, preview, capture)
        }
      } else {
        val quality = when (videoQuality) {
          VideoQuality.HD -> Quality.HD
          VideoQuality.FHD -> Quality.FHD
          VideoQuality.UHD -> Quality.UHD
        }
        val recorder = Recorder.Builder()
          .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
          .build()
        val capture = VideoCapture.withOutput(recorder)
        videoCapture = capture
        provider.bindToLifecycle(owner, selector, preview, capture)
      }
    } catch (e: Exception) {
      notice = "Camera unavailable: ${e.message}"
      null
    }

    val cam = camera ?: return
    cam.cameraInfo.zoomState.value?.let {
      minZoom = it.minZoomRatio
      maxZoom = it.maxZoomRatio
    }
    zoomRatio = 1f.coerceIn(minZoom, maxZoom)
    cam.cameraControl.setZoomRatio(zoomRatio)
    applyTorch()
    applyManual()
  }

  private fun readCapabilities(provider: ProcessCameraProvider, selector: CameraSelector): ManualCapabilities {
    val info = try {
      provider.getCameraInfo(selector)
    } catch (e: Exception) {
      return ManualCapabilities()
    }
    val c2 = Camera2CameraInfo.from(info)
    val capabilities = c2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
    val manualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
    val awbModes = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)
    val exposure = info.exposureState
    val raw = try {
      ImageCapture.OUTPUT_FORMAT_RAW_JPEG in ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
    } catch (e: Exception) {
      false
    }
    return ManualCapabilities(
      manualSensor = manualSensor,
      isoValues = if (manualSensor) isoStops(c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)) else emptyList(),
      shutterValues = if (manualSensor) shutterStops(c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)) else emptyList(),
      minFocusDiopters = if (manualSensor) c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f else 0f,
      evRange = if (exposure.isExposureCompensationSupported) exposure.exposureCompensationRange.let { it.lower..it.upper } else 0..0,
      evStep = exposure.exposureCompensationStep.toFloat(),
      whiteBalances = WhiteBalance.entries.filter { it == WhiteBalance.AUTO || it.awbMode in awbModes },
      rawSupported = raw,
      hasFlash = info.hasFlashUnit()
    )
  }

  // ---- Settings -------------------------------------------------------------------------------

  fun changeMode(newMode: CaptureMode) {
    if (newMode == mode || isRecording) return
    mode = newMode
    rebind()
  }

  fun switchLens() {
    if (!hasFrontAndBack || isRecording) return
    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
    else CameraSelector.LENS_FACING_BACK
    // Manual values belong to one sensor; the other one starts in auto.
    manual = ManualSettings(raw = manual.raw)
    rebind()
  }

  fun cycleFlash() {
    flash = when (mode) {
      // Video has no auto: the light is either on the whole time or off.
      CaptureMode.VIDEO -> if (flash == Flash.OFF) Flash.ON else Flash.OFF
      CaptureMode.PHOTO -> Flash.entries[(flash.ordinal + 1) % Flash.entries.size]
    }
    imageCapture?.flashMode = flashModeFor(flash)
    applyTorch()
  }

  private fun flashModeFor(f: Flash) = when (f) {
    Flash.OFF -> ImageCapture.FLASH_MODE_OFF
    Flash.AUTO -> ImageCapture.FLASH_MODE_AUTO
    Flash.ON -> ImageCapture.FLASH_MODE_ON
  }

  private fun applyTorch() {
    camera?.cameraControl?.enableTorch(mode == CaptureMode.VIDEO && flash == Flash.ON)
  }

  fun changeVideoQuality(q: VideoQuality) {
    if (q == videoQuality || isRecording) return
    videoQuality = q
    rebind()
  }

  fun changeLook(l: Look) {
    look = l
    applyLookToPreview()
  }

  /** The look tints the preview as a hardware layer paint, so it costs nothing per frame. */
  private fun applyLookToPreview() {
    val view = previewView ?: return
    val paint = look.paint()
    if (paint == null) view.setLayerType(View.LAYER_TYPE_NONE, null)
    else view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
  }

  fun setZoom(ratio: Float) {
    val r = ratio.coerceIn(minZoom, maxZoom)
    zoomRatio = r
    camera?.cameraControl?.setZoomRatio(r)
  }

  fun updateManual(transform: (ManualSettings) -> ManualSettings) {
    val before = manual
    var next = transform(before)
    // Leaving auto exposure or focus starts from the values the camera was using.
    if (before.exposureAuto && !next.exposureAuto) {
      next = next.copy(
        iso = live.iso?.let { iso -> caps.isoValues.getOrNull(nearestIndex(caps.isoValues, iso) { a, b -> kotlin.math.abs(a - b).toDouble() }) } ?: next.iso,
        shutterNs = live.shutterNs?.let { ns -> caps.shutterValues.getOrNull(nearestIndex(caps.shutterValues, ns) { a, b -> kotlin.math.abs(kotlin.math.ln(a.toDouble() / b)) }) } ?: next.shutterNs
      )
    }
    if (before.focusAuto && !next.focusAuto) {
      next = next.copy(focusDiopters = (live.focusDiopters ?: 0f).coerceIn(0f, caps.minFocusDiopters))
    }
    manual = next
    if (before.raw != next.raw) rebind() else applyManual()
  }

  private fun applyManual() {
    val cam = camera ?: return
    val m = manual
    val options = CaptureRequestOptions.Builder()
    if (!m.exposureAuto && caps.manualSensor) {
      options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
      options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, m.iso)
      options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, m.shutterNs)
      options.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, m.shutterNs)
    }
    if (!m.focusAuto && caps.minFocusDiopters > 0f) {
      options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
      options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, m.focusDiopters)
    }
    options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, m.whiteBalance.awbMode)
    Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options.build())
    if (m.exposureAuto && caps.evRange.last > caps.evRange.first) {
      cam.cameraControl.setExposureCompensationIndex(m.evIndex.coerceIn(caps.evRange))
    }
  }

  /** Tap to focus: meters and focuses on the point and hands focus back to auto. */
  fun focusAt(x: Float, y: Float) {
    val cam = camera ?: return
    val view = previewView ?: return
    if (!manual.focusAuto) updateManual { it.copy(focusAuto = true) }
    val point = view.meteringPointFactory.createPoint(x, y)
    val flags = if (manual.exposureAuto) FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
    else FocusMeteringAction.FLAG_AF
    cam.cameraControl.startFocusAndMetering(
      FocusMeteringAction.Builder(point, flags).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
    )
  }

  // ---- Capture --------------------------------------------------------------------------------

  fun takePhoto() {
    val capture = imageCapture ?: return
    if (busy) return
    busy = true
    val name = MediaSaver.newName()

    if (capture.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW_JPEG) {
      // RAW is the sensor data before any processing, so it is saved without the look.
      capture.takePicture(
        MediaSaver.photoOptions(context, name, "image/x-adobe-dng"),
        MediaSaver.photoOptions(context, name, "image/jpeg"),
        mainExecutor,
        savedCallback(note = if (look != Look.NATURAL) "RAW + JPEG saved (looks skip RAW)" else "RAW + JPEG saved")
      )
      return
    }

    if (look == Look.NATURAL) {
      capture.takePicture(MediaSaver.photoOptions(context, name), mainExecutor, savedCallback(note = null))
      return
    }

    val shotLook = look
    val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
    capture.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
      override fun onCaptureSuccess(image: ImageProxy) {
        val rotation = image.imageInfo.rotationDegrees
        val bitmap = try {
          image.toBitmap()
        } finally {
          image.close()
        }
        val upright = orient(bitmap, rotation, mirror)
        val processed = shotLook.applyTo(upright)
        val uri = MediaSaver.saveBitmap(context, processed, name)
        val thumb = Bitmap.createScaledBitmap(processed, 192, 192 * processed.height / processed.width, true)
        mainExecutor.execute {
          busy = false
          if (uri == null) notice = "Could not save the photo"
          else {
            lastCapture = uri
            lastIsVideo = false
            lastThumb = thumb
          }
        }
      }

      override fun onError(exception: ImageCaptureException) {
        mainExecutor.execute {
          busy = false
          notice = "Capture failed: ${exception.message}"
        }
      }
    })
  }

  private fun savedCallback(note: String?) = object : ImageCapture.OnImageSavedCallback {
    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
      busy = false
      val uri = output.savedUri ?: return
      lastCapture = uri
      lastIsVideo = false
      note?.let { notice = it }
      loadThumb(uri)
    }

    override fun onError(exception: ImageCaptureException) {
      busy = false
      notice = "Capture failed: ${exception.message}"
    }
  }

  private fun orient(bitmap: Bitmap, degrees: Int, mirror: Boolean): Bitmap {
    if (degrees == 0 && !mirror) return bitmap
    val m = Matrix().apply {
      postRotate(degrees.toFloat())
      if (mirror) postScale(-1f, 1f)
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
  }

  private fun loadThumb(uri: Uri) {
    worker.execute {
      val thumb = MediaSaver.thumbnail(context, uri)
      mainExecutor.execute { if (lastCapture == uri) lastThumb = thumb }
    }
  }

  @SuppressLint("MissingPermission")
  fun toggleRecording() {
    val capture = videoCapture ?: return
    recording?.let {
      it.stop()
      recording = null
      return
    }
    var pending = capture.output.prepareRecording(context, MediaSaver.videoOptions(context, MediaSaver.newName()))
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      pending = pending.withAudioEnabled()
    }
    recordedSeconds = 0
    recording = pending.start(mainExecutor) { event ->
      when (event) {
        is VideoRecordEvent.Start -> isRecording = true
        is VideoRecordEvent.Status -> recordedSeconds = event.recordingStats.recordedDurationNanos / 1_000_000_000L
        is VideoRecordEvent.Finalize -> {
          isRecording = false
          recording = null
          if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
            notice = "Recording failed (${event.error})"
          }
          val uri = event.outputResults.outputUri
          if (uri != Uri.EMPTY) {
            lastCapture = uri
            lastIsVideo = true
            loadThumb(uri)
          }
        }
      }
    }
  }
}
