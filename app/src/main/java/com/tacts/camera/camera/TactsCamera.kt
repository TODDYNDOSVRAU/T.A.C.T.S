package com.tacts.camera.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.ExperimentalHighSpeedVideo
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.tacts.camera.effects.LutEffect
import com.tacts.camera.effects.LutLibrary
import com.tacts.camera.effects.LutProcessor
import com.tacts.camera.filters.Look
import com.tacts.camera.filters.applyTo
import com.tacts.camera.monitor.MonitorConfig
import com.tacts.camera.monitor.MonitorFrame
import com.tacts.camera.monitor.ScopeAnalyzer
import com.tacts.camera.raw.RawVideoEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The camera engine. CameraX binds preview, photo, video, analysis and the GPU effect; camera2
 * interop sets every request key CameraX leaves alone ([RequestOptions]); a separate camera2 engine
 * records RAW video. Its fields are Compose state, so the UI reads them directly; every change goes
 * through [update], which saves the settings and rebinds or re-applies what it has to.
 */
@OptIn(ExperimentalCamera2Interop::class)
class TactsCamera(private val context: Context) {

  var settings by mutableStateOf(CameraSettings())
    private set
  var loaded by mutableStateOf(false)
    private set
  var caps by mutableStateOf(CameraCaps())
    private set
  var live by mutableStateOf(LiveReadout())
    private set
  var monitor by mutableStateOf<MonitorFrame?>(null)
    private set
  var hasFrontAndBack by mutableStateOf(false)
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
  /** Microphone level while recording, 0..1. */
  var audioLevel by mutableFloatStateOf(0f)
    private set
  var rawStats by mutableStateOf(RawVideoEngine.Stats())
    private set
  var busy by mutableStateOf(false)
    private set
  /** "3 / 10" while a burst, interval, bracket or stack is running. */
  var progress by mutableStateOf<String?>(null)
    private set
  var focusPulling by mutableStateOf(false)
    private set
  var lastCapture by mutableStateOf<Uri?>(null)
    private set
  var lastIsVideo by mutableStateOf(false)
    private set
  var lastThumb by mutableStateOf<Bitmap?>(null)
    private set
  /** A one-line notice for the UI; the UI clears it. */
  var notice by mutableStateOf<String?>(null)
  /** The video settings CameraX actually bound, for the info strip. */
  var boundVideo by mutableStateOf<String?>(null)
    private set

  val luts = LutLibrary(context)

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val store = SettingsStore(context)
  private val mainExecutor = ContextCompat.getMainExecutor(context)
  private val worker = Executors.newSingleThreadExecutor()
  private val analysisExecutor = Executors.newSingleThreadExecutor()
  private var provider: ProcessCameraProvider? = null
  private var owner: LifecycleOwner? = null
  private var previewView: PreviewView? = null
  private var camera: Camera? = null
  private var imageCapture: ImageCapture? = null
  private var videoCapture: VideoCapture<Recorder>? = null
  private var analysis: ImageAnalysis? = null
  private var recording: Recording? = null
  private var lutProcessor: LutProcessor? = null
  private var effectActive = false
  private var lastReadout = 0L
  private var saveJob: Job? = null
  private var photoJob: Job? = null
  private var pullJob: Job? = null
  @Volatile private var monitorConfig = MonitorConfig()

  private val raw = RawVideoEngine(context).apply {
    onStats = { s -> mainExecutor.execute { rawStats = s; isRecording = s.recording } }
    onError = { msg -> mainExecutor.execute { notice = msg } }
  }
  private var rawTexture: SurfaceTexture? = null
  private var rawTextureSize: Size? = null

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

  // ---- lifecycle ----

  fun attach(owner: LifecycleOwner, view: PreviewView, startMode: CaptureMode?) {
    this.owner = owner
    this.previewView = view
    orientation.enable()
    scope.launch {
      var s = store.load()
      if (startMode != null) s = s.copy(mode = startMode)
      settings = s
      loaded = true
      applyLookToPreview()
      val future = ProcessCameraProvider.getInstance(context)
      future.addListener({
        provider = future.get()
        hasFrontAndBack = hasLens(CameraSelector.DEFAULT_BACK_CAMERA) && hasLens(CameraSelector.DEFAULT_FRONT_CAMERA)
        if (!hasLens(selector())) {
          settings = settings.copy(
            lensFacing = if (settings.lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
            else CameraSelector.LENS_FACING_BACK
          )
        }
        rebind()
      }, mainExecutor)
    }
  }

  fun release() {
    recording?.stop()
    recording = null
    photoJob?.cancel()
    pullJob?.cancel()
    orientation.disable()
    provider?.unbindAll()
    raw.release()
    lutProcessor?.release()
    camera = null
    scope.cancel()
    worker.shutdown()
    analysisExecutor.shutdown()
  }

  private fun hasLens(selector: CameraSelector) = try {
    provider?.hasCamera(selector) == true
  } catch (e: Exception) {
    false
  }

  private fun selector() = CameraSelector.Builder().requireLensFacing(settings.lensFacing).build()

  // ---- settings ----

  /** Changes the settings, saves them, and rebinds or re-applies whatever the change touches. */
  fun update(transform: (CameraSettings) -> CameraSettings) {
    val before = settings
    var next = transform(before)
    if (next == before) return
    // Leaving auto exposure or focus starts from the values the camera was using.
    if (before.exposureAuto && !next.exposureAuto) {
      next = next.copy(
        iso = live.iso?.let { iso -> caps.isoValues.getOrNull(nearestIndex(caps.isoValues, iso) { a, b -> abs(a - b).toDouble() }) } ?: next.iso,
        shutterNs = live.shutterNs?.let { ns -> caps.shutterValues.getOrNull(nearestIndex(caps.shutterValues, ns) { a, b -> abs(ln(a.toDouble() / b)) }) } ?: next.shutterNs
      )
    }
    if (before.focusAuto && !next.focusAuto && next.focusDiopters == before.focusDiopters) {
      next = next.copy(focusDiopters = (live.focusDiopters ?: 0f).coerceIn(0f, caps.minFocusDiopters))
    }
    if (isRecording && needsRebind(before, next)) {
      notice = "Stop recording to change that"
      return
    }
    settings = next
    save()

    if (before.look != next.look || before.lutName != next.lutName || before.lutOnVideo != next.lutOnVideo) applyLookToPreview()
    when {
      needsRebind(before, next) -> rebind()
      else -> {
        monitorConfig = MonitorConfig.from(next, next.lensFacing == CameraSelector.LENS_FACING_FRONT)
        if (before.flash != next.flash) {
          imageCapture?.flashMode = flashModeFor(next.flash)
          applyTorch()
        }
        if (before.torchStrength != next.torchStrength) applyTorch()
        if (before.lowLightBoost != next.lowLightBoost) applyLowLight()
        if (before.metering != next.metering) applyMetering()
        applyRequest()
        if (next.mode == CaptureMode.RAW_VIDEO) raw.apply(next, caps)
      }
    }
  }

  private fun needsRebind(a: CameraSettings, b: CameraSettings): Boolean =
    a.mode != b.mode || a.lensFacing != b.lensFacing || a.physicalCameraId != b.physicalCameraId ||
      a.photoFormat != b.photoFormat || a.jpegQuality != b.jpegQuality ||
      a.videoQuality != b.videoQuality || a.frameRate != b.frameRate || a.bitrate != b.bitrate ||
      a.dynamicRange != b.dynamicRange || a.stabilization != b.stabilization || a.slowMoFps != b.slowMoFps ||
      a.audioSource != b.audioSource || effectWanted(a) != effectWanted(b) ||
      (a.scope != b.scope && (a.scope == Scope.NONE || b.scope == Scope.NONE)) ||
      ((a.peaking || a.zebras || a.falseColor) != (b.peaking || b.zebras || b.falseColor))

  private fun save() {
    saveJob?.cancel()
    saveJob = scope.launch {
      delay(300)
      store.save(settings)
    }
  }

  // ---- binding ----

  /**
   * Whether the GPU effect runs: a look or LUT on video (8-bit only, as CameraX processes it), or a
   * LUT on the photo preview, so the screen shows what the saved JPEG will get.
   */
  private fun effectWanted(s: CameraSettings) = when (s.mode) {
    CaptureMode.VIDEO -> s.lutOnVideo && s.dynamicRange == DynamicRangeChoice.SDR && (s.lutName != null || s.look != Look.NATURAL.name)
    CaptureMode.PHOTO -> s.lutName != null
    else -> false
  }

  private fun lutEffect(s: CameraSettings, targets: Int): LutEffect {
    val processor = lutProcessor ?: LutProcessor().also { lutProcessor = it }
    processor.setColorMatrix(Look.entries.firstOrNull { it.name == s.look }?.matrix()?.array)
    processor.setLut(luts.load(s.lutName))
    return LutEffect(processor, targets)
  }

  private fun monitoringWanted(s: CameraSettings) = s.scope != Scope.NONE || s.peaking || s.zebras || s.falseColor

  private fun videoFps(): Int? = when (settings.mode) {
    CaptureMode.VIDEO, CaptureMode.RAW_VIDEO -> settings.frameRate
    CaptureMode.SLOW_MO -> settings.slowMoFps
    CaptureMode.PHOTO -> null
  }

  @OptIn(ExperimentalHighSpeedVideo::class, androidx.camera.core.ExperimentalSessionConfig::class)
  private fun rebind() {
    val provider = provider ?: return
    val owner = owner ?: return
    val view = previewView ?: return
    val s = settings
    val selector = selector()

    provider.unbindAll()
    raw.close()
    camera = null
    imageCapture = null
    videoCapture = null
    analysis = null
    monitor = null
    boundVideo = null

    caps = try {
      CameraCaps.read(provider.getCameraInfo(selector))
    } catch (e: Exception) {
      CameraCaps()
    }
    monitorConfig = MonitorConfig.from(s, s.lensFacing == CameraSelector.LENS_FACING_FRONT)

    if (s.mode == CaptureMode.RAW_VIDEO) {
      // The RAW engine owns the camera; the UI shows its own TextureView and calls attachRawPreview.
      openRaw()
      return
    }

    val aspect = ResolutionSelector.Builder()
      .setAspectRatioStrategy(
        if (s.mode == CaptureMode.PHOTO) AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        else AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
      )
      .build()

    val previewBuilder = Preview.Builder().setResolutionSelector(aspect)
    if (s.stabilization == Stabilization.PREVIEW && caps.previewStabilization) previewBuilder.setPreviewStabilizationEnabled(true)
    val interop = Camera2Interop.Extender(previewBuilder)
    s.physicalCameraId?.takeIf { it in caps.physicalCameraIds }?.let { interop.setPhysicalCameraId(it) }
    interop.setSessionCaptureCallback(readoutCallback)
    val preview = previewBuilder.build().also { it.setSurfaceProvider(view.surfaceProvider) }

    camera = try {
      when (s.mode) {
        CaptureMode.PHOTO -> bindPhoto(provider, owner, selector, preview, aspect, s)
        CaptureMode.VIDEO -> bindVideo(provider, owner, selector, preview, aspect, s)
        CaptureMode.SLOW_MO -> {
          val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
          val capture = VideoCapture.withOutput(recorder)
          videoCapture = capture
          val fps = s.slowMoFps.takeIf { it in caps.highSpeedFps } ?: caps.highSpeedFps.firstOrNull() ?: 120
          val config = HighSpeedVideoSessionConfig.Builder(capture)
            .setPreview(preview)
            .setFrameRateRange(Range(fps, fps))
            .setSlowMotionEnabled(true)
            .build()
          boundVideo = "HD ${fps}p slow motion"
          provider.bindToLifecycle(owner, selector, config)
        }
        CaptureMode.RAW_VIDEO -> null
      }
    } catch (e: Exception) {
      notice = when (s.mode) {
        CaptureMode.SLOW_MO -> "Slow motion is not available on this camera"
        else -> "Camera unavailable: ${e.message}"
      }
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
    applyLowLight()
    applyRequest()
    applyMetering()
  }

  private fun bindPhoto(
    provider: ProcessCameraProvider,
    owner: LifecycleOwner,
    selector: CameraSelector,
    preview: Preview,
    aspect: ResolutionSelector,
    s: CameraSettings
  ): Camera {
    val format = when (s.photoFormat.takeIf { it in caps.photoFormats } ?: PhotoFormat.JPEG) {
      PhotoFormat.JPEG -> ImageCapture.OUTPUT_FORMAT_JPEG
      PhotoFormat.ULTRA_HDR -> ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR
      PhotoFormat.RAW -> ImageCapture.OUTPUT_FORMAT_RAW
      PhotoFormat.RAW_JPEG -> ImageCapture.OUTPUT_FORMAT_RAW_JPEG
    }
    val captureBuilder = ImageCapture.Builder()
      .setResolutionSelector(aspect)
      .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
      .setFlashMode(flashModeFor(s.flash))
      .setJpegQuality(s.jpegQuality)
      .setOutputFormat(format)
    s.physicalCameraId?.takeIf { it in caps.physicalCameraIds }?.let { Camera2Interop.Extender(captureBuilder).setPhysicalCameraId(it) }
    val capture = captureBuilder.build()
    imageCapture = capture
    effectActive = effectWanted(s)
    applyLookToPreview()
    fun group(vararg extra: androidx.camera.core.UseCase) = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture).apply {
      extra.forEach { addUseCase(it) }
      if (effectActive) addEffect(lutEffect(s, androidx.camera.core.CameraEffect.PREVIEW))
    }.build()
    // The histogram and the monitoring tools always run in photo mode; they are cheap at this size.
    val analyzer = analyzer(aspect)
    return try {
      analysis = analyzer
      provider.bindToLifecycle(owner, selector, group(analyzer))
    } catch (e: IllegalArgumentException) {
      // Some cameras cannot run analysis next to a RAW or full-size capture.
      analysis = null
      provider.unbindAll()
      provider.bindToLifecycle(owner, selector, group())
    }
  }

  @SuppressLint("RestrictedApi")
  private fun bindVideo(
    provider: ProcessCameraProvider,
    owner: LifecycleOwner,
    selector: CameraSelector,
    preview: Preview,
    aspect: ResolutionSelector,
    s: CameraSettings
  ): Camera {
    val quality = when (s.videoQuality) {
      VideoQuality.SD -> Quality.SD
      VideoQuality.HD -> Quality.HD
      VideoQuality.FHD -> Quality.FHD
      VideoQuality.UHD -> Quality.UHD
    }
    val recorderBuilder = Recorder.Builder()
      .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
      .setAudioSource(audioSourceFor(s.audioSource))
    if (s.bitrate != Bitrate.AUTO) recorderBuilder.setTargetVideoEncodingBitRate(s.bitrate.mbps * 1_000_000)
    val dynamicRange = when (s.dynamicRange.takeIf { it in caps.dynamicRanges } ?: DynamicRangeChoice.SDR) {
      DynamicRangeChoice.SDR -> DynamicRange.SDR
      DynamicRangeChoice.HLG -> DynamicRange.HLG_10_BIT
      DynamicRangeChoice.HDR10 -> DynamicRange.HDR10_10_BIT
      DynamicRangeChoice.HDR10_PLUS -> DynamicRange.HDR10_PLUS_10_BIT
      DynamicRangeChoice.DOLBY_VISION -> DynamicRange.DOLBY_VISION_10_BIT
    }
    val fps = s.frameRate.takeIf { it in caps.frameRates } ?: caps.frameRates.lastOrNull { it <= 30 } ?: 30
    val captureBuilder = VideoCapture.Builder(recorderBuilder.build())
      .setDynamicRange(dynamicRange)
      .setTargetFrameRate(Range(fps, fps))
    if (s.stabilization != Stabilization.OFF && caps.videoStabilization) captureBuilder.setVideoStabilizationEnabled(true)
    s.physicalCameraId?.takeIf { it in caps.physicalCameraIds }?.let { Camera2Interop.Extender(captureBuilder).setPhysicalCameraId(it) }
    val capture = captureBuilder.build()
    videoCapture = capture
    boundVideo = buildString {
      append(s.videoQuality.label).append(' ').append(fps).append('p')
      if (dynamicRange != DynamicRange.SDR) append(" · ").append(s.dynamicRange.label)
      if (s.bitrate != Bitrate.AUTO) append(" · ").append(s.bitrate.mbps).append(" Mb/s")
    }

    val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture)
    if (effectWanted(s)) group.addEffect(lutEffect(s, androidx.camera.core.CameraEffect.PREVIEW or androidx.camera.core.CameraEffect.VIDEO_CAPTURE))
    effectActive = effectWanted(s)
    applyLookToPreview()

    // Monitoring needs a third stream; not every camera can add it next to video.
    if (monitoringWanted(s)) {
      val analyzer = analyzer(aspect)
      try {
        analysis = analyzer
        return provider.bindToLifecycle(owner, selector, group.addUseCase(analyzer).build())
      } catch (e: IllegalArgumentException) {
        analysis = null
        notice = "Scopes are not available while filming on this camera"
        provider.unbindAll()
        val plain = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture)
        if (effectActive) plain.addEffect(LutEffect(lutProcessor!!))
        return provider.bindToLifecycle(owner, selector, plain.build())
      }
    }
    return provider.bindToLifecycle(owner, selector, group.build())
  }

  private fun analyzer(aspect: ResolutionSelector) = ImageAnalysis.Builder()
    .setResolutionSelector(aspect)
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .build()
    .also { a -> a.setAnalyzer(analysisExecutor, ScopeAnalyzer({ monitorConfig }) { f -> mainExecutor.execute { monitor = f } }) }

  private fun audioSourceFor(a: AudioSource) = when (a) {
    AudioSource.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
    AudioSource.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
    AudioSource.VOICE -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    AudioSource.MIC -> MediaRecorder.AudioSource.MIC
  }

  /** Reads back what the camera really used, for the readouts, Kelvin mode and face boxes. */
  private val readoutCallback = object : CameraCaptureSession.CaptureCallback() {
    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
      val now = SystemClock.elapsedRealtime()
      if (now - lastReadout < 200) return
      lastReadout = now
      val readout = LiveReadout(
        iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
        shutterNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
        focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
        frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION),
        ccTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM),
        ccGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS),
        faces = result.get(CaptureResult.STATISTICS_FACES)?.map { it.bounds } ?: emptyList()
      )
      mainExecutor.execute {
        // Kelvin mode keeps the colour transform auto had chosen; do not overwrite it with its own.
        live = if (settings.wbMode == WhiteBalanceMode.AUTO) readout else readout.copy(ccTransform = live.ccTransform ?: readout.ccTransform, ccGains = live.ccGains)
      }
    }
  }

  // ---- RAW video ----

  /** The UI's TextureView for RAW video; the engine opens the camera once it has both. */
  fun attachRawPreview(texture: SurfaceTexture?, size: Size?) {
    rawTexture = texture
    rawTextureSize = size
    if (settings.mode == CaptureMode.RAW_VIDEO) openRaw()
  }

  private fun openRaw() {
    val texture = rawTexture ?: return
    val size = rawTextureSize ?: return
    if (!caps.rawCapable) {
      notice = "This camera has no RAW stream"
      return
    }
    // Preview at a modest 16:9 size; the RAW stream is always the full sensor.
    raw.open(caps.cameraId, texture, Size(1920, 1080).takeIf { size.width > 0 } ?: size, settings, caps)
  }

  // ---- request keys ----

  private fun applyRequest() {
    val cam = camera ?: return
    Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(RequestOptions.build(settings, caps, live, videoFps()))
    if (settings.exposureAuto && caps.evRange.last > caps.evRange.first) {
      cam.cameraControl.setExposureCompensationIndex(settings.evIndex.coerceIn(caps.evRange))
    }
  }

  private fun flashModeFor(f: Flash) = when (f) {
    Flash.OFF -> ImageCapture.FLASH_MODE_OFF
    Flash.AUTO -> ImageCapture.FLASH_MODE_AUTO
    Flash.ON -> ImageCapture.FLASH_MODE_ON
  }

  private fun applyTorch() {
    val cam = camera ?: return
    val on = settings.mode != CaptureMode.PHOTO && settings.flash == Flash.ON
    cam.cameraControl.enableTorch(on)
    if (on && caps.maxTorchStrength > 1 && settings.torchStrength > 0) {
      runCatching { cam.cameraControl.setTorchStrengthLevel(settings.torchStrength.coerceIn(1, caps.maxTorchStrength)) }
    }
  }

  private fun applyLowLight() {
    val cam = camera ?: return
    if (caps.lowLightBoost) runCatching { cam.cameraControl.enableLowLightBoostAsync(settings.lowLightBoost) }
  }

  /** Centre metering is a fixed region in the middle; spot follows the last tap; matrix is the camera's own. */
  private fun applyMetering() {
    val cam = camera ?: return
    val view = previewView ?: return
    when (settings.metering) {
      Metering.MATRIX -> cam.cameraControl.cancelFocusAndMetering()
      Metering.CENTER -> {
        val point = view.meteringPointFactory.createPoint(view.width / 2f, view.height / 2f, 0.3f)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AE).disableAutoCancel().build())
      }
      Metering.SPOT -> Unit
    }
  }

  // ---- looks on the preview ----

  /** Without the GPU effect, the look tints the preview as a hardware layer paint. */
  private fun applyLookToPreview() {
    val view = previewView ?: return
    val look = Look.entries.firstOrNull { it.name == settings.look } ?: Look.NATURAL
    lutProcessor?.let {
      it.setColorMatrix(look.matrix()?.array)
      it.setLut(luts.load(settings.lutName))
    }
    val paint = if (effectActive) null else look.paint()
    if (paint == null) view.setLayerType(View.LAYER_TYPE_NONE, null)
    else view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
  }

  // ---- zoom, focus, exposure points ----

  fun setZoom(ratio: Float) {
    val r = ratio.coerceIn(minZoom, maxZoom)
    zoomRatio = r
    camera?.cameraControl?.setZoomRatio(r)
  }

  /** Tap: focus (and meter, unless exposure is manual or metering is centre-weighted) on the point. */
  fun focusAt(x: Float, y: Float) {
    val cam = camera ?: return
    val view = previewView ?: return
    if (!settings.focusAuto) update { it.copy(focusAuto = true) }
    val point = view.meteringPointFactory.createPoint(x, y)
    var flags = FocusMeteringAction.FLAG_AF
    if (settings.exposureAuto && !settings.aeLock && settings.metering != Metering.CENTER) flags = flags or FocusMeteringAction.FLAG_AE
    cam.cameraControl.startFocusAndMetering(
      FocusMeteringAction.Builder(point, flags).setAutoCancelDuration(5, TimeUnit.SECONDS).build()
    )
  }

  /** The separate exposure reticle: meter on the point only, focus stays where it is. */
  fun exposeAt(x: Float, y: Float) {
    val cam = camera ?: return
    val view = previewView ?: return
    if (!settings.exposureAuto) return
    val point = view.meteringPointFactory.createPoint(x, y, 0.08f)
    cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AE).disableAutoCancel().build())
  }

  /**
   * Pulls focus from A to B (or back, if the lens is already at B) over the set duration, eased at
   * both ends like a follow-focus.
   */
  fun pullFocus() {
    val a = settings.focusPullA ?: return
    val b = settings.focusPullB ?: return
    pullJob?.cancel()
    val atB = abs(settings.focusDiopters - b) < abs(settings.focusDiopters - a)
    val from = if (atB) b else a
    val to = if (atB) a else b
    pullJob = scope.launch {
      focusPulling = true
      val start = SystemClock.elapsedRealtime()
      val duration = settings.focusPullMs.coerceAtLeast(100)
      while (isActive) {
        val t = ((SystemClock.elapsedRealtime() - start).toFloat() / duration).coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t)
        settings = settings.copy(focusAuto = false, focusDiopters = from + (to - from) * eased)
        applyRequest()
        if (t >= 1f) break
        delay(16)
      }
      save()
      focusPulling = false
    }
  }

  // ---- capture ----

  /** The shutter button: what it does depends on the mode, and on the photo mode. */
  fun shutter() {
    when (settings.mode) {
      CaptureMode.VIDEO, CaptureMode.SLOW_MO -> toggleRecording()
      CaptureMode.RAW_VIDEO -> toggleRaw()
      CaptureMode.PHOTO -> {
        photoJob?.let {
          // A second press stops a running interval or burst.
          it.cancel()
          photoJob = null
          progress = null
          busy = false
          return
        }
        if (busy) return
        photoJob = scope.launch {
          try {
            runPhotoMode()
          } finally {
            progress = null
            busy = false
            photoJob = null
          }
        }
      }
    }
  }

  private suspend fun runPhotoMode() {
    val s = settings
    when (s.photoMode) {
      PhotoMode.SINGLE -> capture()
      PhotoMode.BURST -> repeat(s.burstCount) { i ->
        progress = "${i + 1} / ${s.burstCount}"
        capture()
      }
      PhotoMode.INTERVAL -> repeat(s.intervalCount) { i ->
        progress = "${i + 1} / ${s.intervalCount}"
        val started = SystemClock.elapsedRealtime()
        capture()
        delay((s.intervalSeconds * 1000L - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0))
      }
      PhotoMode.BRACKET -> bracket(s)
      PhotoMode.FOCUS_BRACKET -> focusBracket(s)
      PhotoMode.NIGHT -> nightStack(s)
    }
  }

  /** AE bracketing: the same frame at -n…+n stops, by shutter when exposure is manual, by EV otherwise. */
  private suspend fun bracket(s: CameraSettings) {
    val shots = s.bracketShots
    val offsets = (0 until shots).map { (it - (shots - 1) / 2f) * s.bracketStops }
    offsets.forEachIndexed { i, stops ->
      progress = "${i + 1} / $shots · ${if (stops >= 0) "+" else ""}${"%.1f".format(stops)} EV"
      if (!s.exposureAuto && caps.manualSensor) {
        val ns = (s.shutterNs * 2.0.pow(stops.toDouble())).toLong()
        settings = s.copy(shutterNs = caps.exposureRange?.let { ns.coerceIn(it.lower, it.upper) } ?: ns)
        applyRequest()
        delay(150)
      } else {
        val idx = if (caps.evStep > 0f) (stops / caps.evStep).roundToInt().coerceIn(caps.evRange) else 0
        camera?.cameraControl?.setExposureCompensationIndex(idx)
        delay(450) // let auto exposure settle on the new target
      }
      capture()
    }
    settings = s
    applyRequest()
  }

  /** Focus bracketing: from infinity to the closest focus in even steps, for focus stacking later. */
  private suspend fun focusBracket(s: CameraSettings) {
    if (caps.minFocusDiopters <= 0f) {
      notice = "This lens has fixed focus"
      return
    }
    val shots = s.focusBracketShots.coerceAtLeast(2)
    for (i in 0 until shots) {
      progress = "${i + 1} / $shots"
      settings = s.copy(focusAuto = false, focusDiopters = caps.minFocusDiopters * i / (shots - 1))
      applyRequest()
      delay(250)
      capture()
    }
    settings = s
    applyRequest()
  }

  /**
   * Night mode: several frames averaged into one, which lowers noise by the square root of their
   * number. Frames are taken as fast as the camera allows; a tripod or a steady hand keeps them aligned.
   */
  private suspend fun nightStack(s: CameraSettings) {
    val capture = imageCapture ?: return
    if (capture.outputFormat != ImageCapture.OUTPUT_FORMAT_JPEG) {
      notice = "Night mode needs the JPEG format"
      return
    }
    busy = true
    var acc: IntArray? = null
    var w = 0
    var h = 0
    var count = 0
    val mirror = s.lensFacing == CameraSelector.LENS_FACING_FRONT
    for (i in 0 until s.nightFrames) {
      progress = "Night ${i + 1} / ${s.nightFrames}"
      val frame = captureBitmap(capture, mirror) ?: continue
      if (acc == null) {
        w = frame.width
        h = frame.height
        acc = IntArray(w * h * 3)
      }
      if (frame.width != w || frame.height != h) continue
      val row = IntArray(w)
      for (y in 0 until h) {
        frame.getPixels(row, 0, w, 0, y, w, 1)
        val base = y * w * 3
        for (x in 0 until w) {
          val p = row[x]
          acc[base + x * 3] += (p shr 16) and 0xFF
          acc[base + x * 3 + 1] += (p shr 8) and 0xFF
          acc[base + x * 3 + 2] += p and 0xFF
        }
      }
      frame.recycle()
      count++
    }
    val sum = acc ?: return
    progress = "Night · merging"
    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val row = IntArray(w)
    for (y in 0 until h) {
      val base = y * w * 3
      for (x in 0 until w) {
        row[x] = (0xFF shl 24) or ((sum[base + x * 3] / count) shl 16) or ((sum[base + x * 3 + 1] / count) shl 8) or (sum[base + x * 3 + 2] / count)
      }
      result.setPixels(row, 0, w, 0, y, w, 1)
    }
    saveProcessed(result, s)
  }

  /** One photo in the current format, with the look and LUT applied to JPEGs. Suspends until saved. */
  private suspend fun capture() {
    val capture = imageCapture ?: return
    busy = true
    val s = settings
    val name = MediaSaver.newName()
    val wantsProcessing = s.look != Look.NATURAL.name || s.lutName != null
    when {
      capture.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW_JPEG -> saveFiles(capture, name, raw = true, jpeg = true)
      capture.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW -> saveFiles(capture, name, raw = true, jpeg = false)
      capture.outputFormat == ImageCapture.OUTPUT_FORMAT_JPEG && wantsProcessing -> {
        val bitmap = captureBitmap(capture, s.lensFacing == CameraSelector.LENS_FACING_FRONT) ?: return
        saveProcessed(bitmap, s)
      }
      else -> saveFiles(capture, name, raw = false, jpeg = true)
    }
  }

  private suspend fun saveFiles(capture: ImageCapture, name: String, raw: Boolean, jpeg: Boolean) =
    suspendCancellableCoroutine { cont ->
      val callback = object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
          output.savedUri?.let { uri ->
            lastCapture = uri
            lastIsVideo = false
            loadThumb(uri)
          }
          if (cont.isActive) cont.resume(Unit)
        }

        override fun onError(exception: ImageCaptureException) {
          notice = "Capture failed: ${exception.message}"
          if (cont.isActive) cont.resume(Unit)
        }
      }
      when {
        raw && jpeg -> capture.takePicture(
          MediaSaver.photoOptions(context, name, "image/x-adobe-dng"),
          MediaSaver.photoOptions(context, name, "image/jpeg"),
          mainExecutor,
          callback
        )
        raw -> capture.takePicture(MediaSaver.photoOptions(context, name, "image/x-adobe-dng"), mainExecutor, callback)
        else -> capture.takePicture(MediaSaver.photoOptions(context, name), mainExecutor, callback)
      }
    }

  private suspend fun captureBitmap(capture: ImageCapture, mirror: Boolean): Bitmap? = suspendCancellableCoroutine { cont ->
    capture.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
      override fun onCaptureSuccess(image: ImageProxy) {
        val rotation = image.imageInfo.rotationDegrees
        val bitmap = try {
          image.toBitmap()
        } finally {
          image.close()
        }
        if (cont.isActive) cont.resume(orient(bitmap, rotation, mirror))
      }

      override fun onError(exception: ImageCaptureException) {
        mainExecutor.execute { notice = "Capture failed: ${exception.message}" }
        if (cont.isActive) cont.resume(null)
      }
    })
  }

  /** Applies the look and LUT on a worker thread and saves the JPEG. */
  private suspend fun saveProcessed(bitmap: Bitmap, s: CameraSettings) = suspendCancellableCoroutine { cont ->
    worker.execute {
      val look = Look.entries.firstOrNull { it.name == s.look } ?: Look.NATURAL
      var out = look.applyTo(bitmap)
      luts.load(s.lutName)?.let { out = it.applyTo(out) }
      val uri = MediaSaver.saveBitmap(context, out, MediaSaver.newName(), s.jpegQuality)
      val thumb = Bitmap.createScaledBitmap(out, 192, (192 * out.height / out.width).coerceAtLeast(1), true)
      mainExecutor.execute {
        if (uri == null) notice = "Could not save the photo" else {
          lastCapture = uri
          lastIsVideo = false
          lastThumb = thumb
        }
        if (cont.isActive) cont.resume(Unit)
      }
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

  // ---- video ----

  @SuppressLint("MissingPermission")
  private fun toggleRecording() {
    val capture = videoCapture ?: return
    recording?.let {
      it.stop()
      recording = null
      return
    }
    val clip = settings.clipName()
    var pending = capture.output.prepareRecording(context, MediaSaver.videoOptions(context, clip))
    if (settings.audioEnabled && settings.mode != CaptureMode.SLOW_MO &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    ) {
      pending = pending.withAudioEnabled()
    }
    recordedSeconds = 0
    audioLevel = 0f
    recording = pending.start(mainExecutor) { event ->
      when (event) {
        is VideoRecordEvent.Start -> isRecording = true
        is VideoRecordEvent.Status -> {
          recordedSeconds = event.recordingStats.recordedDurationNanos / 1_000_000_000L
          val amp = event.recordingStats.audioStats.audioAmplitude
          audioLevel = if (amp.isNaN()) 0f else amp.toFloat().coerceIn(0f, 1f)
        }
        is VideoRecordEvent.Finalize -> {
          isRecording = false
          recording = null
          audioLevel = 0f
          if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
            notice = "Recording failed (${event.error})"
          }
          val uri = event.outputResults.outputUri
          if (uri != Uri.EMPTY) {
            lastCapture = uri
            lastIsVideo = true
            loadThumb(uri)
            update { it.copy(clipCounter = it.clipCounter + 1) }
          }
        }
      }
    }
  }

  private fun toggleRaw() {
    if (rawStats.recording) {
      raw.stop()
      notice = "${rawStats.written} DNG frames in DCIM/TACTS/${rawStats.folder}"
      update { it.copy(clipCounter = it.clipCounter + 1) }
    } else {
      raw.start(settings.clipName() + "_RAW")
    }
  }

  // ---- lenses ----

  fun switchLens() {
    if (!hasFrontAndBack || isRecording) return
    update {
      // Manual values belong to one sensor; the other one starts in auto.
      it.copy(
        lensFacing = if (it.lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK,
        physicalCameraId = null,
        exposureAuto = true,
        focusAuto = true,
        focusPullA = null,
        focusPullB = null
      )
    }
  }
}
