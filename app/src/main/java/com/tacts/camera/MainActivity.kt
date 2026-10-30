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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tacts.camera.camera.CaptureMode
import com.tacts.camera.ui.CameraScreen
import com.tacts.camera.ui.Ink
import com.tacts.camera.ui.TactsTheme
import com.tacts.camera.ui.TextPrimary
import com.tacts.camera.ui.TextSecondary

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val startMode = if (intent?.action == android.provider.MediaStore.INTENT_ACTION_VIDEO_CAMERA) CaptureMode.VIDEO
    else CaptureMode.PHOTO
    setContent { TactsTheme { PermissionGate { CameraScreen(startMode) } } }
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
  Column(
    modifier = Modifier.fillMaxSize().background(Ink).padding(32.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text("T.A.C.T.S.", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Text(
      "TOTTALLY ACCURATE CAMERA TECHNOLOGY SYSTEM",
      color = TextSecondary,
      fontSize = 11.sp,
      letterSpacing = 1.sp,
      textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(32.dp))
    Text(
      "The camera needs access to the camera. The microphone is used for sound in videos.",
      color = TextSecondary,
      textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = { launcher.launch(requiredPermissions) }) { Text("Allow camera") }
    if (asked) {
      TextButton(onClick = {
        context.startActivity(
          Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        )
      }) { Text("Open app settings") }
    }
  }
}
