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
import androidx.camera.camera2.interop.Camera2CameraInfo
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
import com.tacts.camera.photo.Frame
import com.tacts.camera.photo.MultiFrame
import com.tacts.camera.photo.PhotoOutput
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
  /** The latest colour readout when only it changed; kept out of Compose state (see readoutCallback). */
  private var colour: LiveReadout? = null
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
  /** The lenses of the current facing, for the zoom capsule. */
  var lensStops by mutableStateOf<List<LensStop>>(emptyList())
    private set
  /** The zoom as the phone's own camera app names it: 0.6 on the ultra-wide, 3 on a 3× tele. */
  val zoomFactor: Float
    get() = (settings.cameraId?.let { id -> lensStops.firstOrNull { it.cameraId == id }?.factor } ?: 1f) * zoomRatio
  /** The zoom to set after the next bind; null keeps the current one. */
  private var pendingZoom: Float? = null

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
    onFinished = { path, frames, dropped ->
      mainExecutor.execute {
        notice = "$frames DNG frames in $path" + if (dropped > 0) ", $dropped dropped" else ""
      }
    }
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

  private fun selector(): CameraSelector {
    val builder = CameraSelector.Builder().requireLensFacing(settings.lensFacing)
    settings.cameraId?.let { id ->
      builder.addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id }.ifEmpty { infos } }
    }
    return builder.build()
  }

  /** The camera ids CameraX can open for the current facing, the one it picks by default first. */
  private fun facingCameraIds(provider: ProcessCameraProvider): List<String> = try {
    val default = Camera2CameraInfo.from(provider.getCameraInfo(CameraSelector.Builder().requireLensFacing(settings.lensFacing).build())).cameraId
    val all = provider.availableCameraInfos
      .filter { it.lensFacing == settings.lensFacing }
      .map { Camera2CameraInfo.from(it).cameraId }
    listOf(default) + (all - default)
  } catch (e: Exception) {
    emptyList()
  }

  // ---- settings ----

  /** Changes the settings, saves them, and rebinds or re-applies whatever the change touches. */
  fun update(transform: (CameraSettings) -> CameraSettings) = update(false, transform)

  /** Applies a saved preset as it was saved, without taking over the live exposure and focus. */
  fun applyPreset(p: Preset) = update(true) { it.withPreset(p) }

  private fun update(exact: Boolean, transform: (CameraSettings) -> CameraSettings) {
    val before = settings
    var next = transform(before)
    if (next == before) return
    // Leaving auto exposure or focus starts from the values the camera was using.
    if (!exact && before.exposureAuto && !next.exposureAuto) {
      next = next.copy(
        iso = live.iso?.let { iso -> caps.isoValues.getOrNull(nearestIndex(caps.isoValues, iso) { a, b -> abs(a - b).toDouble() }) } ?: next.iso,
        shutterNs = live.shutterNs?.let { ns -> caps.shutterValues.getOrNull(nearestIndex(caps.shutterValues, ns) { a, b -> abs(ln(a.toDouble() / b)) }) } ?: next.shutterNs
      )
    }
    if (!exact && before.focusAuto && !next.focusAuto && next.focusDiopters == before.focusDiopters) {
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
    a.mode != b.mode || a.lensFacing != b.lensFacing || a.physicalCameraId != b.physicalCameraId || a.cameraId != b.cameraId ||
      a.photoFormat != b.photoFormat || a.jpegQuality != b.jpegQuality || a.zeroShutterLag != b.zeroShutterLag ||
      a.videoQuality != b.videoQuality || a.frameRate != b.frameRate || a.bitrate != b.bitrate ||
      a.dynamicRange != b.dynamicRange || a.stabilization != b.stabilization || a.slowMoFps != b.slowMoFps ||
      a.audioSource != b.audioSource || effectWanted(a) != effectWanted(b) ||
      (a.scope != b.scope && (a.scope == Scope.NONE || b.scope == Scope.NONE)) || tintsPreview(a) != tintsPreview(b) ||
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

  /**
   * Whether the preview needs the look's layer paint: a look the GPU effect does not already draw.
   * Only a TextureView takes a layer paint; otherwise the preview is a SurfaceView, which the system
   * composes directly without a redraw of the app for every camera frame.
   */
  private fun tintsPreview(s: CameraSettings) = s.look != Look.NATURAL.name && !effectWanted(s)

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
    lensStops = Lenses.stops(context, facingCameraIds(provider))
    // A lens of its own that this phone no longer lists falls back to the default camera.
    if (s.cameraId != null && lensStops.none { it.cameraId == s.cameraId }) {
      update { it.copy(cameraId = null) }
      return
    }
    monitorConfig = MonitorConfig.from(s, s.lensFacing == CameraSelector.LENS_FACING_FRONT)

    // A mode saved from another camera or lens may not exist on this one.
    if (s.mode == CaptureMode.SLOW_MO && caps.highSpeedFps.isEmpty()) {
      update { it.copy(mode = CaptureMode.VIDEO) }
      return
    }

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

    // High-speed sessions choose the preview size themselves and reject a resolution selector.
    val previewBuilder = Preview.Builder().apply { if (s.mode != CaptureMode.SLOW_MO) setResolutionSelector(aspect) }
    if (s.stabilization == Stabilization.PREVIEW && caps.previewStabilization) previewBuilder.setPreviewStabilizationEnabled(true)
    val interop = Camera2Interop.Extender(previewBuilder)
    s.physicalCameraId?.takeIf { it in caps.physicalCameraIds }?.let { interop.setPhysicalCameraId(it) }
    interop.setSessionCaptureCallback(readoutCallback)
    view.implementationMode = if (tintsPreview(s)) PreviewView.ImplementationMode.COMPATIBLE else PreviewView.ImplementationMode.PERFORMANCE
    val preview = previewBuilder.build().also { it.setSurfaceProvider(view.surfaceProvider) }

    camera = try {
      when (s.mode) {
        CaptureMode.PHOTO -> bindPhoto(provider, owner, selector, preview, aspect, s)
        CaptureMode.VIDEO -> bindVideo(provider, owner, selector, preview, aspect, s)
        CaptureMode.SLOW_MO -> {
          // High-speed sessions only take the qualities the camera lists for them, not the normal ones.
          val quality = Recorder.getHighSpeedVideoCapabilities(provider.getCameraInfo(selector))
            ?.getSupportedQualities(DynamicRange.SDR)
            ?.firstOrNull()
            ?: throw IllegalStateException("no high-speed quality")
          val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality))
            .build()
          val capture = VideoCapture.withOutput(recorder)
          videoCapture = capture
          val fps = s.slowMoFps.takeIf { it in caps.highSpeedFps } ?: caps.highSpeedFps.firstOrNull() ?: 120
          val config = HighSpeedVideoSessionConfig.Builder(capture)
            .setPreview(preview)
            .setFrameRateRange(Range(fps, fps))
            .setSlowMotionEnabled(true)
            .build()
          boundVideo = "${quality.toString().removePrefix("QUALITY_")} ${fps}p slow motion"
          provider.bindToLifecycle(owner, selector, config)
        }
        CaptureMode.RAW_VIDEO -> null
      }
    } catch (e: Exception) {
      android.util.Log.w("TACTS", "bind failed in ${s.mode}", e)
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
    // The zoom survives a mode change, as in the phone's own camera app.
    zoomRatio = (pendingZoom ?: zoomRatio).coerceIn(minZoom, maxZoom)
    pendingZoom = null
    cam.cameraControl.setZoomRatio(zoomRatio)
    applyTorch()
    applyLowLight()
    applyRequest()
    applyMetering()
  }

  private fun zslSupported(provider: ProcessCameraProvider, selector: CameraSelector): Boolean = try {
    provider.getCameraInfo(selector).isZslSupported
  } catch (e: Exception) {
    false
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
      .setCaptureMode(
        // Zero shutter lag keeps a ring of full-resolution frames and saves the one at the press.
        // CameraX turns it off by itself with flash, RAW or an effect bound.
        if (s.zeroShutterLag && zslSupported(provider, selector)) ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
        else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
      )
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
        faces = result.get(CaptureResult.STATISTICS_FACES)?.map { it.bounds } ?: emptyList(),
        activeLens = if (android.os.Build.VERSION.SDK_INT >= 29) result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null
      )
      if (readout.activeLens != live.activeLens) android.util.Log.i("TACTS", "active lens ${readout.activeLens} at zoom $zoomRatio")
      mainExecutor.execute {
        // Kelvin mode keeps the colour transform auto had chosen; do not overwrite it with its own.
        val next = if (settings.wbMode == WhiteBalanceMode.AUTO) readout else readout.copy(ccTransform = live.ccTransform ?: readout.ccTransform, ccGains = live.ccGains)
        // The colour transform and gains change on every frame in auto and only feed Kelvin mode; the
        // screen shows the other values, so the UI recomposes only when one of those changes.
        if (next.copy(ccTransform = null, ccGains = null, faces = emptyList()) != live.copy(ccTransform = null, ccGains = null, faces = emptyList())) {
          live = next
          colour = null
        } else colour = next
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
    Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(RequestOptions.build(settings, caps, colour?.let { live.copy(ccTransform = it.ccTransform, ccGains = it.ccGains) } ?: live, videoFps()))
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

  /** Off, auto, on in photo; off and torch in video. */
  fun cycleFlash() = update {
    it.copy(
      flash = if (it.mode != CaptureMode.PHOTO) (if (it.flash == Flash.OFF) Flash.ON else Flash.OFF)
      else Flash.entries[(it.flash.ordinal + 1) % Flash.entries.size]
    )
  }

  /** The next frame rate this camera offers for the current mode. */
  fun cycleFrameRate() {
    val rates = if (settings.mode == CaptureMode.SLOW_MO) caps.highSpeedFps else caps.frameRates
    if (rates.isEmpty()) return
    update {
      if (it.mode == CaptureMode.SLOW_MO) it.copy(slowMoFps = rates[(rates.indexOf(it.slowMoFps) + 1) % rates.size])
      else it.copy(frameRate = rates[(rates.indexOf(it.frameRate) + 1) % rates.size])
    }
  }

  /** "4K" or "4K HLG": the quality and, beyond SDR, the dynamic range. */
  fun qualityLabel(): String {
    val range = when (settings.dynamicRange) {
      DynamicRangeChoice.SDR -> ""
      DynamicRangeChoice.HLG -> " HLG"
      DynamicRangeChoice.HDR10 -> " HDR10"
      DynamicRangeChoice.HDR10_PLUS -> " HDR10+"
      DynamicRangeChoice.DOLBY_VISION -> " DV"
    }
    return settings.videoQuality.label + range
  }

  /** Jumps to a lens of the zoom capsule, rebinding when it is a camera of its own. */
  fun selectLens(stop: LensStop) {
    if (stop.cameraId == settings.cameraId) {
      setZoom(stop.zoom)
      return
    }
    if (isRecording) {
      notice = "Stop recording to change lens"
      return
    }
    pendingZoom = stop.zoom
    update { it.copy(cameraId = stop.cameraId, physicalCameraId = null) }
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
      PhotoMode.HDR -> hdrMerge(s)
      PhotoMode.SUPER_RES -> superRes(s)
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
   * Night mode: several frames aligned and averaged, which lowers noise by about the square root of
   * their number. Whatever moved between frames is taken from the first one only.
   */
  private suspend fun nightStack(s: CameraSettings) {
    val frames = burst("Night", s.nightFrames) ?: return
    merged(s, frames.first, frames.second, 0, "Night, ${frames.first.size} frames aligned and averaged", "Night") { f, step ->
      MultiFrame.night(f, 0, step)
    }
  }

  /** HDR: three exposures, under, middle and over by [CameraSettings.hdrStops], fused into one. */
  private suspend fun hdrMerge(s: CameraSettings) {
    val capture = imageCapture ?: return
    if (!jpegOnly(capture, "HDR")) return
    busy = true
    val stops = listOf(-s.hdrStops, 0f, s.hdrStops)
    val frames = mutableListOf<Frame>()
    var rotation = 0
    try {
      stops.forEachIndexed { i, ev ->
        progress = "HDR ${i + 1} / 3 · ${if (ev >= 0) "+" else ""}${"%.1f".format(ev)} EV"
        exposeBy(s, ev)
        val (bytes, rot) = captureJpeg(capture) ?: return
        frames += Frame(bytes)
        rotation = rot
      }
    } finally {
      settings = s
      applyRequest()
    }
    merged(s, frames, rotation, 1, "HDR, 3 exposures at -/+${"%.1f".format(java.util.Locale.US, s.hdrStops)} EV fused", "HDR") { f, step ->
      MultiFrame.hdr(f, 1, step)
    }
  }

  /** Super-resolution: shifted frames added on a grid twice as fine, for a picture of twice the width and height. */
  private suspend fun superRes(s: CameraSettings) {
    val frames = burst("Super-res", s.superResFrames) ?: return
    val w = frames.first[0].width * 2
    val h = frames.first[0].height * 2
    merged(s, frames.first, frames.second, 0, "Super-resolution ${w}x$h from ${frames.first.size} frames of ${w / 2}x${h / 2}", "Super-res") { f, step ->
      MultiFrame.superRes(f, 0, step)
    }
  }

  /** Changes exposure by [stops]: by shutter when exposure is manual, by EV otherwise, and lets it settle. */
  private suspend fun exposeBy(s: CameraSettings, stops: Float) {
    if (!s.exposureAuto && caps.manualSensor) {
      val ns = (s.shutterNs * 2.0.pow(stops.toDouble())).toLong()
      settings = s.copy(shutterNs = caps.exposureRange?.let { ns.coerceIn(it.lower, it.upper) } ?: ns)
      applyRequest()
      delay(150)
    } else {
      val idx = if (caps.evStep > 0f) (s.evIndex + stops / caps.evStep).roundToInt().coerceIn(caps.evRange) else 0
      camera?.cameraControl?.setExposureCompensationIndex(idx)
      delay(450)
    }
  }

  private fun jpegOnly(capture: ImageCapture, mode: String): Boolean {
    if (capture.outputFormat == ImageCapture.OUTPUT_FORMAT_JPEG) return true
    notice = "$mode needs the JPEG format"
    return false
  }

  /** [count] frames as fast as the camera gives them, kept as JPEG, with the rotation of the last. */
  private suspend fun burst(label: String, count: Int): Pair<List<Frame>, Int>? {
    val capture = imageCapture ?: return null
    if (!jpegOnly(capture, label)) return null
    busy = true
    val frames = mutableListOf<Frame>()
    var rotation = 0
    for (i in 0 until count) {
      progress = "$label ${i + 1} / $count"
      val (bytes, rot) = captureJpeg(capture) ?: continue
      frames += Frame(bytes)
      rotation = rot
    }
    if (frames.size < 2) {
      notice = "$label needs at least 2 frames; the camera gave ${frames.size}"
      return null
    }
    return frames to rotation
  }

  /** Runs a merge on the worker, applies the look and LUT, saves with EXIF, and reports how long it took. */
  private suspend fun merged(
    s: CameraSettings,
    frames: List<Frame>,
    rotation: Int,
    ref: Int,
    description: String,
    label: String,
    merge: (List<Frame>, (String) -> Unit) -> Bitmap
  ) = suspendCancellableCoroutine { cont ->
    worker.execute {
      val started = SystemClock.elapsedRealtime()
      val uri = try {
        var stepStart = started
        val out = merge(frames) { step ->
          val now = SystemClock.elapsedRealtime()
          android.util.Log.i("TACTS", "$label: $step after ${now - stepStart} ms")
          stepStart = now
          mainExecutor.execute { progress = "$label · $step" }
        }
        android.util.Log.i("TACTS", "$label: merged in ${SystemClock.elapsedRealtime() - stepStart} ms")
        mainExecutor.execute { progress = "$label · saving" }
        val look = Look.entries.firstOrNull { it.name == s.look } ?: Look.NATURAL
        PhotoOutput.applyInPlace(out, look.matrix()?.array, luts.load(s.lutName))
        val mirror = s.lensFacing == CameraSelector.LENS_FACING_FRONT
        val u = PhotoOutput.save(context, out, MediaSaver.newName(), s.jpegQuality, frames[ref].jpeg, PhotoOutput.exifOrientation(rotation, mirror), description)
        out.recycle()
        u
      } catch (e: OutOfMemoryError) {
        android.util.Log.w("TACTS", "$label merge out of memory", e)
        mainExecutor.execute { notice = "$label failed: not enough memory. Use fewer frames." }
        null
      } catch (e: Exception) {
        android.util.Log.w("TACTS", "$label merge failed", e)
        mainExecutor.execute { notice = "$label failed: ${e.message}" }
        null
      }
      android.util.Log.i("TACTS", "$label: ${frames.size} frames merged and saved in ${SystemClock.elapsedRealtime() - started} ms")
      mainExecutor.execute {
        if (uri != null) {
          lastCapture = uri
          lastIsVideo = false
          loadThumb(uri)
        } else if (notice == null) notice = "Could not save the photo"
        if (cont.isActive) cont.resume(Unit)
      }
    }
  }

  /** One frame's JPEG and its rotation, straight from the camera, without decoding it. */
  private suspend fun captureJpeg(capture: ImageCapture): Pair<ByteArray, Int>? = suspendCancellableCoroutine { cont ->
    capture.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
      override fun onCaptureSuccess(image: ImageProxy) {
        val out = try {
          val buf = image.planes[0].buffer
          val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
          bytes to image.imageInfo.rotationDegrees
        } catch (e: Exception) {
          null
        } finally {
          image.close()
        }
        if (cont.isActive) cont.resume(out)
      }

      override fun onError(exception: ImageCaptureException) {
        mainExecutor.execute { notice = "Capture failed: ${exception.message}" }
        if (cont.isActive) cont.resume(null)
      }
    })
  }

  /** One photo in the current format, with the look and LUT applied to JPEGs. Suspends until saved. */
  private suspend fun capture() {
    val capture = imageCapture ?: return
    busy = true
    val s = settings
    val name = MediaSaver.newName()
    val wantsProcessing = s.look != Look.NATURAL.name || s.lutName != null
    val started = SystemClock.elapsedRealtime()
    try {
      captureOnce(capture, s, name, wantsProcessing)
    } finally {
      // Engineering readout: shutter press to file saved, for the log.
      android.util.Log.i("TACTS", "photo ${s.photoFormat} ${if (wantsProcessing) "processed " else ""}saved in ${SystemClock.elapsedRealtime() - started} ms")
    }
  }

  private suspend fun captureOnce(capture: ImageCapture, s: CameraSettings, name: String, wantsProcessing: Boolean) {
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
      update { it.copy(clipCounter = it.clipCounter + 1) }
    } else {
      raw.start(settings.clipName() + "_RAW")
    }
  }

  // ---- lenses ----

  fun switchLens() {
    if (!hasFrontAndBack || isRecording) return
    pendingZoom = 1f
    update {
      // Manual values belong to one sensor; the other one starts in auto.
      it.copy(
        lensFacing = if (it.lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK,
        physicalCameraId = null,
        cameraId = null,
        exposureAuto = true,
        focusAuto = true,
        focusPullA = null,
        focusPullB = null
      )
    }
  }
}
