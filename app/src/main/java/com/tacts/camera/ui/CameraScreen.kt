package com.tacts.camera.ui

import android.content.Intent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Shadow
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.tacts.camera.camera.GlassStyle
import com.tacts.camera.camera.GridStyle
import com.tacts.camera.camera.PhotoFormat
import com.tacts.camera.camera.Scope
import com.tacts.camera.ui.theme.LocalBackdrop
import com.tacts.camera.ui.theme.LocalGlassStyle
import com.tacts.camera.ui.theme.LocalHazeState
import com.tacts.camera.ui.theme.liquidGlassSupported
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
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

private enum class Panel { NONE, PRO, LOOKS, MONITOR, CONTROLS }

@Composable
fun CameraScreen(startMode: CaptureMode?) {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()
  val camera = remember { TactsCamera(context) }
  val previewView = remember {
    PreviewView(context).apply {
      // SurfaceView or TextureView is chosen at each bind (TactsCamera.rebind).
      scaleType = PreviewView.ScaleType.FILL_CENTER
    }
  }
  DisposableEffect(owner) {
    camera.attach(owner, previewView, startMode)
    onDispose { camera.release() }
  }

  var panel by remember { mutableStateOf(Panel.NONE) }
  var proTab by remember { mutableStateOf(ProTab.ISO) }
  // The ƒ button: shows the manual values over the shutter, as the iOS camera does.
  var proRow by remember { mutableStateOf(false) }
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

  // The glass of T.A.R.K.A.S.: only the viewfinder is recorded, and every control over it blurs or
  // bends it. The system's "reduce blur" setting turns glass into the solid surface.
  val hazeState = remember { HazeState() }
  val rawTexture = remember { mutableStateOf<TextureView?>(null) }
  val backdrop = rememberLayerBackdrop()
  val blurOn = s.glass != GlassStyle.SOLID && systemBlurAllowed(context)
  val liquidOn = blurOn && s.glass == GlassStyle.LIQUID && liquidGlassSupported
  val closePanel = { panel = Panel.NONE }

  CompositionLocalProvider(
    LocalGlassStyle provides s.glass,
    LocalHazeState provides if (blurOn) hazeState else null,
    LocalBackdrop provides if (liquidOn) backdrop else null
  ) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
      // Photo keeps its 3:4 frame under the top bar; video fills the whole screen and the controls float
      // over it.
      val photo = s.mode == CaptureMode.PHOTO
      val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TOP_BAR
      val frame = if (photo) Modifier.padding(top = top).fillMaxWidth().aspectRatio(3f / 4f) else Modifier.fillMaxSize()

      // The glass blurs a small copy of the picture drawn under the real one (see PreviewMirror). The
      // recorded layer covers the whole screen, because liquid glass places its samples in screen space;
      // the copy draws itself where the viewfinder is.
      if (blurOn) {
        Box(
          Modifier
            .fillMaxSize()
            .hazeSource(hazeState)
            .then(if (liquidOn) Modifier.layerBackdrop(backdrop) else Modifier)
        ) {
          PreviewMirror(
            source = { if (s.mode == CaptureMode.RAW_VIDEO) rawTexture.value else previewView.getChildAt(0) },
            modifier = Modifier.fillMaxSize()
          )
        }
      }

      Box(frame.clipToBounds().background(Color.Black)) {
        // Anamorphic desqueeze: the picture is shown wider than recorded, letterboxed in the frame.
        val squeeze = Modifier.graphicsLayer { if (s.desqueeze != Desqueeze.OFF) scaleY = 1f / s.desqueeze.factor }
        Box(Modifier.fillMaxSize().then(squeeze)) {
          if (s.mode == CaptureMode.RAW_VIDEO) RawPreview(camera) { rawTexture.value = it }
          else AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        }
        if (s.mode != CaptureMode.RAW_VIDEO) MonitorOverlay(camera.monitor)
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
      }

      // ---- top: format, lights and looks ----
      Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        TopBar(camera, panel, onPanel = { panel = if (panel == it) Panel.NONE else it })
        Column(Modifier.fillMaxWidth().padding(top = Space.s), horizontalAlignment = Alignment.CenterHorizontally) {
          if (camera.isRecording) RecordingPill(camera) else TimecodePill(camera)
          camera.progress?.let { ProgressPill(it) }
        }
        if (s.mode != CaptureMode.RAW_VIDEO) ScopePanel(s.scope, camera.monitor, Modifier.padding(Space.m))
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

      // ---- bottom: lenses or the open sheet, the shutter row, the modes ----
      Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = Space.s),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        when (panel) {
          Panel.LOOKS -> LookStrip(camera)
          Panel.MONITOR -> MonitorPanel(camera)
          Panel.CONTROLS -> ControlsSheet(
            camera,
            onSettings = {
              panel = Panel.NONE
              settingsOpen = true
            },
            onPanel = { panel = it },
            onExposure = {
              proTab = ProTab.EV
              proRow = true
              panel = Panel.PRO
            }
          )
          // A lens locked in Settings ignores the capsule's lens changes, so it is not offered then.
          else -> if (s.mode != CaptureMode.RAW_VIDEO && s.physicalCameraId == null) ZoomChips(camera, Modifier)
        }
        if (proRow) {
          ProReadoutRow(
            camera,
            selected = proTab.takeIf { panel == Panel.PRO },
            onTab = { t ->
              if (panel == Panel.PRO && proTab == t) panel = Panel.NONE
              else {
                proTab = t
                panel = Panel.PRO
              }
            },
            modifier = Modifier.padding(horizontal = Space.xs, vertical = Space.xxs)
          )
        }
        Spacer(Modifier.height(Space.s))
        Row(
          Modifier.fillMaxWidth().padding(horizontal = Space.xxl),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          GlassText(
            "ƒ",
            contentDescription = "Manual controls",
            tint = if (proRow || !s.isAllAuto) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.bounceEntrance(0)
          ) {
            proRow = !proRow
            if (!proRow && panel == Panel.PRO) panel = Panel.NONE
          }
          ShutterButton(camera, countdown > 0, Modifier.bounceEntrance(1)) { shoot() }
          GlassIcon(
            Icons.Rounded.Apps,
            contentDescription = "More controls",
            tint = if (panel == Panel.CONTROLS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            size = 52.dp,
            modifier = Modifier.bounceEntrance(2)
          ) { panel = if (panel == Panel.CONTROLS) Panel.NONE else Panel.CONTROLS }
        }
        Spacer(Modifier.height(Space.m))
        // The dial of the chosen value takes the place of the modes, under the shutter.
        if (panel == Panel.PRO) {
          ProPanel(camera, proTab, onClose = closePanel)
        } else {
          Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            LastCapture(camera, Modifier.bounceEntrance(3)) {
              camera.lastCapture?.let { uri ->
                val intent = Intent(Intent.ACTION_VIEW)
                  .setDataAndType(uri, if (camera.lastIsVideo) "video/*" else "image/*")
                  .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(intent) }
              }
            }
            ModeSwitch(camera, Modifier.weight(1f).padding(horizontal = Space.s))
            GlassIcon(
              Icons.Rounded.Cameraswitch,
              contentDescription = "Switch camera",
              enabled = camera.hasFrontAndBack && !camera.isRecording,
              size = 48.dp,
              tint = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.bounceEntrance(4)
            ) { camera.switchLens() }
          }
        }
      }
    }
  }
}

private val TOP_BAR = 52.dp

/**
 * False when the system asks for no blur, read the way T.A.R.K.A.S. reads it: Samsung's "Reduce
 * transparency and blur", or window blurs turned off in the developer options.
 */
private fun systemBlurAllowed(context: android.content.Context): Boolean {
  val resolver = context.contentResolver
  fun on(read: () -> Int) = try { read() == 1 } catch (e: Exception) { false }
  return !on { android.provider.Settings.System.getInt(resolver, "accessibility_reduce_transparency", 0) } &&
    !on { android.provider.Settings.Global.getInt(resolver, "disable_window_blurs", 0) }
}

/** The RAW engine draws into a TextureView of its own; CameraX's PreviewView is not used then. */
@Composable
private fun RawPreview(camera: TactsCamera, onView: (TextureView) -> Unit) {
  // The picture is 9:16 and covers the frame like the CameraX preview does, instead of being stretched.
  BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
    val scale = maxOf(maxWidth / 9f, maxHeight / 16f)
    AndroidView(
      modifier = Modifier.requiredSize(scale * 9f, scale * 16f),
      factory = { ctx ->
      TextureView(ctx).apply {
        onView(this)
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
private fun TopBar(camera: TactsCamera, panel: Panel, onPanel: (Panel) -> Unit) {
  val colors = MaterialTheme.colorScheme
  val s = camera.settings
  val caps = camera.caps
  fun tint(active: Boolean) = if (active) colors.primary else colors.onSurface
  // The format on the left (what the shutter makes), the lights and looks on the right, in one capsule.
  Row(
    Modifier.fillMaxWidth().height(TOP_BAR).padding(horizontal = Space.m),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
      when (s.mode) {
        CaptureMode.PHOTO -> {
          val formats = PhotoFormat.entries.filter { it in caps.photoFormats }
          FormatPill(s.photoFormat.shortLabel, 0) {
            if (formats.size > 1) camera.update { it.copy(photoFormat = formats[(formats.indexOf(it.photoFormat) + 1) % formats.size]) }
          }
        }
        CaptureMode.VIDEO -> {
          val qualities = caps.videoQualities
          FormatPill(camera.qualityLabel(), 0) {
            if (qualities.size > 1) camera.update { it.copy(videoQuality = qualities[(qualities.indexOf(it.videoQuality) + 1) % qualities.size]) }
          }
          FormatPill("${s.frameRate}", 1) { camera.cycleFrameRate() }
        }
        CaptureMode.SLOW_MO -> FormatPill("${s.slowMoFps}", 0) { camera.cycleFrameRate() }
        CaptureMode.RAW_VIDEO -> {
          FormatPill("RAW", 0) {}
          FormatPill("${s.frameRate}", 1) { camera.cycleFrameRate() }
        }
      }
    }
    Row(
      Modifier.bounceEntrance(2).floatingSurface(Shapes.pill).padding(horizontal = Space.xxs),
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (caps.hasFlash) {
        IconAction(
          when (s.flash) {
            Flash.OFF -> Icons.Rounded.FlashOff
            Flash.AUTO -> Icons.Rounded.FlashAuto
            Flash.ON -> Icons.Rounded.FlashOn
          },
          contentDescription = "Flash ${s.flash.name.lowercase()}",
          onClick = { camera.cycleFlash() },
          tint = tint(s.flash != Flash.OFF)
        )
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
  }
}

/** A small glass label for the format and frame rate; tapping it steps to the next one. */
@Composable
private fun FormatPill(text: String, index: Int, onClick: () -> Unit) {
  Box(
    Modifier
      .height(48.dp)
      .bounceEntrance(index)
      .clip(Shapes.pill)
      .clickable(role = Role.Button, onClickLabel = "Change", onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text,
      style = AppText.Caption1.copy(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.floatingSurface(Shapes.pill).padding(horizontal = Space.m, vertical = Space.xs)
    )
  }
}

/** A round glass button, as the corner controls of the iOS 26 camera. */
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

/** A round glass button with a glyph instead of an icon (the ƒ of the manual controls). */
@Composable
private fun GlassText(text: String, contentDescription: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Box(
    modifier
      .size(52.dp)
      .clip(CircleShape)
      .clickable(role = Role.Button, onClick = onClick)
      .semantics { this.contentDescription = contentDescription },
    contentAlignment = Alignment.Center
  ) {
    Box(Modifier.size(52.dp).floatingSurface(CircleShape), contentAlignment = Alignment.Center) {
      Text(text, style = AppText.Title2.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium), color = tint)
    }
  }
}

/** Time-of-day timecode while framing a video, as on a cinema camera. */
@Composable
private fun TimecodePill(camera: TactsCamera) {
  val s = camera.settings
  val video = s.mode != CaptureMode.PHOTO
  if (!video || !s.timecode) return
  var timecode by remember { mutableStateOf("") }
  LaunchedEffect(s.frameRate) {
    while (true) {
      val c = Calendar.getInstance()
      val frames = c.get(Calendar.MILLISECOND) * s.frameRate / 1000
      timecode = "%02d:%02d:%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND), frames)
      // Ten times a second: the frame count is exact when drawn, without a redraw on every frame.
      delay(100)
    }
  }
  Text(
    timecode,
    style = AppText.Caption1.copy(fontFamily = JetBrainsMono),
    color = MaterialTheme.colorScheme.onSurface,
    // Its own layer: ten updates a second redraw this pill, not the whole screen.
    modifier = Modifier.graphicsLayer().floatingSurface(Shapes.pill).padding(horizontal = Space.m, vertical = Space.xxs)
  )
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
        seconds?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "REC ${camera.rawStats.written} · ${camera.rawStats.dropped} drop",
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
  // One figure per real lens (0.6, 1, 3, ... as measured from the lenses). Bare figures over the
  // picture; the lens in use sits in a small glass circle, with the exact zoom.
  val stops = camera.lensStops
  if (stops.size < 2) return
  val colors = MaterialTheme.colorScheme
  val factor = camera.zoomFactor
  val pinned = camera.settings.cameraId
  val active = if (pinned != null) stops.firstOrNull { it.cameraId == pinned }
  else stops.filter { it.cameraId == null }.lastOrNull { it.factor <= factor + 0.01f }
  val shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 6f)
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
    stops.forEachIndexed { i, stop ->
      val selected = stop == active
      val text = if (selected) formatFactor(factor) + "×" else stop.label
      Box(
        Modifier
          .bounceEntrance(i)
          .size(48.dp)
          .clip(CircleShape)
          .clickable(role = Role.Button) { camera.selectLens(stop) }
          .semantics { contentDescription = "Lens ${stop.label} times" },
        contentAlignment = Alignment.Center
      ) {
        Box(
          Modifier.size(40.dp).then(if (selected) Modifier.floatingSurface(CircleShape) else Modifier),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text,
            style = AppText.Caption1.copy(fontFamily = JetBrainsMono, fontWeight = FontWeight.SemiBold, shadow = if (selected) null else shadow),
            color = if (selected) colors.primary else colors.onSurface
          )
        }
      }
    }
  }
}

private val CaptureMode.shortLabel: String
  get() = when (this) {
    CaptureMode.PHOTO -> "PHOTO"
    CaptureMode.VIDEO -> "VIDEO"
    CaptureMode.SLOW_MO -> "SLO-MO"
    CaptureMode.RAW_VIDEO -> "RAW"
  }

@Composable
private fun ModeSwitch(camera: TactsCamera, modifier: Modifier) {
  val caps = camera.caps
  val colors = MaterialTheme.colorScheme
  val modes = CaptureMode.entries.filter {
    when (it) {
      CaptureMode.SLOW_MO -> caps.highSpeedFps.isNotEmpty()
      CaptureMode.RAW_VIDEO -> caps.rawCapable
      else -> true
    }
  }
  // The modes are words in one glass pill; the chosen one sits in an inner capsule, in the accent.
  Box(modifier, contentAlignment = Alignment.Center) {
    Row(
      Modifier.floatingSurface(Shapes.pill).horizontalScroll(rememberScrollState()).padding(Space.xxs),
      verticalAlignment = Alignment.CenterVertically
    ) {
      modes.forEachIndexed { i, m ->
        val selected = camera.settings.mode == m
        Box(
          Modifier
            .bounceEntrance(i)
            .height(44.dp)
            .clip(Shapes.pill)
            .then(if (selected) Modifier.background(colors.onSurface.copy(alpha = 0.14f), Shapes.pill) else Modifier)
            .selectable(selected = selected, enabled = !camera.isRecording, role = Role.Tab) { camera.update { it.copy(mode = m) } }
            .semantics { contentDescription = m.label }
            .padding(horizontal = Space.m),
          contentAlignment = Alignment.Center
        ) {
          Text(
            m.shortLabel,
            style = AppText.Caption1.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
            color = when {
              selected -> colors.primary
              camera.isRecording -> colors.onSurfaceVariant.copy(alpha = 0.5f)
              else -> colors.onSurface
            },
            maxLines = 1
          )
        }
      }
    }
  }
}

/**
 * Everything that is not on screen all the time, in one glass sheet: the photo drive modes on top,
 * then round buttons with their names, three to a row.
 */
@Composable
private fun ControlsSheet(camera: TactsCamera, onSettings: () -> Unit, onPanel: (Panel) -> Unit, onExposure: () -> Unit) {
  val s = camera.settings
  val caps = camera.caps
  val colors = MaterialTheme.colorScheme
  val buttons = buildList<@Composable () -> Unit> {
    if (caps.hasFlash) add {
      ControlButton(
        when (s.flash) { Flash.OFF -> Icons.Rounded.FlashOff; Flash.AUTO -> Icons.Rounded.FlashAuto; Flash.ON -> Icons.Rounded.FlashOn },
        if (s.mode == CaptureMode.PHOTO) "FLASH" else "TORCH",
        s.flash != Flash.OFF
      ) { camera.cycleFlash() }
    }
    if (s.mode == CaptureMode.PHOTO) add {
      ControlButton(
        when (s.timerSeconds) { 0 -> Icons.Rounded.TimerOff; 3 -> Icons.Rounded.Timer3; else -> Icons.Rounded.Timer10 },
        "TIMER",
        s.timerSeconds > 0
      ) { camera.update { it.copy(timerSeconds = when (it.timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }) } }
    } else add {
      ControlButton(null, "FRAME RATE", false, text = "${if (s.mode == CaptureMode.SLOW_MO) s.slowMoFps else s.frameRate}") { camera.cycleFrameRate() }
    }
    add { ControlButton(Icons.Rounded.Exposure, "EXPOSURE", s.evIndex != 0 || s.aeLock, onClick = onExposure) }
    add {
      ControlButton(Icons.Rounded.GridOn, "GRID", s.grid != GridStyle.OFF) {
        camera.update { it.copy(grid = GridStyle.entries[(it.grid.ordinal + 1) % GridStyle.entries.size]) }
      }
    }
    add { ControlButton(Icons.Rounded.Straighten, "LEVEL", s.level) { camera.update { it.copy(level = !it.level) } } }
    add { ControlButton(Icons.Rounded.Visibility, "MONITOR", s.peaking || s.zebras || s.falseColor || s.scope != Scope.NONE) { onPanel(Panel.MONITOR) } }
    add { ControlButton(Icons.Rounded.Palette, "LOOKS", s.look != "NATURAL" || s.lutName != null) { onPanel(Panel.LOOKS) } }
    add { ControlButton(Icons.Rounded.Settings, "SETTINGS", false, onClick = onSettings) }
  }

  Column(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = Space.s)
      .bounceEntrance()
      .floatingSurface(Shapes.l)
      .padding(vertical = Space.s),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Box(Modifier.size(width = 36.dp, height = 4.dp).clip(Shapes.pill).background(colors.onSurfaceVariant.copy(alpha = 0.5f)))
    if (s.mode == CaptureMode.PHOTO) {
      Spacer(Modifier.height(Space.xs))
      PhotoModes(camera)
    }
    Spacer(Modifier.height(Space.s))
    buttons.chunked(3).forEach { row ->
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        row.forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { it() } }
        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
      }
    }
  }
}

/** A round button with its name under it, for the controls sheet. */
@Composable
private fun ControlButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector?,
  label: String,
  active: Boolean,
  text: String? = null,
  onClick: () -> Unit
) {
  val colors = MaterialTheme.colorScheme
  val tint = if (active) colors.primary else colors.onSurface
  Column(
    Modifier
      .clip(Shapes.m)
      .clickable(role = Role.Button, onClick = onClick)
      .semantics { contentDescription = label.lowercase() }
      .padding(vertical = Space.s, horizontal = Space.xs),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Box(
      Modifier.size(52.dp).clip(CircleShape).background(if (active) colors.primary.copy(alpha = 0.18f) else colors.onSurface.copy(alpha = 0.10f)),
      contentAlignment = Alignment.Center
    ) {
      if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
      else Text(text.orEmpty(), style = AppText.Subhead.copy(fontFamily = JetBrainsMono, fontWeight = FontWeight.SemiBold), color = tint)
    }
    Spacer(Modifier.height(Space.xs))
    Text(label, style = AppText.Caption2.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp), color = colors.onSurface, maxLines = 1)
  }
}

@Composable
private fun PhotoModes(camera: TactsCamera) {
  val colors = MaterialTheme.colorScheme
  Row(
    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.s),
    horizontalArrangement = Arrangement.spacedBy(Space.xxs, Alignment.CenterHorizontally)
  ) {
    // Plain words, the chosen one in the accent.
    PhotoMode.entries.forEachIndexed { i, m ->
      val selected = camera.settings.photoMode == m
      Box(
        Modifier
          .bounceEntrance(i)
          .height(40.dp)
          .clip(Shapes.pill)
          .then(if (selected) Modifier.background(colors.onSurface.copy(alpha = 0.14f), Shapes.pill) else Modifier)
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
      .clip(CircleShape)
      .background(colors.surfaceContainer)
      .border(1.5.dp, colors.onSurface.copy(alpha = 0.6f), CircleShape)
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

