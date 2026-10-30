package com.tacts.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tacts.camera.camera.CaptureMode
import com.tacts.camera.ui.CameraScreen
import com.tacts.camera.ui.components.PrimaryButton
import com.tacts.camera.ui.components.TextAction
import com.tacts.camera.ui.theme.AppMotion
import com.tacts.camera.ui.theme.AppText
import com.tacts.camera.ui.theme.JetBrainsMono
import com.tacts.camera.ui.theme.Space
import com.tacts.camera.ui.theme.TactsTheme
import com.tacts.camera.ui.theme.bounceEntrance

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    AppMotion.readSystem(this)
    val startMode = if (intent?.action == android.provider.MediaStore.INTENT_ACTION_VIDEO_CAMERA) CaptureMode.VIDEO
    else CaptureMode.PHOTO
    setContent { TactsTheme { PermissionGate { CameraScreen(startMode) } } }
  }

  override fun onResume() {
    super.onResume()
    // The system animation setting may have changed while the app was away.
    AppMotion.readSystem(this)
  }
}

private val requiredPermissions = buildList {
  add(Manifest.permission.CAMERA)
  add(Manifest.permission.RECORD_AUDIO)
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}.toTypedArray()

/** Asks for the camera (plus microphone and, before Android 10, storage) before showing the camera. */
@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
  val context = LocalContext.current
  fun cameraGranted() =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

  var granted by remember { mutableStateOf(cameraGranted()) }
  var asked by remember { mutableStateOf(false) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    asked = true
    granted = cameraGranted()
  }
  LaunchedEffect(Unit) { if (!granted) launcher.launch(requiredPermissions) }
  // Coming back from the app settings page with the permission turned on.
  LifecycleResumeEffect(Unit) {
    granted = cameraGranted()
    onPauseOrDispose { }
  }

  if (granted) {
    content()
    return
  }
  val colors = MaterialTheme.colorScheme
  Column(
    modifier = Modifier.fillMaxSize().background(colors.background).padding(horizontal = Space.xxl),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text("T.A.C.T.S.", style = AppText.LargeTitle, color = colors.onBackground, modifier = Modifier.bounceEntrance(0))
    Spacer(Modifier.height(Space.xs))
    Text(
      "TOTTALLY ACCURATE CAMERA TECHNOLOGY SYSTEM",
      style = AppText.Caption2.copy(fontFamily = JetBrainsMono, letterSpacing = 0.08.em),
      color = colors.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.bounceEntrance(1)
    )
    Spacer(Modifier.height(Space.section))
    Text(
      "The camera needs access to the camera. The microphone is used for sound in videos.",
      style = AppText.Body,
      color = colors.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.bounceEntrance(2)
    )
    Spacer(Modifier.height(Space.xl))
    PrimaryButton(
      "Allow camera",
      onClick = { launcher.launch(requiredPermissions) },
      icon = Icons.Rounded.PhotoCamera,
      modifier = Modifier.fillMaxWidth().bounceEntrance(3)
    )
    if (asked) {
      TextAction(
        "Open app settings",
        onClick = {
          context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
          )
        },
        modifier = Modifier.bounceEntrance(4)
      )
    }
  }
}
