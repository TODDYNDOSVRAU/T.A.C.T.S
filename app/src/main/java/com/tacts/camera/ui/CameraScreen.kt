package com.tacts.camera.ui

import android.content.Intent
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GridOff
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Timer10
import androidx.compose.material.icons.rounded.Timer3
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import com.tacts.camera.camera.Flash
import com.tacts.camera.camera.TactsCamera
import com.tacts.camera.camera.VideoQuality
import com.tacts.camera.ui.components.FilterChip
import com.tacts.camera.ui.components.IconAction
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
import kotlin.math.roundToInt

private enum class Panel { NONE, PRO, LOOKS }

@Composable
fun CameraScreen(startMode: CaptureMode) {
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
    camera.changeMode(startMode)
    camera.attach(owner, previewView)
    onDispose { camera.release() }
  }

  var panel by remember { mutableStateOf(Panel.NONE) }
  var proTab by remember { mutableStateOf(ProTab.ISO) }
  var grid by remember { mutableStateOf(true) }
  var timer by remember { mutableIntStateOf(0) }
  var countdown by remember { mutableIntStateOf(0) }
  var focusPoint by remember { mutableStateOf<Offset?>(null) }
  var focusTick by remember { mutableIntStateOf(0) }
  // The capture blink: the frame dims for an instant, as the shutter closes. It is the camera's
  // answer to the shutter, not decoration, so it stays alongside the one animation T.A.C.T.S. keeps.
  val blink = remember { Animatable(0f) }

  LaunchedEffect(focusTick) {
    if (focusTick > 0) {
      delay(1200)
      focusPoint = null
    }
  }
  LaunchedEffect(camera.notice) {
    if (camera.notice != null) {
      delay(2800)
      camera.notice = null
    }
  }

  fun shoot() {
    if (camera.mode == CaptureMode.VIDEO) {
      camera.toggleRecording()
      return
    }
    if (countdown > 0 || camera.busy) return
    scope.launch {
      for (s in timer downTo 1) {
        countdown = s
        delay(1000)
      }
      countdown = 0
      camera.takePhoto()
      blink.snapTo(0.7f)
      blink.animateTo(0f, tween(220))
    }
  }

  Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Column(Modifier.fillMaxSize()) {
      TopBar(
        camera = camera,
        grid = grid,
        timer = timer,
        panel = panel,
        onGrid = { grid = !grid },
        onTimer = { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 } },
        onPanel = { panel = if (panel == it) Panel.NONE else it }
      )

      BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        val ratio = if (camera.mode == CaptureMode.PHOTO) 3f / 4f else 9f / 16f
        Box(
          Modifier
            .aspectRatio(ratio, matchHeightConstraintsFirst = maxWidth / maxHeight > ratio)
            .clip(Shapes.xs)
        ) {
          AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
          Box(
            Modifier
              .fillMaxSize()
              .pointerInput(Unit) {
                detectTapGestures { p ->
                  camera.focusAt(p.x, p.y)
                  focusPoint = p
                  focusTick++
                }
              }
              .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ -> camera.setZoom(camera.zoomRatio * zoom) }
              }
          )
          if (grid) GridOverlay()
          focusPoint?.let { FocusRing(it) }
          Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = blink.value)))
          if (panel == Panel.PRO) {
            camera.histogram?.let { Histogram(it, Modifier.align(Alignment.TopStart).padding(Space.m)) }
          }
          if (camera.isRecording) RecordingPill(camera.recordedSeconds, Modifier.align(Alignment.TopCenter).padding(Space.m))
          if (countdown > 0) {
            // Keyed on the number, so each second drops in with the bounce.
            androidx.compose.runtime.key(countdown) {
              Text(
                "$countdown",
                style = AppText.Title1.copy(fontFamily = SpaceGrotesk, fontSize = 104.sp, lineHeight = 110.sp, fontWeight = FontWeight.Medium),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).bounceEntrance()
              )
            }
          }
          camera.notice?.let {
            androidx.compose.runtime.key(it) {
              Text(
                it,
                style = AppText.Footnote,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                  .align(Alignment.TopCenter)
                  .padding(top = 56.dp, start = Space.l, end = Space.l)
                  .bounceEntrance()
                  .floatingSurface(Shapes.pill)
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
          ZoomChips(camera, Modifier.padding(bottom = Space.s))
          when (panel) {
            Panel.PRO -> ProPanel(camera, proTab, onTab = { proTab = it })
            Panel.LOOKS -> LookStrip(camera.look, onLook = { camera.changeLook(it) })
            Panel.NONE -> Unit
          }
        }
      }

      Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
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
          IconAction(
            Icons.Rounded.Cameraswitch,
            contentDescription = "Switch camera",
            onClick = { camera.switchLens() },
            enabled = camera.hasFrontAndBack && !camera.isRecording,
            filled = true,
            size = 52.dp,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.bounceEntrance(2)
          )
        }
      }
    }
  }
}

@Composable
private fun TopBar(
  camera: TactsCamera,
  grid: Boolean,
  timer: Int,
  panel: Panel,
  onGrid: () -> Unit,
  onTimer: () -> Unit,
  onPanel: (Panel) -> Unit
) {
  val colors = MaterialTheme.colorScheme
  fun tint(active: Boolean) = if (active) colors.primary else colors.onSurface
  Row(
    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Space.s, vertical = Space.xs),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    if (camera.caps.hasFlash) {
      IconAction(
        when (camera.flash) {
          Flash.OFF -> Icons.Rounded.FlashOff
          Flash.AUTO -> Icons.Rounded.FlashAuto
          Flash.ON -> Icons.Rounded.FlashOn
        },
        contentDescription = "Flash ${camera.flash.name.lowercase()}",
        onClick = { camera.cycleFlash() },
        tint = tint(camera.flash != Flash.OFF),
        modifier = Modifier.bounceEntrance(0)
      )
    } else {
      Spacer(Modifier.size(48.dp))
    }
    if (camera.mode == CaptureMode.PHOTO) {
      IconAction(
        when (timer) { 3 -> Icons.Rounded.Timer3; 10 -> Icons.Rounded.Timer10; else -> Icons.Rounded.TimerOff },
        contentDescription = if (timer == 0) "Timer off" else "Timer $timer seconds",
        onClick = onTimer,
        tint = tint(timer > 0),
        modifier = Modifier.bounceEntrance(1)
      )
    } else {
      TagToggle(camera.videoQuality.label, selected = false, index = 1) {
        val all = VideoQuality.entries
        camera.changeVideoQuality(all[(camera.videoQuality.ordinal + 1) % all.size])
      }
    }
    IconAction(
      if (grid) Icons.Rounded.GridOn else Icons.Rounded.GridOff,
      contentDescription = "Grid",
      onClick = onGrid,
      tint = tint(grid),
      modifier = Modifier.bounceEntrance(2)
    )
    IconAction(
      Icons.Rounded.Palette,
      contentDescription = "Looks",
      onClick = { onPanel(Panel.LOOKS) },
      tint = tint(panel == Panel.LOOKS || camera.look.ordinal != 0),
      modifier = Modifier.bounceEntrance(3)
    )
    if (camera.mode == CaptureMode.PHOTO && camera.caps.rawSupported) {
      TagToggle("RAW", selected = camera.manual.raw, index = 4) { camera.updateManual { it.copy(raw = !it.raw) } }
    }
    TagToggle("PRO", selected = panel == Panel.PRO || !camera.manual.isAllAuto, index = 5) { onPanel(Panel.PRO) }
  }
}

/** A short on/off label in the top bar (RAW, PRO, the video quality), on the quiet control fill. */
@Composable
private fun TagToggle(text: String, selected: Boolean, index: Int, onClick: () -> Unit) {
  Box(
    Modifier
      .size(width = 56.dp, height = 48.dp)
      .bounceEntrance(index)
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
private fun GridOverlay() {
  Canvas(Modifier.fillMaxSize()) {
    val c = Color.White.copy(alpha = 0.28f)
    for (i in 1..2) {
      val x = size.width * i / 3f
      val y = size.height * i / 3f
      drawLine(c, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
      drawLine(c, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
  }
}

@Composable
private fun FocusRing(at: Offset) {
  val half = with(LocalDensity.current) { 36.dp.toPx() }
  // Keyed on the point, so every tap drops a fresh ring in with the bounce.
  androidx.compose.runtime.key(at) {
    Box(
      Modifier
        .offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }
        .bounceEntrance()
        .size(72.dp)
        .border(1.5.dp, MaterialTheme.colorScheme.primary, Shapes.xs)
    )
  }
}

@Composable
private fun Histogram(bins: FloatArray, modifier: Modifier) {
  val bar = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
  Canvas(
    modifier
      .bounceEntrance()
      .size(width = 128.dp, height = 52.dp)
      .floatingSurface(Shapes.s)
      .padding(Space.xs)
  ) {
    val w = size.width / bins.size
    bins.forEachIndexed { i, v ->
      val h = v * size.height
      drawRect(bar, topLeft = Offset(i * w, size.height - h), size = Size(w + 0.5f, h))
    }
  }
}

@Composable
private fun RecordingPill(seconds: Long, modifier: Modifier) {
  val colors = MaterialTheme.colorScheme
  Row(
    modifier
      .bounceEntrance()
      .clip(Shapes.pill)
      .background(colors.error)
      .padding(horizontal = Space.m, vertical = Space.xs),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(colors.onError))
    Spacer(Modifier.width(Space.s))
    Text(
      "%02d:%02d".format(seconds / 60, seconds % 60),
      style = AppText.Subhead.copy(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium),
      color = colors.onError
    )
  }
}

@Composable
private fun ZoomChips(camera: TactsCamera, modifier: Modifier) {
  val presets = buildList {
    if (camera.minZoom < 0.95f) add(camera.minZoom)
    listOf(1f, 2f, 5f, 10f).filter { it <= camera.maxZoom + 0.01f }.forEach { add(it) }
  }
  if (presets.size < 2) return
  val colors = MaterialTheme.colorScheme
  val active = presets.lastOrNull { it <= camera.zoomRatio + 0.01f } ?: presets.first()
  Row(
    modifier.bounceEntrance().floatingSurface(Shapes.pill).padding(Space.xs),
    horizontalArrangement = Arrangement.spacedBy(Space.xs)
  ) {
    presets.forEachIndexed { i, p ->
      val selected = p == active
      val shown = if (selected) camera.zoomRatio else p
      val text = if (shown < 1f || shown % 1f > 0.05f) "%.1f".format(shown) else "${shown.roundToInt()}"
      Box(
        Modifier
          .bounceEntrance(i)
          .size(40.dp)
          .glassControl(CircleShape, selected)
          .clickable(role = Role.Button) { camera.setZoom(p) }
          .semantics { contentDescription = "Zoom $text times" },
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
private fun ModeSwitch(camera: TactsCamera) {
  Row(
    Modifier.fillMaxWidth().padding(top = Space.s),
    horizontalArrangement = Arrangement.spacedBy(Space.xs, Alignment.CenterHorizontally)
  ) {
    CaptureMode.entries.forEachIndexed { i, m ->
      FilterChip(
        label = if (m == CaptureMode.PHOTO) "Photo" else "Video",
        selected = camera.mode == m,
        onClick = { camera.changeMode(m) },
        enabled = !camera.isRecording,
        modifier = Modifier.bounceEntrance(i)
      )
    }
  }
}

@Composable
private fun ShutterButton(camera: TactsCamera, waiting: Boolean, modifier: Modifier, onClick: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  val video = camera.mode == CaptureMode.VIDEO
  val recording = camera.isRecording
  Box(
    modifier
      .size(78.dp)
      .border(4.dp, colors.onSurface, CircleShape)
      .clip(CircleShape)
      .clickable(enabled = !waiting, role = Role.Button, onClick = onClick)
      .semantics { contentDescription = if (video) (if (recording) "Stop recording" else "Record") else "Take photo" },
    contentAlignment = Alignment.Center
  ) {
    Box(
      Modifier
        .size(if (recording) 28.dp else 62.dp)
        .clip(if (recording) RoundedCornerShape(6.dp) else CircleShape)
        .background(
          when {
            video -> colors.error
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
      // Keyed on the picture, so each new capture drops into place.
      androidx.compose.runtime.key(thumb) {
        Image(
          thumb.asImageBitmap(),
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize().bounceEntrance()
        )
      }
    } else if (camera.lastCapture != null && camera.lastIsVideo) {
      Icon(Icons.Rounded.Videocam, contentDescription = null, tint = colors.onSurface)
    }
  }
}
