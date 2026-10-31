package com.tacts.camera.ui.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tacts.camera.camera.Antibanding
import com.tacts.camera.camera.AudioSource
import com.tacts.camera.camera.Bitrate
import com.tacts.camera.camera.CameraCaps
import com.tacts.camera.camera.CameraSettings
import com.tacts.camera.camera.Desqueeze
import com.tacts.camera.camera.DynamicRangeChoice
import com.tacts.camera.camera.FrameGuide
import com.tacts.camera.camera.GammaProfile
import com.tacts.camera.camera.GridStyle
import com.tacts.camera.camera.Metering
import com.tacts.camera.camera.PeakingColor
import com.tacts.camera.camera.PhotoFormat
import com.tacts.camera.camera.ProcessingLevel
import com.tacts.camera.camera.RequestOptions
import com.tacts.camera.camera.SHUTTER_ANGLES
import com.tacts.camera.camera.SafeArea
import com.tacts.camera.camera.Scope
import com.tacts.camera.camera.ShutterMode
import com.tacts.camera.camera.Stabilization
import com.tacts.camera.camera.TactsCamera
import com.tacts.camera.camera.VideoQuality
import com.tacts.camera.camera.angleLabel
import com.tacts.camera.ui.components.GroupDivider
import com.tacts.camera.ui.components.GroupFooter
import com.tacts.camera.ui.components.GroupHeader
import com.tacts.camera.ui.components.InfoRow
import com.tacts.camera.ui.components.InsetGroup
import com.tacts.camera.ui.components.ListRow
import com.tacts.camera.ui.components.RadioRow
import com.tacts.camera.ui.components.SliderRow
import com.tacts.camera.ui.components.StepperRow
import com.tacts.camera.ui.components.SubPageHeader
import com.tacts.camera.ui.components.SwitchRow
import com.tacts.camera.ui.components.TextAction
import com.tacts.camera.ui.info.CameraInfoPage
import com.tacts.camera.ui.theme.Space
import com.tacts.camera.ui.theme.entrance
import kotlin.math.roundToInt

/** One choice page: a list of options with a check mark on the chosen one. */
private class Choice(
  val title: String,
  val options: List<Pair<String, String?>>,
  val selected: Int,
  val footer: String? = null,
  val onPick: (Int) -> Unit
)

private sealed interface Page {
  data object Root : Page
  class Pick(val choice: Choice) : Page
  data object Luts : Page
  data object Lenses : Page
  data object Info : Page
  data object Presets : Page
}

/**
 * Every option of the camera, in groups, in the T.A.R.K.A.S. grouped-list style. A choice opens its
 * own page with the options; options this camera does not have are not listed, or say why.
 */
@Composable
fun SettingsScreen(camera: TactsCamera, onClose: () -> Unit) {
  var page by remember { mutableStateOf<Page>(Page.Root) }
  // Held here, not in the list, so coming back from a page lands on the same row.
  val rootScroll = rememberScrollState()
  var rootShown by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { rootShown = true }
  BackHandler { if (page == Page.Root) onClose() else page = Page.Root }

  androidx.compose.foundation.layout.Box(
    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
  ) {
    when (val p = page) {
      Page.Root -> Root(camera, rootScroll, !rootShown, onClose, open = { page = it })
      is Page.Pick -> ChoicePage(p.choice) { page = Page.Root }
      Page.Luts -> LutPage(camera) { page = Page.Root }
      Page.Lenses -> LensPage(camera) { page = Page.Root }
      Page.Info -> CameraInfoPage(onBack = { page = Page.Root })
      Page.Presets -> PresetPage(camera) { page = Page.Root }
    }
  }
}

@Composable
private fun ChoicePage(choice: Choice, onBack: () -> Unit) {
  var selected by remember { mutableIntStateOf(choice.selected) }
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    SubPageHeader(choice.title, "Settings", onBack)
    InsetGroup(Modifier.entrance()) {
      choice.options.forEachIndexed { i, (label, sub) ->
        if (i > 0) GroupDivider()
        RadioRow(label, selected = i == selected, subtitle = sub, onClick = {
          selected = i
          choice.onPick(i)
        })
      }
    }
    choice.footer?.let { GroupFooter(it) }
    Spacer(Modifier.height(Space.section))
  }
}

@Composable
private fun Root(camera: TactsCamera, scroll: ScrollState, animate: Boolean, onClose: () -> Unit, open: (Page) -> Unit) {
  val s = camera.settings
  val caps = camera.caps
  fun set(t: (CameraSettings) -> CameraSettings) = camera.update(t)

  /** A row showing the current value, opening a page of choices. */
  @Composable
  fun <T> Pick(
    title: String,
    current: T,
    options: List<T>,
    label: (T) -> String,
    sub: (T) -> String? = { null },
    footer: String? = null,
    enabled: Boolean = true,
    onPick: (T) -> Unit
  ) {
    ListRow(title, value = label(current), enabled = enabled && options.size > 1, onClick = {
      open(Page.Pick(Choice(title, options.map { label(it) to sub(it) }, options.indexOf(current).coerceAtLeast(0), footer) { onPick(options[it]) }))
    })
  }

  var index = 0
  fun next() = index++

  Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
    SubPageHeader("Settings", "Camera", onClose)

    // ---- Photo ----
    GroupHeader("PHOTO")
    InsetGroup(Modifier.entrance(next(), animate)) {
      Pick("Format", s.photoFormat, PhotoFormat.entries.filter { it in caps.photoFormats }, { it.label }) { v -> set { it.copy(photoFormat = v) } }
      GroupDivider()
      SliderRow("JPEG quality", s.jpegQuality.toFloat(), { v -> set { it.copy(jpegQuality = v.roundToInt()) } }, 70f..100f, "${s.jpegQuality}")
      GroupDivider()
      SwitchRow("Zero shutter lag", s.zeroShutterLag, { v -> set { it.copy(zeroShutterLag = v) } }, subtitle = "Saves the frame at the press. Off waits for a fresh frame at full processing; flash and RAW always wait. On a Galaxy S23 the preview freezes when the zoom changes lens, so it is off by default.")
      GroupDivider()
      Pick("Self-timer", s.timerSeconds, listOf(0, 2, 3, 5, 10), { if (it == 0) "Off" else "$it s" }) { v -> set { it.copy(timerSeconds = v) } }
      GroupDivider()
      StepperRow("Burst shots", s.burstCount, { v -> set { it.copy(burstCount = v) } }, 2..50)
      GroupDivider()
      StepperRow("Interval", s.intervalSeconds, { v -> set { it.copy(intervalSeconds = v) } }, 1..3600, format = { "$it s" })
      GroupDivider()
      StepperRow("Interval shots", s.intervalCount, { v -> set { it.copy(intervalCount = v) } }, 2..9999, step = 10)
      GroupDivider()
      Pick("Bracket step", s.bracketStops, listOf(0.3f, 0.7f, 1f, 1.5f, 2f, 3f), { "±%.1f EV".format(it) }) { v -> set { it.copy(bracketStops = v) } }
      GroupDivider()
      Pick("Bracket shots", s.bracketShots, listOf(3, 5, 7, 9), { "$it" }) { v -> set { it.copy(bracketShots = v) } }
      GroupDivider()
      StepperRow("Focus bracket shots", s.focusBracketShots, { v -> set { it.copy(focusBracketShots = v) } }, 2..60)
      GroupDivider()
      StepperRow("Night frames", s.nightFrames, { v -> set { it.copy(nightFrames = v) } }, 2..32, subtitle = "Aligned and averaged: more frames, less noise")
      GroupDivider()
      Pick("HDR range", s.hdrStops, listOf(1f, 1.5f, 2f, 2.5f, 3f), { "±%.1f EV".format(it) }) { v -> set { it.copy(hdrStops = v) } }
      GroupDivider()
      StepperRow("Super-res frames", s.superResFrames, { v -> set { it.copy(superResFrames = v) } }, 4..16, subtitle = "Hand-held works best: the small shake is what adds detail")
    }
    GroupFooter("RAW is the sensor's data in a DNG file. Ultra HDR keeps an HDR gain map inside the JPEG for HDR screens.")
    GroupFooter(
      "Night, HDR and Super-res take several JPEG frames and align them before merging; what moves between frames comes from one frame only. " +
        "Super-res doubles width and height (4× the pixels). It adds some real detail, much less than a sensor with that many pixels would."
    )

    // ---- Presets ----
    GroupHeader("PRESETS")
    InsetGroup(Modifier.entrance(next(), animate)) {
      ListRow("Presets", value = if (s.presets.isEmpty()) "None" else "${s.presets.size}", icon = Icons.Rounded.Tune, onClick = { open(Page.Presets) })
    }

    // ---- Video ----
    GroupHeader("VIDEO")
    InsetGroup(Modifier.entrance(next(), animate)) {
      Pick("Resolution", s.videoQuality, caps.videoQualities.ifEmpty { VideoQuality.entries }, { it.label }) { v -> set { it.copy(videoQuality = v) } }
      GroupDivider()
      Pick("Frame rate", s.frameRate, caps.frameRates, { "$it fps" }) { v -> set { it.copy(frameRate = v) } }
      GroupDivider()
      Pick("Bitrate", s.bitrate, Bitrate.entries, { it.label }, footer = "The encoder caps the bitrate at what it supports.") { v -> set { it.copy(bitrate = v) } }
      GroupDivider()
      Pick(
        "Dynamic range",
        s.dynamicRange,
        DynamicRangeChoice.entries.filter { it in caps.dynamicRanges },
        { it.label },
        footer = "10-bit HDR records HEVC. Looks and LUTs are not burnt into HDR video."
      ) { v -> set { it.copy(dynamicRange = v) } }
      GroupDivider()
      Pick(
        "Stabilization",
        s.stabilization,
        Stabilization.entries.filter {
          it == Stabilization.OFF || (it == Stabilization.VIDEO && caps.videoStabilization) || (it == Stabilization.PREVIEW && caps.previewStabilization)
        },
        { it.label }
      ) { v -> set { it.copy(stabilization = v) } }
      if (caps.hasOis) {
        GroupDivider()
        SwitchRow("Optical stabilization (OIS)", s.ois, { v -> set { it.copy(ois = v) } })
      }
      GroupDivider()
      Pick("Shutter", s.shutterMode, ShutterMode.entries, { it.label }, footer = "Angle keeps motion blur the same at any frame rate: 180° is the film look.") { v ->
        set { it.copy(shutterMode = v) }
      }
      if (s.shutterMode == ShutterMode.ANGLE) {
        GroupDivider()
        Pick("Shutter angle", s.shutterAngle, SHUTTER_ANGLES, { angleLabel(it) }) { v -> set { it.copy(shutterAngle = v) } }
      }
      if (caps.highSpeedFps.isNotEmpty()) {
        GroupDivider()
        Pick("Slow motion", s.slowMoFps, caps.highSpeedFps, { "$it fps" }) { v -> set { it.copy(slowMoFps = v) } }
      }
      GroupDivider()
      StepperRow("Reel", s.reel, { v -> set { it.copy(reel = v) } }, 1..999, format = { "A%03d".format(it) })
      GroupDivider()
      StepperRow("Next clip", s.clipCounter, { v -> set { it.copy(clipCounter = v) } }, 1..999, format = { "C%03d".format(it) }, subtitle = s.clipName())
    }

    // ---- Audio ----
    GroupHeader("AUDIO")
    InsetGroup(Modifier.entrance(next(), animate)) {
      SwitchRow("Record audio", s.audioEnabled, { v -> set { it.copy(audioEnabled = v) } }, icon = Icons.Rounded.Mic)
      GroupDivider()
      Pick("Source", s.audioSource, AudioSource.entries, { it.label }, footer = "Unprocessed skips the phone's noise suppression and gain, for a cleaner signal to mix.") { v ->
        set { it.copy(audioSource = v) }
      }
    }

    // ---- Exposure ----
    GroupHeader("EXPOSURE")
    InsetGroup(Modifier.entrance(next(), animate)) {
      Pick("Metering", s.metering, Metering.entries, { it.label }) { v -> set { it.copy(metering = v) } }
      GroupDivider()
      Pick("Antibanding", s.antibanding, Antibanding.entries, { it.label }, footer = "Match the mains frequency to stop flicker under artificial light.") { v ->
        set { it.copy(antibanding = v) }
      }
      if (caps.apertures.size > 1) {
        GroupDivider()
        Pick("Aperture", s.aperture, listOf(0f) + caps.apertures, { if (it == 0f) "Auto" else "f/%.1f".format(it) }) { v -> set { it.copy(aperture = v) } }
      }
      if (caps.ndFilters.size > 1) {
        GroupDivider()
        Pick("ND filter", s.ndFilter, caps.ndFilters, { if (it == 0f) "Off" else "%.1f EV".format(it) }) { v -> set { it.copy(ndFilter = v) } }
      }
      if (caps.lowLightBoost) {
        GroupDivider()
        SwitchRow("Low-light boost", s.lowLightBoost, { v -> set { it.copy(lowLightBoost = v) } })
      }
      if (caps.maxTorchStrength > 1) {
        GroupDivider()
        SliderRow("Torch strength", s.torchStrength.toFloat(), { v -> set { it.copy(torchStrength = v.roundToInt()) } }, 0f..caps.maxTorchStrength.toFloat(), if (s.torchStrength == 0) "Default" else "${s.torchStrength}")
      }
      if (caps.faceDetectModes.size > 1) {
        GroupDivider()
        SwitchRow("Face detection", s.faceDetection, { v -> set { it.copy(faceDetection = v) } })
      }
    }

    // ---- Picture ----
    GroupHeader("PICTURE")
    InsetGroup(Modifier.entrance(next(), animate)) {
      Pick(
        "Gamma",
        s.gamma,
        GammaProfile.entries,
        { it.label },
        sub = { it.description },
        enabled = caps.hasTonemapCurve,
        footer = "Log keeps more highlight and shadow detail for grading. Pair it with the \"Log to Rec.709\" LUT to monitor."
      ) { v -> set { it.copy(gamma = v) } }
      GroupDivider()
      Pick("Noise reduction", s.noiseReduction, RequestOptions.levelsFor("nr", caps), { it.label }) { v -> set { it.copy(noiseReduction = v) } }
      GroupDivider()
      Pick("Sharpening", s.sharpening, RequestOptions.levelsFor("edge", caps), { it.label }) { v -> set { it.copy(sharpening = v) } }
      GroupDivider()
      Pick("Hot pixel correction", s.hotPixel, RequestOptions.levelsFor("hot", caps), { it.label }) { v -> set { it.copy(hotPixel = v) } }
      GroupDivider()
      Pick("Lens shading correction", s.lensShading, RequestOptions.levelsFor("shading", caps), { it.label }) { v -> set { it.copy(lensShading = v) } }
      GroupDivider()
      ListRow("LUTs", value = s.lutName ?: "None", icon = Icons.Rounded.Palette, onClick = { open(Page.Luts) })
    }
    if (!caps.hasTonemapCurve) GroupFooter("This camera does not accept tonemap curves, so gamma profiles are not available.")
    if (s.noiseReduction == ProcessingLevel.OFF || s.sharpening == ProcessingLevel.OFF) {
      GroupFooter("Noise reduction and sharpening off give the most natural texture and the most room in post.")
    }

    // ---- Monitoring ----
    GroupHeader("MONITORING")
    InsetGroup(Modifier.entrance(next(), animate)) {
      Pick("Focus peaking colour", s.peakingColor, PeakingColor.entries, { it.label }) { v -> set { it.copy(peakingColor = v) } }
      GroupDivider()
      SliderRow("Peaking sensitivity", (120 - s.peakingThreshold).toFloat(), { v -> set { it.copy(peakingThreshold = 120 - v.roundToInt()) } }, 20f..100f, "${120 - s.peakingThreshold}")
      GroupDivider()
      SliderRow("Zebra level", s.zebraLevel.toFloat(), { v -> set { it.copy(zebraLevel = v.roundToInt()) } }, 70f..100f, "${s.zebraLevel} %", steps = 29)
      GroupDivider()
      Pick("Scope", s.scope, Scope.entries, { it.label }) { v -> set { it.copy(scope = v) } }
      GroupDivider()
      Pick("Grid", s.grid, GridStyle.entries, { it.label }) { v -> set { it.copy(grid = v) } }
      GroupDivider()
      Pick("Frame guide", s.frameGuide, FrameGuide.entries, { it.label }) { v -> set { it.copy(frameGuide = v) } }
      GroupDivider()
      Pick("Safe area", s.safeArea, SafeArea.entries, { it.label }) { v -> set { it.copy(safeArea = v) } }
      GroupDivider()
      Pick("Anamorphic desqueeze", s.desqueeze, Desqueeze.entries, { it.label }, footer = "Shows anamorphic footage unsqueezed on screen; the recording stays squeezed.") { v ->
        set { it.copy(desqueeze = v) }
      }
      GroupDivider()
      SwitchRow("Horizon level", s.level, { v -> set { it.copy(level = v) } })
      GroupDivider()
      SwitchRow("Centre mark", s.centerMark, { v -> set { it.copy(centerMark = v) } })
      GroupDivider()
      SwitchRow("Timecode", s.timecode, { v -> set { it.copy(timecode = v) } }, subtitle = "Time of day, as on a slate")
    }
    GroupFooter("False colour: purple and blue are crushed, green is 18 % grey, pink is skin, yellow is near clipping, red is clipped.")

    // ---- Interface ----
    GroupHeader("INTERFACE")
    InsetGroup(Modifier.entrance(next(), animate)) {
      val styles = com.tacts.camera.camera.GlassStyle.entries.filter { it != com.tacts.camera.camera.GlassStyle.LIQUID || com.tacts.camera.ui.theme.liquidGlassSupported }
      Pick("Controls", s.glass, styles, { it.label }, sub = { it.description }) { v -> set { it.copy(glass = v) } }
    }
    GroupFooter("Turning down transparency and blur in Android makes the controls solid.")

    // ---- Camera ----
    GroupHeader("CAMERA")
    InsetGroup(Modifier.entrance(next(), animate)) {
      ListRow(
        "Lenses",
        value = s.physicalCameraId?.let { "Lens $it" } ?: "Automatic",
        icon = Icons.Rounded.Camera,
        enabled = caps.physicalCameraIds.isNotEmpty(),
        onClick = { open(Page.Lenses) }
      )
      GroupDivider()
      ListRow("Camera info", value = caps.hardwareLevelLabel, icon = Icons.Rounded.Info, onClick = { open(Page.Info) })
      GroupDivider()
      ListRow("Reset all settings", chevron = false, icon = Icons.Rounded.Tune, onClick = { set { CameraSettings(mode = it.mode, lensFacing = it.lensFacing) } })
    }
    GroupFooter("T.A.C.T.S. ${versionName(LocalContext.current)} · ${caps.hardwareLevelLabel} camera${if (caps.manualSensor) " with manual sensor" else ""}${if (caps.rawCapable) " and RAW" else ""}")
    Spacer(Modifier.height(Space.section))
  }
}

@Composable
private fun LutPage(camera: TactsCamera, onBack: () -> Unit) {
  val context = LocalContext.current
  var names by remember { mutableStateOf(camera.luts.names()) }
  val s = camera.settings
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      try {
        val name = camera.luts.import(uri)
        names = camera.luts.names()
        camera.update { it.copy(lutName = name) }
      } catch (e: Exception) {
        camera.notice = e.message ?: "That file is not a 3D .cube LUT"
      }
    }
  }
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    SubPageHeader("LUTs", "Settings", onBack) {
      TextAction("Import .cube", onClick = { picker.launch(arrayOf("*/*")) })
    }
    InsetGroup(Modifier.entrance()) {
      RadioRow("None", selected = s.lutName == null, onClick = { camera.update { it.copy(lutName = null) } })
      names.forEachIndexed { i, name ->
        GroupDivider()
        RadioRow(
          name,
          selected = s.lutName == name,
          subtitle = if (camera.luts.isBuiltIn(name)) "Built in" else "Imported",
          onClick = { camera.update { it.copy(lutName = name) } },
          modifier = Modifier.entrance(i + 1)
        )
      }
    }
    GroupHeader("APPLY")
    InsetGroup {
      SwitchRow("Burn into video", s.lutOnVideo, { v -> camera.update { it.copy(lutOnVideo = v) } }, subtitle = "Off: the LUT is only for photos")
    }
    GroupFooter("In photo mode the LUT is applied to the saved JPEG. In video it runs on the GPU for the preview and the recording (8-bit SDR only).")
    if (s.lutName != null && !camera.luts.isBuiltIn(s.lutName)) {
      Spacer(Modifier.height(Space.l))
      InsetGroup {
        ListRow("Delete \"${s.lutName}\"", chevron = false, onClick = {
          camera.luts.delete(s.lutName)
          camera.update { it.copy(lutName = null) }
          names = camera.luts.names()
        })
      }
    }
    Spacer(Modifier.height(Space.section))
  }
}

/** Saved sets of settings: save the current one, tap one to apply it, delete the chosen one. */
@Composable
private fun PresetPage(camera: TactsCamera, onBack: () -> Unit) {
  val s = camera.settings
  var chosen by remember { mutableStateOf<Int?>(null) }
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    SubPageHeader("Presets", "Settings", onBack) {
      TextAction("Save current", onClick = {
        val p = s.toPreset()
        if (s.presets.any { it.values == p.values }) camera.notice = "Already saved as \"${s.presets.first { it.values == p.values }.name}\""
        else camera.update { it.copy(presets = it.presets + p) }
      })
    }
    if (s.presets.isEmpty()) {
      GroupFooter("No presets yet. Set exposure, focus, colour and formats the way you want them, then Save current.")
    } else {
      InsetGroup(Modifier.entrance()) {
        s.presets.forEachIndexed { i, p ->
          if (i > 0) GroupDivider()
          RadioRow(
            "Preset ${i + 1}",
            selected = chosen == i,
            subtitle = p.name,
            onClick = {
              chosen = i
              camera.applyPreset(p)
            },
            modifier = Modifier.entrance(i + 1)
          )
        }
      }
      GroupFooter("Applying a preset keeps the mode, the lens and the clip numbering.")
      chosen?.takeIf { it in s.presets.indices }?.let { i ->
        Spacer(Modifier.height(Space.l))
        InsetGroup {
          ListRow("Delete preset ${i + 1}", chevron = false, onClick = {
            chosen = null
            camera.update { it.copy(presets = it.presets.filterIndexed { k, _ -> k != i }) }
          })
        }
      }
    }
    Spacer(Modifier.height(Space.section))
  }
}

@Composable
private fun LensPage(camera: TactsCamera, onBack: () -> Unit) {
  val caps = camera.caps
  val s = camera.settings
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    SubPageHeader("Lenses", "Settings", onBack)
    InsetGroup(Modifier.entrance()) {
      RadioRow("Automatic", selected = s.physicalCameraId == null, subtitle = "The phone switches lenses with zoom", onClick = { camera.update { it.copy(physicalCameraId = null) } })
      caps.physicalCameraIds.forEachIndexed { i, id ->
        GroupDivider()
        RadioRow(
          "Lens $id",
          selected = s.physicalCameraId == id,
          subtitle = physicalSummary(LocalContext.current, id),
          onClick = { camera.update { it.copy(physicalCameraId = id) } },
          modifier = Modifier.entrance(i + 1)
        )
      }
    }
    GroupFooter("Locking to one lens stops the phone from switching between ultra-wide, wide and tele while you zoom or film.")
  }
}

private fun physicalSummary(context: Context, id: String): String = try {
  val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
  val c = manager.getCameraCharacteristics(id)
  val focal = c.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
  val sensor = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
  val aperture = c.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
  // 35 mm equivalent from the sensor diagonal.
  val equiv = if (focal != null && sensor != null) {
    val diag = kotlin.math.sqrt(sensor.width * sensor.width + sensor.height * sensor.height)
    (focal * 43.27f / diag).roundToInt()
  } else null
  listOfNotNull(equiv?.let { "$it mm equiv." }, focal?.let { "%.2f mm".format(it) }, aperture?.let { "f/%.1f".format(it) }).joinToString(" · ")
} catch (e: Exception) {
  ""
}

private fun versionName(context: Context): String = try {
  context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
} catch (e: Exception) {
  ""
}

