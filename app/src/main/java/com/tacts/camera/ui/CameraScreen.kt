package com.tacts.camera.ui

import android.content.Intent
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Timer10
import androidx.compose.material.icons.filled.Timer3
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
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

  Box(Modifier.fillMaxSize().background(Color.Black)) {
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
            .clip(RoundedCornerShape(4.dp))
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
            camera.histogram?.let { Histogram(it, Modifier.align(Alignment.TopStart).padding(10.dp)) }
          }
          if (camera.isRecording) RecordingPill(camera.recordedSeconds, Modifier.align(Alignment.TopCenter).padding(10.dp))
          if (countdown > 0) {
            Text(
              "$countdown",
              color = Color.White,
              fontSize = 96.sp,
              fontWeight = FontWeight.Thin,
              modifier = Modifier.align(Alignment.Center)
            )
          }
          camera.notice?.let {
            Text(
              it,
              color = TextPrimary,
              fontSize = 13.sp,
              modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 52.dp)
                .clip(CircleShape)
                .background(Ink.copy(alpha = 0.8f))
                .padding(horizontal = 14.dp, vertical = 8.dp)
            )
          }
        }
        // Panels float over the bottom of the frame, so opening one never resizes the preview.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
          ZoomChips(camera, Modifier.padding(bottom = 12.dp))
          AnimatedVisibility(panel == Panel.PRO, enter = fadeIn(), exit = fadeOut()) {
            ProPanel(camera, proTab) { proTab = it }
          }
          AnimatedVisibility(panel == Panel.LOOKS, enter = fadeIn(), exit = fadeOut()) {
            LookStrip(camera.look) { camera.changeLook(it) }
          }
        }
      }

      Column(Modifier.fillMaxWidth().background(Color.Black).navigationBarsPadding()) {
        ModeSwitch(camera)
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          LastCapture(camera) {
            camera.lastCapture?.let { uri ->
              val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, if (camera.lastIsVideo) "video/*" else "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              runCatching { context.startActivity(intent) }
            }
          }
          ShutterButton(camera, countdown > 0) { shoot() }
          RoundIcon(Icons.Filled.Cameraswitch, enabled = camera.hasFrontAndBack && !camera.isRecording) {
            camera.switchLens()
          }
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
  Row(
    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    if (camera.caps.hasFlash) {
      TopIcon(
        when (camera.flash) {
          Flash.OFF -> Icons.Filled.FlashOff
          Flash.AUTO -> Icons.Filled.FlashAuto
          Flash.ON -> Icons.Filled.FlashOn
        },
        active = camera.flash != Flash.OFF,
        onClick = { camera.cycleFlash() }
      )
    } else {
      Spacer(Modifier.size(40.dp))
    }
    if (camera.mode == CaptureMode.PHOTO) {
      TopIcon(
        when (timer) { 3 -> Icons.Filled.Timer3; 10 -> Icons.Filled.Timer10; else -> Icons.Filled.TimerOff },
        active = timer > 0,
        onClick = onTimer
      )
    } else {
      TopText(camera.videoQuality.label, active = false) {
        val all = VideoQuality.entries
        camera.changeVideoQuality(all[(camera.videoQuality.ordinal + 1) % all.size])
      }
    }
    TopIcon(if (grid) Icons.Filled.GridOn else Icons.Filled.GridOff, active = grid, onClick = onGrid)
    TopIcon(Icons.Filled.Palette, active = panel == Panel.LOOKS || camera.look.ordinal != 0) { onPanel(Panel.LOOKS) }
    if (camera.mode == CaptureMode.PHOTO && camera.caps.rawSupported) {
      TopText("RAW", active = camera.manual.raw) { camera.updateManual { it.copy(raw = !it.raw) } }
    }
    TopText("PRO", active = panel == Panel.PRO || !camera.manual.isAllAuto) { onPanel(Panel.PRO) }
  }
}

@Composable
private fun TopIcon(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
  Box(
    Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    Icon(icon, contentDescription = null, tint = if (active) Amber else TextPrimary, modifier = Modifier.size(22.dp))
  }
}

@Composable
private fun TopText(text: String, active: Boolean, onClick: () -> Unit) {
  Text(
    text,
    color = if (active) Ink else TextPrimary,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 1.sp,
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(if (active) Amber else Color.Transparent)
      .border(1.dp, if (active) Amber else TextSecondary, RoundedCornerShape(6.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 4.dp)
  )
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
  Box(
    Modifier
      .offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }
      .size(72.dp)
      .border(1.5.dp, Amber, RoundedCornerShape(6.dp))
  )
}

@Composable
private fun Histogram(bins: FloatArray, modifier: Modifier) {
  Canvas(
    modifier
      .size(width = 128.dp, height = 52.dp)
      .clip(RoundedCornerShape(6.dp))
      .background(Ink.copy(alpha = 0.55f))
      .padding(4.dp)
  ) {
    val w = size.width / bins.size
    bins.forEachIndexed { i, v ->
      val h = v * size.height
      drawRect(
        Color.White.copy(alpha = 0.75f),
        topLeft = Offset(i * w, size.height - h),
        size = Size(w + 0.5f, h)
      )
    }
  }
}

@Composable
private fun RecordingPill(seconds: Long, modifier: Modifier) {
  Row(
    modifier
      .clip(CircleShape)
      .background(Record)
      .padding(horizontal = 12.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
    Spacer(Modifier.width(6.dp))
    Text(
      "%02d:%02d".format(seconds / 60, seconds % 60),
      color = Color.White,
      fontSize = 14.sp,
      fontFamily = FontFamily.Monospace
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
  val active = presets.lastOrNull { it <= camera.zoomRatio + 0.01f } ?: presets.first()
  Row(
    modifier.clip(CircleShape).background(Ink.copy(alpha = 0.55f)).padding(4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    presets.forEach { p ->
      val selected = p == active
      val shown = if (selected) camera.zoomRatio else p
      val text = if (shown < 1f || shown % 1f > 0.05f) "%.1f".format(shown) else "${shown.roundToInt()}"
      val size by animateDpAsState(if (selected) 40.dp else 32.dp, label = "zoom")
      Box(
        Modifier
          .size(size)
          .clip(CircleShape)
          .background(if (selected) InkHigh else Color.Transparent)
          .clickable { camera.setZoom(p) },
        contentAlignment = Alignment.Center
      ) {
        Text(
          if (selected) "$text×" else text,
          color = if (selected) Amber else TextPrimary,
          fontSize = if (selected) 12.sp else 11.sp,
          fontWeight = FontWeight.SemiBold
        )
      }
    }
  }
}

@Composable
private fun ModeSwitch(camera: TactsCamera) {
  Row(
    Modifier.fillMaxWidth().padding(top = 12.dp),
    horizontalArrangement = Arrangement.Center
  ) {
    CaptureMode.entries.forEach { m ->
      Text(
        m.name,
        color = if (camera.mode == m) Amber else TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier
          .clip(CircleShape)
          .clickable(enabled = !camera.isRecording) { camera.changeMode(m) }
          .padding(horizontal = 16.dp, vertical = 6.dp)
      )
    }
  }
}

@Composable
private fun ShutterButton(camera: TactsCamera, waiting: Boolean, onClick: () -> Unit) {
  val video = camera.mode == CaptureMode.VIDEO
  val inner by animateDpAsState(if (camera.isRecording) 28.dp else 62.dp, label = "shutter")
  val corner by animateDpAsState(if (camera.isRecording) 6.dp else 31.dp, label = "corner")
  Box(
    Modifier
      .size(78.dp)
      .border(4.dp, Color.White, CircleShape)
      .clip(CircleShape)
      .clickable(enabled = !waiting) { onClick() },
    contentAlignment = Alignment.Center
  ) {
    Box(
      Modifier
        .size(inner)
        .clip(RoundedCornerShape(corner))
        .background(
          when {
            video -> Record
            camera.busy || waiting -> Color.White.copy(alpha = 0.5f)
            else -> Color.White
          }
        )
    )
  }
}

@Composable
private fun LastCapture(camera: TactsCamera, onClick: () -> Unit) {
  Box(
    Modifier
      .size(52.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(InkHigh)
      .border(1.5.dp, TextPrimary.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
      .clickable(enabled = camera.lastCapture != null, onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    val thumb = camera.lastThumb
    if (thumb != null) {
      Image(thumb.asImageBitmap(), contentDescription = "Last capture", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else if (camera.lastCapture != null && camera.lastIsVideo) {
      Icon(Icons.Filled.Videocam, contentDescription = null, tint = TextPrimary)
    }
  }
}

@Composable
private fun RoundIcon(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
  Box(
    Modifier
      .size(52.dp)
      .clip(CircleShape)
      .background(InkHigh)
      .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    Icon(icon, contentDescription = "Switch camera", tint = if (enabled) TextPrimary else TextSecondary.copy(alpha = 0.4f))
  }
}
