package com.tacts.camera.ui

import android.content.Intent
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.TextureView
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timer10
import androidx.compose.material.icons.rounded.Timer3
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tacts.camera.camera.CaptureMode
import com.tacts.camera.camera.Desqueeze
import com.tacts.camera.camera.Flash
import com.tacts.camera.camera.PhotoMode
import com.tacts.camera.camera.ShutterMode
import com.tacts.camera.camera.TactsCamera
import com.tacts.camera.camera.WhiteBalanceMode
import com.tacts.camera.camera.angleLabel
import com.tacts.camera.camera.formatFactor
import com.tacts.camera.camera.shutterLabel
import com.tacts.camera.ui.components.FilterChip
import com.tacts.camera.ui.components.IconAction
import com.tacts.camera.ui.settings.SettingsScreen
import com.tacts.camera.ui.theme.AppText
import com.tacts.camera.ui.theme.JetBrainsMono
import com.tacts.camera.ui.theme.Shapes
import com.tacts.camera.ui.theme.Space
import com.tacts.camera.ui.theme.SpaceGrotesk
import com.tacts.camera.ui.theme.bounceEntrance
import com.tacts.camera.ui.theme.floatingSurface
import com.tacts.camera.ui.theme.glassContentColor
import com.tacts.camera.ui.theme.glassControl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.roundToInt

private enum class Panel { NONE, PRO, LOOKS, MONITOR }

@Composable
fun CameraScreen(startMode: CaptureMode?) {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()
  val camera = remember { TactsCamera(context) }
  val previewView = remember {
    PreviewView(context).apply {
      // TextureView, so the look's layer paint tints the preview.
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
      scaleType = PreviewView.ScaleType.FILL_CENTER
    }
  }
  DisposableEffect(owner) {
    camera.attach(owner, previewView, startMode)
    onDispose { camera.release() }
  }

  var panel by remember { mutableStateOf(Panel.NONE) }
  var proTab by remember { mutableStateOf(ProTab.ISO) }
  var settingsOpen by remember { mutableStateOf(false) }
  var countdown by remember { mutableIntStateOf(0) }
  var focusPoint by remember { mutableStateOf<Offset?>(null) }
  var aePoint by remember { mutableStateOf<Offset?>(null) }
  var focusTick by remember { mutableIntStateOf(0) }
  // The capture blink: the frame dims for an instant, as the shutter closes.
  val blink = remember { Animatable(0f) }
  val s = camera.settings

  LaunchedEffect(focusTick) {
    if (focusTick > 0) {
      delay(1200)
      focusPoint = null
    }
  }
  LaunchedEffect(camera.notice) {
    if (camera.notice != null) {
      delay(3200)
      camera.notice = null
    }
  }

  fun shoot() {
    if (s.mode != CaptureMode.PHOTO || camera.progress != null) {
      camera.shutter()
      return
    }
    if (countdown > 0 || camera.busy) return
    scope.launch {
      for (sec in s.timerSeconds downTo 1) {
        countdown = sec
        delay(1000)
      }
      countdown = 0
      camera.shutter()
      blink.snapTo(0.7f)
      blink.animateTo(0f, tween(220))
    }
  }

  if (settingsOpen) {
    SettingsScreen(camera, onClose = { settingsOpen = false })
    return
  }

  Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Column(Modifier.fillMaxSize()) {
      TopBar(
        camera = camera,
        panel = panel,
        onSettings = { settingsOpen = true },
        onTimer = { camera.update { it.copy(timerSeconds = when (it.timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }) } },
        onPanel = { panel = if (panel == it) Panel.NONE else it }
      )
      InfoStrip(camera)

      BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Photo keeps its 3:4 frame; video fills the whole area edge to edge and crops what does not fit.
        val photo = s.mode == CaptureMode.PHOTO
        Box(
          (if (photo) Modifier.aspectRatio(3f / 4f, matchHeightConstraintsFirst = maxWidth / maxHeight > 3f / 4f).clip(Shapes.xs)
          else Modifier.fillMaxSize().clipToBounds())
            .background(Color.Black)
        ) {
          // Anamorphic desqueeze: the picture is shown wider than recorded, letterboxed in the frame.
          val squeeze = Modifier.graphicsLayer { if (s.desqueeze != Desqueeze.OFF) scaleY = 1f / s.desqueeze.factor }
          Box(Modifier.fillMaxSize().then(squeeze)) {
            if (s.mode == CaptureMode.RAW_VIDEO) {
              RawPreview(camera)
            } else {
              AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            }
            if (s.mode != CaptureMode.RAW_VIDEO) MonitorOverlay(camera.monitor)
          }
          Box(
            Modifier
              .fillMaxSize()
              .pointerInput(Unit) {
                detectTapGestures(
                  onTap = { p ->
                    camera.focusAt(p.x, p.y)
                    focusPoint = p
                    focusTick++
                  },
                  // FiLMiC's separate reticle: a long press meters exposure there, focus stays.
                  onLongPress = { p ->
                    camera.exposeAt(p.x, p.y)
                    aePoint = p
                  }
                )
              }
              .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ -> camera.setZoom(camera.zoomRatio * zoom) }
              }
          )
          GridOverlay(s.grid)
          FrameGuideOverlay(s.frameGuide, s.safeArea, s.centerMark)
          if (s.level) LevelOverlay()
          focusPoint?.let { FocusRing(it) }
          aePoint?.let { AeReticle(it) { aePoint = null } }
          Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = blink.value)))
          if (s.mode != CaptureMode.RAW_VIDEO) ScopePanel(s.scope, camera.monitor, Modifier.align(Alignment.TopStart).padding(Space.m))
          Column(Modifier.align(Alignment.TopCenter).padding(Space.m), horizontalAlignment = Alignment.CenterHorizontally) {
            if (camera.isRecording) RecordingPill(camera)
            camera.progress?.let { ProgressPill(it) }
          }
          if (countdown > 0) {
            key(countdown) {
              Text(
                "$countdown",
                style = AppText.Title1.copy(fontFamily = SpaceGrotesk, fontSize = 104.sp, lineHeight = 110.sp, fontWeight = FontWeight.Medium),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).bounceEntrance()
              )
            }
          }
          camera.notice?.let {
            key(it) {
              Text(
                it,
                style = AppText.Footnote,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                  .align(Alignment.Center)
                  .padding(horizontal = Space.xl)
                  .bounceEntrance()
                  .floatingSurface(Shapes.m)
                  .padding(horizontal = Space.l, vertical = Space.s)
              )
            }
          }
        }
        // Panels float over the bottom of the frame, so opening one never resizes the preview.
        Column(
          Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = Space.s),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          // A lens locked in Settings ignores the capsule's lens changes, so it is not offered then.
          if (s.mode != CaptureMode.RAW_VIDEO && s.physicalCameraId == null) ZoomChips(camera, Modifier.padding(bottom = Space.s))
          when (panel) {
            Panel.PRO -> ProPanel(camera, proTab, onTab = { proTab = it })
            Panel.LOOKS -> LookStrip(camera)
            Panel.MONITOR -> MonitorPanel(camera)
            Panel.NONE -> Unit
          }
        }
      }

      Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        if (s.mode == CaptureMode.PHOTO) PhotoModes(camera)
        ModeSwitch(camera)
        Row(
          Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.m),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          LastCapture(camera, Modifier.bounceEntrance(0)) {
            camera.lastCapture?.let { uri ->
              val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, if (camera.lastIsVideo) "video/*" else "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              runCatching { context.startActivity(intent) }
            }
          }
          ShutterButton(camera, countdown > 0, Modifier.bounceEntrance(1)) { shoot() }
          GlassIcon(
            Icons.Rounded.Cameraswitch,
            contentDescription = "Switch camera",
            enabled = camera.hasFrontAndBack && !camera.isRecording,
            size = 52.dp,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.bounceEntrance(2)
          ) { camera.switchLens() }
        }
      }
    }
  }
}

/** The RAW engine draws into a TextureView of its own; CameraX's PreviewView is not used then. */
@Composable
private fun RawPreview(camera: TactsCamera) {
  // The picture is 9:16 and covers the frame like the CameraX preview does, instead of being stretched.
  BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
    val scale = maxOf(maxWidth / 9f, maxHeight / 16f)
    AndroidView(
      modifier = Modifier.requiredSize(scale * 9f, scale * 16f),
      factory = { ctx ->
      TextureView(ctx).apply {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
          override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) =
            camera.attachRawPreview(texture, Size(width, height))

          override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
          override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            camera.attachRawPreview(null, null)
            return true
          }

          override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
      }
      }
    )
  }
}

@Composable
private fun TopBar(
  camera: TactsCamera,
  panel: Panel,
  onSettings: () -> Unit,
  onTimer: () -> Unit,
  onPanel: (Panel) -> Unit
) {
  val colors = MaterialTheme.colorScheme
  val s = camera.settings
  fun tint(active: Boolean) = if (active) colors.primary else colors.onSurface
  // The iOS 26 arrangement: flash alone on the left, the tools in one capsule in the middle, the mode's
  // own switch (PRO) on the right.
  Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Space.m, vertical = Space.xs)) {
    if (camera.caps.hasFlash) {
      GlassIcon(
        when (s.flash) {
          Flash.OFF -> Icons.Rounded.FlashOff
          Flash.AUTO -> Icons.Rounded.FlashAuto
          Flash.ON -> Icons.Rounded.FlashOn
        },
        contentDescription = "Flash ${s.flash.name.lowercase()}",
        tint = tint(s.flash != Flash.OFF),
        modifier = Modifier.align(Alignment.CenterStart).bounceEntrance(0)
      ) {
        camera.update {
          it.copy(
            flash = if (it.mode != CaptureMode.PHOTO) (if (it.flash == Flash.OFF) Flash.ON else Flash.OFF)
            else Flash.entries[(it.flash.ordinal + 1) % Flash.entries.size]
          )
        }
      }
    }

    Row(
      Modifier.align(Alignment.Center).bounceEntrance(1).floatingSurface(Shapes.pill).padding(horizontal = Space.xxs),
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconAction(Icons.Rounded.Settings, "Settings", onClick = onSettings, tint = colors.onSurface)
      if (s.mode == CaptureMode.PHOTO) {
        IconAction(
          when (s.timerSeconds) { 0 -> Icons.Rounded.TimerOff; 3 -> Icons.Rounded.Timer3; 10 -> Icons.Rounded.Timer10; else -> Icons.Rounded.Timer3 },
          contentDescription = if (s.timerSeconds == 0) "Timer off" else "Timer ${s.timerSeconds} seconds",
          onClick = onTimer,
          tint = tint(s.timerSeconds > 0)
        )
      } else {
        TagToggle("${if (s.mode == CaptureMode.SLOW_MO) s.slowMoFps else s.frameRate}p", selected = false) {
          val rates = if (s.mode == CaptureMode.SLOW_MO) camera.caps.highSpeedFps else camera.caps.frameRates
          if (rates.isNotEmpty()) {
            camera.update {
              if (it.mode == CaptureMode.SLOW_MO) it.copy(slowMoFps = rates[(rates.indexOf(it.slowMoFps) + 1) % rates.size])
              else it.copy(frameRate = rates[(rates.indexOf(it.frameRate) + 1) % rates.size])
            }
          }
        }
      }
      IconAction(
        Icons.Rounded.Visibility,
        contentDescription = "Monitoring",
        onClick = { onPanel(Panel.MONITOR) },
        tint = tint(panel == Panel.MONITOR || s.peaking || s.zebras || s.falseColor)
      )
      IconAction(
        Icons.Rounded.Palette,
        contentDescription = "Looks and LUTs",
        onClick = { onPanel(Panel.LOOKS) },
        tint = tint(panel == Panel.LOOKS || s.look != "NATURAL" || s.lutName != null)
      )
    }

    Box(Modifier.align(Alignment.CenterEnd).bounceEntrance(2)) {
      TagToggle("PRO", selected = panel == Panel.PRO || !s.isAllAuto) { onPanel(Panel.PRO) }
    }
  }
}

/** A round button on the glass fill, as the iOS 26 camera draws its corner controls. */
@Composable
private fun GlassIcon(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  contentDescription: String,
  tint: Color,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  size: androidx.compose.ui.unit.Dp = 44.dp,
  onClick: () -> Unit
) {
  Box(
    modifier
      .size(maxOf(size, 48.dp))
      .clip(CircleShape)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .semantics { this.contentDescription = contentDescription },
    contentAlignment = Alignment.Center
  ) {
    Box(Modifier.size(size).floatingSurface(CircleShape), contentAlignment = Alignment.Center) {
      Icon(icon, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = 0.38f), modifier = Modifier.size(22.dp))
    }
  }
}

/**
 * The status line of a cinema camera: format, frame rate, shutter, ISO, white balance and, in video,
 * the timecode; in RAW video, the frames written and dropped.
 */
@Composable
private fun InfoStrip(camera: TactsCamera) {
  val s = camera.settings
  val live = camera.live
  val colors = MaterialTheme.colorScheme
  var timecode by remember { mutableStateOf("") }
  val video = s.mode != CaptureMode.PHOTO
  LaunchedEffect(video, s.timecode, s.frameRate) {
    while (video && s.timecode) {
      val c = Calendar.getInstance()
      val frames = c.get(Calendar.MILLISECOND) * s.frameRate / 1000
      timecode = "%02d:%02d:%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND), frames)
      delay(1000L / s.frameRate.coerceAtLeast(1))
    }
  }
  val shutter = when {
    s.exposureAuto -> live.shutterNs?.let(::shutterLabel)
    video && s.shutterMode == ShutterMode.ANGLE -> angleLabel(s.shutterAngle)
    else -> shutterLabel(s.shutterNs)
  }
  val iso = if (s.exposureAuto) live.iso else s.iso
  val wb = when (s.wbMode) {
    WhiteBalanceMode.AUTO -> if (s.awbLock) "AWB-L" else "AWB"
    WhiteBalanceMode.PRESET -> "${s.whiteBalance.kelvin}K"
    WhiteBalanceMode.KELVIN -> "${s.kelvin}K${if (s.tint != 0) " ${if (s.tint > 0) "+" else ""}${s.tint}" else ""}"
  }
  val parts = buildList {
    when (s.mode) {
      CaptureMode.PHOTO -> {
        add(s.photoFormat.label.substringBefore(" (").uppercase())
        if (s.photoMode != PhotoMode.SINGLE) add(s.photoMode.label.uppercase())
      }
      CaptureMode.VIDEO, CaptureMode.SLOW_MO -> add(camera.boundVideo ?: "${s.videoQuality.label} ${s.frameRate}p")
      CaptureMode.RAW_VIDEO -> {
        add("RAW DNG ${s.frameRate}p")
        if (camera.rawStats.recording || camera.rawStats.written > 0) add("${camera.rawStats.written} fr · ${camera.rawStats.dropped} drop")
      }
    }
    shutter?.let { add(it) }
    iso?.let { add("ISO $it") }
    add(wb)
    if (s.gamma.name != "STANDARD") add(s.gamma.label.uppercase())
    if (s.lutName != null) add("LUT")
    if (video && s.timecode && timecode.isNotEmpty()) add(timecode)
  }
  Row(
    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.l, vertical = Space.xxs),
    horizontalArrangement = Arrangement.Center
  ) {
    Text(
      parts.joinToString("  ·  "),
      style = AppText.Caption1.copy(fontFamily = JetBrainsMono),
      color = colors.onSurfaceVariant,
      maxLines = 1
    )
  }
}

/** A short on/off label in the top bar (PRO, the frame rate), on the quiet control fill. */
@Composable
private fun TagToggle(text: String, selected: Boolean, onClick: () -> Unit) {
  Box(
    Modifier
      .size(width = 56.dp, height = 48.dp)
      .toggleable(value = selected, role = Role.Switch, onValueChange = { onClick() }),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text,
      style = AppText.Caption1.copy(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold),
      color = glassContentColor(selected),
      modifier = Modifier.glassControl(Shapes.xs, selected).padding(horizontal = Space.s, vertical = Space.xs)
    )
  }
}

@Composable
private fun FocusRing(at: Offset) {
  val half = with(LocalDensity.current) { 36.dp.toPx() }
  key(at) {
    Box(
      Modifier
        .offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }
        .bounceEntrance()
        .size(72.dp)
        .border(1.5.dp, MaterialTheme.colorScheme.primary, Shapes.xs)
    )
  }
}

/** The exposure reticle: a circle with a sun, where exposure is metered; tap it to release. */
@Composable
private fun AeReticle(at: Offset, onClear: () -> Unit) {
  val half = with(LocalDensity.current) { 28.dp.toPx() }
  key(at) {
    Box(
      Modifier
        .offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }
        .bounceEntrance()
        .size(56.dp)
        .border(1.5.dp, Color(0xFFFFD60A), CircleShape)
        .clip(CircleShape)
        .clickable(role = Role.Button, onClick = onClear)
        .semantics { contentDescription = "Exposure point, tap to release" },
      contentAlignment = Alignment.Center
    ) {
      Icon(Icons.Rounded.WbSunny, contentDescription = null, tint = Color(0xFFFFD60A), modifier = Modifier.size(18.dp))
    }
  }
}

/** Recording time and, while sound is recorded, a level meter. */
@Composable
private fun RecordingPill(camera: TactsCamera) {
  val colors = MaterialTheme.colorScheme
  val seconds = if (camera.settings.mode == CaptureMode.RAW_VIDEO) null else camera.recordedSeconds
  Row(verticalAlignment = Alignment.CenterVertically) {
    Row(
      Modifier
        .bounceEntrance()
        .clip(Shapes.pill)
        .background(colors.error)
        .padding(horizontal = Space.m, vertical = Space.xs),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(Modifier.size(8.dp).clip(CircleShape).background(colors.onError))
      Spacer(Modifier.width(Space.s))
      Text(
        seconds?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "REC ${camera.rawStats.written}",
        style = AppText.Subhead.copy(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium),
        color = colors.onError
      )
    }
    if (camera.settings.audioEnabled && camera.settings.mode == CaptureMode.VIDEO) {
      Spacer(Modifier.width(Space.s))
      AudioMeter(camera.audioLevel)
    }
  }
}

/** A horizontal level meter: green, yellow from -12 dBFS, red from -3 dBFS. */
@Composable
private fun AudioMeter(level: Float) {
  val db = if (level <= 0f) -60f else (20f * kotlin.math.log10(level)).coerceIn(-60f, 0f)
  val fill = (db + 60f) / 60f
  val color = when {
    db > -3f -> Color(0xFFFF3B30)
    db > -12f -> Color(0xFFFFD60A)
    else -> Color(0xFF34C759)
  }
  Box(Modifier.bounceEntrance(1).size(width = 72.dp, height = 10.dp).floatingSurface(Shapes.pill)) {
    Box(Modifier.fillMaxHeight().width(72.dp * fill).clip(Shapes.pill).background(color))
  }
}

@Composable
private fun ProgressPill(text: String) {
  key(text) {
    Text(
      text,
      style = AppText.Subhead.copy(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium),
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier
        .padding(top = Space.xs)
        .bounceEntrance()
        .floatingSurface(Shapes.pill)
        .padding(horizontal = Space.m, vertical = Space.xs)
    )
  }
}

@Composable
private fun ZoomChips(camera: TactsCamera, modifier: Modifier) {
  // One button per real lens (0.6, 1, 3, ... as measured from the lenses), like the iOS capsule.
  val stops = camera.lensStops
  if (stops.size < 2) return
  val colors = MaterialTheme.colorScheme
  val factor = camera.zoomFactor
  val pinned = camera.settings.cameraId
  val active = if (pinned != null) stops.firstOrNull { it.cameraId == pinned }
  else stops.filter { it.cameraId == null }.lastOrNull { it.factor <= factor + 0.01f }
  Row(
    modifier.bounceEntrance().floatingSurface(Shapes.pill).padding(Space.xxs),
    horizontalArrangement = Arrangement.spacedBy(Space.xxs),
    verticalAlignment = Alignment.CenterVertically
  ) {
    stops.forEachIndexed { i, stop ->
      val selected = stop == active
      val text = if (selected) formatFactor(factor) else stop.label
      Box(
        Modifier
          .bounceEntrance(i)
          .size(if (selected) 44.dp else 36.dp)
          .clip(CircleShape)
          .background(if (selected) colors.surfaceContainer else Color.Transparent)
          .clickable(role = Role.Button) { camera.selectLens(stop) }
          .semantics { contentDescription = "Lens ${stop.label} times" },
        contentAlignment = Alignment.Center
      ) {
        Text(
          if (selected) "$text×" else text,
          style = AppText.Caption1.copy(fontFamily = JetBrainsMono, fontWeight = FontWeight.SemiBold),
          color = if (selected) colors.primary else colors.onSurface
        )
      }
    }
  }
}

@Composable
private fun PhotoModes(camera: TactsCamera) {
  Row(
    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.s),
    horizontalArrangement = Arrangement.spacedBy(Space.xxs, Alignment.CenterHorizontally)
  ) {
    // Quieter than the modes below: plain words, the chosen one in the accent, as iOS marks its options.
    val colors = MaterialTheme.colorScheme
    PhotoMode.entries.forEachIndexed { i, m ->
      val selected = camera.settings.photoMode == m
      Box(
        Modifier
          .bounceEntrance(i)
          .height(36.dp)
          .clip(Shapes.pill)
          .selectable(selected = selected, enabled = camera.progress == null, role = Role.Tab) { camera.update { it.copy(photoMode = m) } }
          .padding(horizontal = Space.m),
        contentAlignment = Alignment.Center
      ) {
        Text(
          m.label.uppercase(),
          style = AppText.Caption1.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
          color = if (selected) colors.primary else colors.onSurfaceVariant,
          maxLines = 1
        )
      }
    }
  }
}

@Composable
private fun ModeSwitch(camera: TactsCamera) {
  val caps = camera.caps
  val modes = CaptureMode.entries.filter {
    when (it) {
      CaptureMode.SLOW_MO -> caps.highSpeedFps.isNotEmpty()
      CaptureMode.RAW_VIDEO -> caps.rawCapable
      else -> true
    }
  }
  val colors = MaterialTheme.colorScheme
  // iOS 26: the modes are words, and the chosen one sits in a glass pill.
  Row(
    Modifier.fillMaxWidth().padding(top = Space.xs),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Row(Modifier.floatingSurface(Shapes.pill).padding(Space.xxs)) {
      modes.forEachIndexed { i, m ->
        val selected = camera.settings.mode == m
        Box(
          Modifier
            .bounceEntrance(i)
            .height(40.dp)
            .clip(Shapes.pill)
            .background(if (selected) colors.surfaceContainer else Color.Transparent)
            .selectable(selected = selected, enabled = !camera.isRecording, role = Role.Tab) { camera.update { it.copy(mode = m) } }
            .padding(horizontal = Space.l),
          contentAlignment = Alignment.Center
        ) {
          Text(
            m.label,
            style = AppText.Subhead.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = when {
              selected -> colors.onSurface
              camera.isRecording -> colors.onSurfaceVariant.copy(alpha = 0.5f)
              else -> colors.onSurfaceVariant
            },
            maxLines = 1
          )
        }
      }
    }
  }
}

@Composable
private fun ShutterButton(camera: TactsCamera, waiting: Boolean, modifier: Modifier, onClick: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  val mode = camera.settings.mode
  val video = mode != CaptureMode.PHOTO
  val active = camera.isRecording || camera.progress != null
  Box(
    modifier
      .size(78.dp)
      .border(4.dp, colors.onSurface, CircleShape)
      .clip(CircleShape)
      .clickable(enabled = !waiting, role = Role.Button, onClick = onClick)
      .semantics {
        contentDescription = when {
          active -> "Stop"
          video -> "Record"
          else -> "Take photo"
        }
      },
    contentAlignment = Alignment.Center
  ) {
    Box(
      Modifier
        .size(if (active) 28.dp else 62.dp)
        .clip(if (active) RoundedCornerShape(6.dp) else CircleShape)
        .background(
          when {
            video || active -> colors.error
            camera.busy || waiting -> colors.onSurface.copy(alpha = 0.5f)
            else -> colors.onSurface
          }
        )
    )
  }
}

@Composable
private fun LastCapture(camera: TactsCamera, modifier: Modifier, onClick: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  Box(
    modifier
      .size(52.dp)
      .clip(Shapes.s)
      .background(colors.surfaceContainer)
      .border(1.5.dp, colors.onSurface.copy(alpha = 0.6f), Shapes.s)
      .clickable(enabled = camera.lastCapture != null, role = Role.Button, onClick = onClick)
      .semantics { contentDescription = "Open last capture" },
    contentAlignment = Alignment.Center
  ) {
    val thumb = camera.lastThumb
    if (thumb != null) {
      key(thumb) {
        Image(thumb.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().bounceEntrance())
      }
    } else if (camera.lastCapture != null && camera.lastIsVideo) {
      Icon(Icons.Rounded.Videocam, contentDescription = null, tint = colors.onSurface)
    }
  }
}

