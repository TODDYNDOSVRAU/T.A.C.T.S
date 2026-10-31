package com.tacts.camera.camera

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Every choice the user makes, in one serialisable object. The camera reads it to bind and to build
 * its capture requests; the settings sheet edits it; SettingsStore keeps it between launches. Options
 * the open camera does not support stay in here untouched and are simply not applied.
 */

enum class CaptureMode(val label: String) { PHOTO("Photo"), VIDEO("Video"), SLOW_MO("Slow-mo"), RAW_VIDEO("RAW video") }

enum class Flash { OFF, AUTO, ON }

// ---- photo ----

enum class PhotoFormat(val label: String, val shortLabel: String) {
  JPEG("JPEG", "JPEG"), ULTRA_HDR("Ultra HDR", "UHDR"), RAW("RAW (DNG)", "RAW"), RAW_JPEG("RAW + JPEG", "RAW+J")
}

enum class PhotoMode(val label: String) {
  SINGLE("Single"),
  BURST("Burst"),
  INTERVAL("Interval"),
  BRACKET("AE bracket"),
  FOCUS_BRACKET("Focus bracket"),
  NIGHT("Night")
}

// ---- video ----

enum class VideoQuality(val label: String) { SD("SD"), HD("HD"), FHD("FHD"), UHD("4K") }

enum class Bitrate(val label: String, val mbps: Int) {
  AUTO("Auto", 0),
  STANDARD("Standard · 20 Mb/s", 20),
  HIGH("High · 50 Mb/s", 50),
  EXTREME("Extreme · 100 Mb/s", 100),
  MAX("Max · 200 Mb/s", 200)
}

enum class DynamicRangeChoice(val label: String) {
  SDR("SDR 8-bit"),
  HLG("HLG 10-bit"),
  HDR10("HDR10 10-bit"),
  HDR10_PLUS("HDR10+ 10-bit"),
  DOLBY_VISION("Dolby Vision 10-bit")
}

enum class Stabilization(val label: String) { OFF("Off"), VIDEO("Video (EIS)"), PREVIEW("Preview + video") }

enum class AudioSource(val label: String) { CAMCORDER("Camcorder"), UNPROCESSED("Unprocessed"), VOICE("Voice"), MIC("Microphone") }

enum class ShutterMode(val label: String) { SPEED("Speed"), ANGLE("Angle") }

val SHUTTER_ANGLES = listOf(11.25f, 22.5f, 45f, 90f, 172.8f, 180f, 270f, 360f)

val FRAME_RATES = listOf(24, 25, 30, 48, 50, 60)

// ---- colour and image ----

enum class WhiteBalanceMode(val label: String) { AUTO("Auto"), PRESET("Preset"), KELVIN("Kelvin") }

enum class WhiteBalance(val label: String, val awbMode: Int, val kelvin: Int) {
  DAYLIGHT("Daylight", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, 5600),
  CLOUDY("Cloudy", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, 6500),
  SHADE("Shade", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_SHADE, 7500),
  TUNGSTEN("Tungsten", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, 3200),
  FLUORESCENT("Fluorescent", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, 4000),
  WARM_FLUORESCENT("Warm fluorescent", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT, 3000),
  TWILIGHT("Twilight", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_TWILIGHT, 9000)
}

enum class GammaProfile(val label: String, val description: String) {
  STANDARD("Standard", "The phone's own picture"),
  FLAT("Flat", "Lower contrast, more room in shadows and highlights"),
  LOG("Log", "Cineon-style log curve for grading"),
  LOG_HC("Log high contrast", "Log with a gentler toe, easier to expose"),
  HLG_LIKE("HLG-like", "Rec.709 below mid grey, log above, like broadcast HLG"),
  LINEAR("Linear", "No curve: the sensor's light, for technical work")
}

enum class ProcessingLevel(val label: String) { DEFAULT("Default"), OFF("Off"), FAST("Fast"), HIGH_QUALITY("High quality"), MINIMAL("Minimal") }

enum class Metering(val label: String) { MATRIX("Matrix"), CENTER("Centre"), SPOT("Spot") }

enum class Antibanding(val label: String) { AUTO("Auto"), HZ50("50 Hz"), HZ60("60 Hz"), OFF("Off") }

// ---- monitoring ----

enum class Scope(val label: String) {
  NONE("Off"),
  LUMA("Luma histogram"),
  RGB("RGB histogram"),
  WAVEFORM("Waveform"),
  PARADE("RGB parade"),
  VECTORSCOPE("Vectorscope")
}

enum class PeakingColor(val label: String, val argb: Long) {
  RED("Red", 0xFFFF3B30),
  GREEN("Green", 0xFF34C759),
  BLUE("Blue", 0xFF0A84FF),
  YELLOW("Yellow", 0xFFFFD60A),
  WHITE("White", 0xFFFFFFFF)
}

enum class FrameGuide(val label: String, val ratio: Float) {
  NONE("Off", 0f),
  SCOPE_239("2.39:1", 2.39f),
  SCOPE_235("2.35:1", 2.35f),
  FLAT_185("1.85:1", 1.85f),
  HD_169("16:9", 16f / 9f),
  ACADEMY_43("4:3", 4f / 3f),
  SQUARE("1:1", 1f),
  VERTICAL_916("9:16", 9f / 16f),
  SOCIAL_45("4:5", 4f / 5f)
}

/** How controls over the viewfinder are drawn, as in T.A.R.K.A.S. */
enum class GlassStyle(val label: String, val description: String) {
  SOLID("Solid", "The app's colour, no blur. Lightest on the battery."),
  FROSTED("Frosted glass", "Blurs the picture behind the controls."),
  LIQUID("Liquid glass", "Glass that bends the picture behind it, with a lit edge (Android 13 or newer). Uses more of the GPU.")
}

enum class GridStyle(val label: String) { OFF("Off"), THIRDS("Thirds"), GOLDEN("Golden ratio"), CROSSHAIR("Crosshair"), SQUARES("Squares") }

enum class Desqueeze(val label: String, val factor: Float) {
  OFF("Off", 1f),
  X133("1.33×", 1.33f),
  X15("1.5×", 1.5f),
  X155("1.55×", 1.55f),
  X18("1.8×", 1.8f),
  X2("2×", 2f)
}

enum class SafeArea(val label: String, val fraction: Float) { OFF("Off", 0f), P90("90 %", 0.9f), P80("80 %", 0.8f) }

// ---- the whole set ----

@Serializable
data class CameraSettings(
  val mode: CaptureMode = CaptureMode.PHOTO,
  val lensFacing: Int = androidx.camera.core.CameraSelector.LENS_FACING_BACK,
  /** A physical lens of a logical multi-camera, or null for the logical camera. */
  val physicalCameraId: String? = null,
  /** A lens the phone lists as a camera of its own, outside the default camera's zoom range; null for the default camera. */
  val cameraId: String? = null,
  val glass: GlassStyle = GlassStyle.LIQUID,
  val flash: Flash = Flash.OFF,
  val torchStrength: Int = 0,
  val lowLightBoost: Boolean = false,

  // Exposure
  val evIndex: Int = 0,
  val exposureAuto: Boolean = true,
  val iso: Int = 100,
  val shutterNs: Long = 16_666_667L,
  val shutterMode: ShutterMode = ShutterMode.SPEED,
  val shutterAngle: Float = 180f,
  val aeLock: Boolean = false,
  val metering: Metering = Metering.MATRIX,
  val antibanding: Antibanding = Antibanding.AUTO,
  val aperture: Float = 0f,
  val ndFilter: Float = 0f,

  // Focus
  val focusAuto: Boolean = true,
  val focusDiopters: Float = 0f,
  val focusPullA: Float? = null,
  val focusPullB: Float? = null,
  val focusPullMs: Int = 2000,
  val faceDetection: Boolean = false,

  // Colour and image
  val wbMode: WhiteBalanceMode = WhiteBalanceMode.AUTO,
  val whiteBalance: WhiteBalance = WhiteBalance.DAYLIGHT,
  val kelvin: Int = 5600,
  val tint: Int = 0,
  val awbLock: Boolean = false,
  val gamma: GammaProfile = GammaProfile.STANDARD,
  val noiseReduction: ProcessingLevel = ProcessingLevel.DEFAULT,
  val sharpening: ProcessingLevel = ProcessingLevel.DEFAULT,
  val hotPixel: ProcessingLevel = ProcessingLevel.DEFAULT,
  val lensShading: ProcessingLevel = ProcessingLevel.DEFAULT,
  val ois: Boolean = true,
  val look: String = "NATURAL",
  val lutName: String? = null,
  val lutOnVideo: Boolean = true,

  // Photo
  val photoFormat: PhotoFormat = PhotoFormat.JPEG,
  val photoMode: PhotoMode = PhotoMode.SINGLE,
  val timerSeconds: Int = 0,
  val burstCount: Int = 10,
  val intervalSeconds: Int = 5,
  val intervalCount: Int = 60,
  val bracketStops: Float = 1f,
  val bracketShots: Int = 3,
  val focusBracketShots: Int = 5,
  val nightFrames: Int = 8,
  val jpegQuality: Int = 95,
  /** Save the frame at the press from a buffer, where the camera supports it; off waits for a fresh, fully processed frame. */
  val zeroShutterLag: Boolean = true,

  // Video
  val videoQuality: VideoQuality = VideoQuality.FHD,
  val frameRate: Int = 30,
  val bitrate: Bitrate = Bitrate.AUTO,
  val dynamicRange: DynamicRangeChoice = DynamicRangeChoice.SDR,
  val stabilization: Stabilization = Stabilization.OFF,
  val slowMoFps: Int = 120,
  val audioEnabled: Boolean = true,
  val audioSource: AudioSource = AudioSource.CAMCORDER,
  val reel: Int = 1,
  val clipCounter: Int = 1,

  // Monitoring
  val peaking: Boolean = false,
  val peakingColor: PeakingColor = PeakingColor.RED,
  val peakingThreshold: Int = 60,
  val zebras: Boolean = false,
  val zebraLevel: Int = 95,
  val falseColor: Boolean = false,
  val scope: Scope = Scope.NONE,
  val grid: GridStyle = GridStyle.THIRDS,
  val frameGuide: FrameGuide = FrameGuide.NONE,
  val safeArea: SafeArea = SafeArea.OFF,
  val desqueeze: Desqueeze = Desqueeze.OFF,
  val level: Boolean = false,
  val centerMark: Boolean = false,
  val timecode: Boolean = true
) {
  /** Nothing about exposure, focus or colour has been set by hand. */
  val isAllAuto: Boolean
    get() = exposureAuto && focusAuto && wbMode == WhiteBalanceMode.AUTO && evIndex == 0 && !aeLock && !awbLock

  /** The exposure time the shutter angle gives at the current frame rate. */
  fun angleExposureNs(fps: Int = frameRate): Long = (shutterAngle / 360f * 1_000_000_000f / fps).toLong()

  /** The next clip's name, Blackmagic style: A001_C001_YYMMDD. */
  fun clipName(date: java.util.Date = java.util.Date()): String =
    "A%03d_C%03d_%s".format(reel, clipCounter, java.text.SimpleDateFormat("yyMMdd", java.util.Locale.US).format(date))
}

private val Context.dataStore by preferencesDataStore("tacts_settings")
private val SETTINGS_KEY = stringPreferencesKey("settings")
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

/** Keeps [CameraSettings] between launches. Unknown or missing fields fall back to their defaults. */
class SettingsStore(private val context: Context) {
  suspend fun load(): CameraSettings = try {
    context.dataStore.data.first()[SETTINGS_KEY]?.let { json.decodeFromString(CameraSettings.serializer(), it) } ?: CameraSettings()
  } catch (e: Exception) {
    CameraSettings()
  }

  suspend fun save(settings: CameraSettings) {
    context.dataStore.edit { it[SETTINGS_KEY] = json.encodeToString(CameraSettings.serializer(), settings) }
  }
}
