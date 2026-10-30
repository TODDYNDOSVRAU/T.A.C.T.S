package com.tacts.camera.ui.info

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tacts.camera.ui.components.FilterChip
import com.tacts.camera.ui.components.GroupDivider
import com.tacts.camera.ui.components.GroupHeader
import com.tacts.camera.ui.components.InfoRow
import com.tacts.camera.ui.components.InsetGroup
import com.tacts.camera.ui.components.SubPageHeader
import com.tacts.camera.ui.theme.Space
import com.tacts.camera.ui.theme.bounceEntrance

/**
 * Everything camera2 reports about each camera on the phone, logical and physical: the summary a
 * camera tester shows first (hardware level, capabilities, RAW, ranges), then every characteristic key
 * with its value.
 */
@Composable
fun CameraInfoPage(onBack: () -> Unit) {
  val context = LocalContext.current
  val cameras = remember { allCameras(context) }
  var selected by remember { mutableStateOf(cameras.firstOrNull()?.first) }
  val entries = remember(selected) { selected?.let { describe(context, it) } ?: emptyList() }

  LazyColumn(Modifier.fillMaxSize()) {
    item { SubPageHeader("Camera info", "Settings", onBack) }
    item {
      Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Space.page)) {
        cameras.forEachIndexed { i, (id, label) ->
          FilterChip(label, selected = id == selected, onClick = { selected = id }, modifier = Modifier.bounceEntrance(i).padding(end = Space.xs))
        }
      }
    }
    entries.forEach { (section, rows) ->
      item(key = "$selected-$section") { GroupHeader(section.uppercase()) }
      item(key = "$selected-$section-rows") {
        InsetGroup(Modifier.bounceEntrance()) {
          rows.forEachIndexed { i, (k, v) ->
            if (i > 0) GroupDivider()
            InfoRow(k, v, mono = section == "All keys")
          }
        }
      }
    }
    item { Spacer(Modifier.height(Space.section)) }
  }
}

/** Every camera id, including the physical lenses behind logical cameras, with a readable label. */
private fun allCameras(context: Context): List<Pair<String, String>> {
  val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
  val out = linkedMapOf<String, String>()
  manager.cameraIdList.forEach { id ->
    val c = manager.getCameraCharacteristics(id)
    out[id] = "$id · ${facing(c.get(CameraCharacteristics.LENS_FACING))}"
    if (android.os.Build.VERSION.SDK_INT >= 28) {
      c.physicalCameraIds.forEach { pid -> if (pid !in out) out[pid] = "$pid · physical" }
    }
  }
  return out.toList()
}

private fun facing(f: Int?) = when (f) {
  CameraCharacteristics.LENS_FACING_BACK -> "back"
  CameraCharacteristics.LENS_FACING_FRONT -> "front"
  CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
  else -> "?"
}

private fun describe(context: Context, id: String): List<Pair<String, List<Pair<String, String>>>> {
  val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
  val c = try {
    manager.getCameraCharacteristics(id)
  } catch (e: Exception) {
    return listOf("Error" to listOf("Could not read this camera" to (e.message ?: "")))
  }
  val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
  val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
  val summary = listOf(
    "Hardware level" to when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
      0 -> "LIMITED"; 1 -> "FULL"; 2 -> "LEGACY"; 3 -> "LEVEL 3"; 4 -> "EXTERNAL"; else -> "?"
    },
    "Capabilities" to caps.joinToString { capabilityName(it) },
    "RAW" to if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps) "Yes, ${map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height } ?: "?"}" else "No",
    "Manual sensor" to yes(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps),
    "Manual post-processing" to yes(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps),
    "ISO range" to "${c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: "–"}",
    "Exposure range" to (c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { "${it.lower} – ${it.upper} ns" } ?: "–"),
    "Focal lengths" to (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString { "%.2f mm".format(it) } ?: "–"),
    "Apertures" to (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString { "f/%.1f".format(it) } ?: "–"),
    "Sensor size" to "${c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: "–"} mm",
    "Pixel array" to "${c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: "–"}",
    "FPS ranges" to (c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString() ?: "–"),
    "High-speed video" to (try {
      map?.highSpeedVideoFpsRanges?.joinToString() ?: "–"
    } catch (e: Exception) {
      "–"
    })
  )
  val streams = map?.let { streamSummary(it) } ?: emptyList()
  val all = c.keys.sortedBy { it.name }.map { key ->
    @Suppress("UNCHECKED_CAST")
    val value = try {
      format(c.get(key as CameraCharacteristics.Key<Any>))
    } catch (e: Exception) {
      "?"
    }
    key.name.removePrefix("android.") to value
  }
  return listOf("Summary" to summary, "Streams" to streams, "All keys" to all)
}

private fun streamSummary(map: StreamConfigurationMap): List<Pair<String, String>> =
  map.outputFormats.map { f ->
    val sizes = map.getOutputSizes(f)?.sortedByDescending { it.width * it.height } ?: emptyList()
    formatName(f) to (sizes.take(6).joinToString() + if (sizes.size > 6) " … (${sizes.size})" else "")
  }

private fun formatName(f: Int) = when (f) {
  ImageFormat.JPEG -> "JPEG"
  ImageFormat.YUV_420_888 -> "YUV_420_888"
  ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
  ImageFormat.RAW10 -> "RAW10"
  ImageFormat.RAW12 -> "RAW12"
  ImageFormat.PRIVATE -> "PRIVATE"
  ImageFormat.DEPTH16 -> "DEPTH16"
  ImageFormat.DEPTH_POINT_CLOUD -> "DEPTH_POINT_CLOUD"
  ImageFormat.HEIC -> "HEIC"
  ImageFormat.YCBCR_P010 -> "P010 (10-bit)"
  else -> "0x%X".format(f)
}

private fun capabilityName(c: Int) = when (c) {
  0 -> "BACKWARD_COMPATIBLE"; 1 -> "MANUAL_SENSOR"; 2 -> "MANUAL_POST_PROCESSING"; 3 -> "RAW"
  4 -> "PRIVATE_REPROCESSING"; 5 -> "READ_SENSOR_SETTINGS"; 6 -> "BURST_CAPTURE"; 7 -> "YUV_REPROCESSING"
  8 -> "DEPTH_OUTPUT"; 9 -> "CONSTRAINED_HIGH_SPEED_VIDEO"; 10 -> "MOTION_TRACKING"; 11 -> "LOGICAL_MULTI_CAMERA"
  12 -> "MONOCHROME"; 13 -> "SECURE_IMAGE_DATA"; 14 -> "SYSTEM_CAMERA"; 15 -> "OFFLINE_PROCESSING"
  16 -> "ULTRA_HIGH_RESOLUTION_SENSOR"; 17 -> "REMOSAIC_REPROCESSING"; 18 -> "DYNAMIC_RANGE_TEN_BIT"
  19 -> "STREAM_USE_CASE"; 20 -> "COLOR_SPACE_PROFILES"
  else -> "#$c"
}

private fun yes(b: Boolean) = if (b) "Yes" else "No"

/** A characteristic value as text: arrays joined, nested values expanded, long lists cut. */
private fun format(v: Any?): String {
  val text = when (v) {
    null -> "null"
    is IntArray -> v.joinToString(prefix = "[", postfix = "]")
    is FloatArray -> v.joinToString(prefix = "[", postfix = "]")
    is LongArray -> v.joinToString(prefix = "[", postfix = "]")
    is BooleanArray -> v.joinToString(prefix = "[", postfix = "]")
    is ByteArray -> v.joinToString(prefix = "[", postfix = "]")
    is Array<*> -> v.joinToString(prefix = "[", postfix = "]") { format(it) }
    is StreamConfigurationMap -> "${v.outputFormats.size} output formats"
    else -> v.toString()
  }
  return if (text.length > 400) text.take(400) + " …" else text
}
