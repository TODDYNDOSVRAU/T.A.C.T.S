package com.tacts.camera.raw

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import com.tacts.camera.camera.CameraCaps
import com.tacts.camera.camera.CameraSettings
import com.tacts.camera.camera.RequestOptions
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * RAW video in the MotionCam way: the sensor's own data, frame after frame, each frame a DNG file in
 * a folder per clip (a CinemaDNG-style sequence that Resolve and other graders open as a clip).
 *
 * CameraX has no RAW stream for video, so this engine runs the camera on its own with camera2: one
 * session with the preview surface and a RAW_SENSOR ImageReader. Frames are written on a worker
 * thread; when the writer falls behind, new frames are dropped and counted rather than stalling the
 * camera. How many frames per second reach the card depends on the phone's storage.
 */
class RawVideoEngine(private val context: Context) {

  data class Stats(val recording: Boolean = false, val written: Int = 0, val dropped: Int = 0, val folder: String? = null)

  var onStats: (Stats) -> Unit = {}
  var onError: (String) -> Unit = {}

  private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
  private val thread = HandlerThread("tacts-raw").apply { start() }
  private val handler = Handler(thread.looper)
  private val writer = Executors.newSingleThreadExecutor()

  private var device: CameraDevice? = null
  private var session: CameraCaptureSession? = null
  private var reader: ImageReader? = null
  private var previewSurface: Surface? = null
  private var characteristics: CameraCharacteristics? = null
  private var request: CaptureRequest.Builder? = null

  private val results = ConcurrentHashMap<Long, TotalCaptureResult>()
  private val pending = AtomicInteger(0)
  @Volatile private var recording = false
  @Volatile private var clip: String? = null
  private val written = AtomicInteger(0)
  private val frame = AtomicInteger(0)
  /** RAW images that arrived before their capture result, by timestamp. Touched on the camera thread only. */
  private val waiting = HashMap<Long, Image>()
  private val dropped = AtomicInteger(0)
  private var fallbackDir: File? = null

  /** Opens [cameraId] and shows its preview on [texture]. */
  @SuppressLint("MissingPermission")
  fun open(cameraId: String, texture: SurfaceTexture, previewSize: Size, settings: CameraSettings, caps: CameraCaps) {
    close()
    val chars = manager.getCameraCharacteristics(cameraId)
    characteristics = chars
    val rawSize = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
      ?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
    if (rawSize == null) {
      onError("This camera has no RAW stream")
      return
    }
    texture.setDefaultBufferSize(previewSize.width, previewSize.height)
    val preview = Surface(texture)
    previewSurface = preview
    val raw = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, MAX_IN_FLIGHT + 2)
    reader = raw
    raw.setOnImageAvailableListener({ onRaw(it) }, handler)

    manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
      override fun onOpened(camera: CameraDevice) {
        device = camera
        @Suppress("DEPRECATION")
        camera.createCaptureSession(listOf(preview, raw.surface), object : CameraCaptureSession.StateCallback() {
          override fun onConfigured(s: CameraCaptureSession) {
            session = s
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
              addTarget(preview)
              addTarget(raw.surface)
            }
            request = builder
            apply(settings, caps)
          }

          override fun onConfigureFailed(s: CameraCaptureSession) = onError("The camera could not stream RAW at this size")
        }, handler)
      }

      override fun onDisconnected(camera: CameraDevice) = close()
      override fun onError(camera: CameraDevice, error: Int) {
        onError("Camera error $error")
        close()
      }
    }, handler)
  }

  /** Applies the manual settings to the running stream. */
  fun apply(settings: CameraSettings, caps: CameraCaps) {
    val builder = request ?: return
    val s = session ?: return
    val fps = settings.frameRate
    builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
    val options = RequestOptions.build(settings, caps, com.tacts.camera.camera.LiveReadout(), fps)
    // The same keys CameraX gets through interop, copied onto this request.
    options.listOptions().forEach { opt ->
      @Suppress("UNCHECKED_CAST")
      val key = (opt.token as CaptureRequest.Key<Any>)
      options.retrieveOption(opt)?.let { builder.set(key, it) }
    }
    if (settings.focusAuto) builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
    // DngCreator writes BaselineExposure from this key, and refuses a result that lacks it.
    builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100)
    try {
      s.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
          val ts = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: return
          // The RAW image often arrives before its metadata: hand it over now if it is waiting.
          val image = waiting.remove(ts)
          if (image != null) enqueue(image, result) else {
            results[ts] = result
            if (results.size > 30) results.keys.sorted().take(results.size - 30).forEach { results.remove(it) }
          }
        }
      }, handler)
    } catch (e: Exception) {
      onError(e.message ?: "Could not start the stream")
    }
  }

  fun start(clipName: String) {
    clip = clipName
    written.set(0)
    frame.set(0)
    dropped.set(0)
    fallbackDir = null
    recording = true
    report()
  }

  fun stop() {
    recording = false
    handler.post {
      waiting.values.forEach { it.close() }
      waiting.clear()
    }
    report()
  }

  private fun onRaw(r: ImageReader) {
    val image = r.acquireNextImage() ?: return
    if (!recording) {
      image.close()
      return
    }
    if (pending.get() + waiting.size >= MAX_IN_FLIGHT) {
      image.close()
      dropped.incrementAndGet()
      report()
      return
    }
    val result = results.remove(image.timestamp)
    if (result == null) {
      waiting[image.timestamp] = image
      // A frame whose metadata never comes is dropped once newer frames have moved on.
      if (waiting.size > 2) waiting.keys.sorted().dropLast(2).forEach { ts ->
        waiting.remove(ts)?.close()
        dropped.incrementAndGet()
      }
      return
    }
    enqueue(image, result)
  }

  private fun enqueue(image: Image, result: TotalCaptureResult) {
    val chars = characteristics
    if (!recording || chars == null) {
      image.close()
      return
    }
    pending.incrementAndGet()
    val index = frame.incrementAndGet()
    val name = clip ?: "RAW"
    writer.execute { write(image, result, chars, name, index) }
  }

  private fun write(image: Image, result: TotalCaptureResult, chars: CameraCharacteristics, clipName: String, index: Int) {
    val output = openOutput(clipName, "%s_%06d.dng".format(clipName, index))
    try {
      output?.stream?.use { out ->
        DngCreator(chars, result).use { dng ->
          // The sensor is mounted sideways; the tag tells editors how to stand the frame up (portrait).
          dng.setOrientation(orientationTag(chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0))
          dng.writeImage(out, image)
        }
      }
      if (output != null) written.incrementAndGet() else dropped.incrementAndGet()
    } catch (e: Exception) {
      android.util.Log.w("TactsRaw", "DNG write failed", e)
      output?.discard?.invoke()
      dropped.incrementAndGet()
    } finally {
      image.close()
      pending.decrementAndGet()
      report()
    }
  }

  private class Output(val stream: OutputStream, val discard: () -> Unit)

  /** DCIM/TACTS/<clip>/<frame>.dng: one folder per clip, visible to file managers and graders. */
  private fun openOutput(clipName: String, file: String): Output? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/TACTS/$clipName")
      }
      val resolver = context.contentResolver
      val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
      val stream = resolver.openOutputStream(uri) ?: return null
      return Output(stream) { resolver.delete(uri, null, null) }
    }
    @Suppress("DEPRECATION")
    val dir = fallbackDir ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "TACTS/$clipName")
      .apply { mkdirs() }.also { fallbackDir = it }
    val target = File(dir, file)
    return Output(FileOutputStream(target)) { target.delete() }
  }

  private fun report() = onStats(Stats(recording, written.get(), dropped.get(), clip))

  fun close() {
    recording = false
    try {
      session?.close()
    } catch (_: Exception) {
    }
    device?.close()
    reader?.close()
    previewSurface?.release()
    session = null
    device = null
    reader = null
    previewSurface = null
    request = null
    results.clear()
  }

  fun release() {
    close()
    writer.shutdown()
    thread.quitSafely()
  }

  companion object {
    /** Frames waiting to be written before new ones are dropped. */
    private const val MAX_IN_FLIGHT = 4
  }
}

private fun orientationTag(degrees: Int) = when (degrees) {
  90 -> android.media.ExifInterface.ORIENTATION_ROTATE_90
  180 -> android.media.ExifInterface.ORIENTATION_ROTATE_180
  270 -> android.media.ExifInterface.ORIENTATION_ROTATE_270
  else -> android.media.ExifInterface.ORIENTATION_NORMAL
}
