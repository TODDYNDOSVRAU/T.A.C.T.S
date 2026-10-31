package com.tacts.camera.photo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.tacts.camera.effects.Lut
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves a merged photo: look and LUT applied in place, the reference frame's EXIF kept, orientation as a tag. */
object PhotoOutput {

  /**
   * The colour matrix of a look (4×5, 0..255 space) and then the LUT, on the bitmap itself, 64 rows at a
   * time: a merged 50 MP picture has no room for a second copy.
   */
  fun applyInPlace(bitmap: Bitmap, matrix: FloatArray?, lut: Lut?) {
    if (matrix == null && lut == null) return
    val w = bitmap.width
    val h = bitmap.height
    val rows = 64
    val buf = IntArray(w * rows)
    val rgb = FloatArray(3)
    var y = 0
    while (y < h) {
      val n = minOf(rows, h - y)
      bitmap.getPixels(buf, 0, w, 0, y, w, n)
      for (k in 0 until w * n) {
        val p = buf[k]
        var r = ((p shr 16) and 0xFF).toFloat()
        var g = ((p shr 8) and 0xFF).toFloat()
        var b = (p and 0xFF).toFloat()
        if (matrix != null) {
          val m = matrix
          val nr = m[0] * r + m[1] * g + m[2] * b + m[3] * 255f + m[4]
          val ng = m[5] * r + m[6] * g + m[7] * b + m[8] * 255f + m[9]
          val nb = m[10] * r + m[11] * g + m[12] * b + m[13] * 255f + m[14]
          r = nr.coerceIn(0f, 255f)
          g = ng.coerceIn(0f, 255f)
          b = nb.coerceIn(0f, 255f)
        }
        if (lut != null) {
          lut.map(r / 255f, g / 255f, b / 255f, rgb)
          r = rgb[0] * 255f
          g = rgb[1] * 255f
          b = rgb[2] * 255f
        }
        buf[k] = (0xFF shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
      }
      bitmap.setPixels(buf, 0, w, 0, y, w, n)
      y += n
    }
  }

  private fun to8(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)

  /** The EXIF orientation that shows a sensor-oriented picture upright: rotation, then a mirror for the front camera. */
  fun exifOrientation(rotation: Int, mirror: Boolean): Int = when ((rotation % 360 + 360) % 360) {
    90 -> if (mirror) ExifInterface.ORIENTATION_TRANSPOSE else ExifInterface.ORIENTATION_ROTATE_90
    180 -> if (mirror) ExifInterface.ORIENTATION_FLIP_VERTICAL else ExifInterface.ORIENTATION_ROTATE_180
    270 -> if (mirror) ExifInterface.ORIENTATION_TRANSVERSE else ExifInterface.ORIENTATION_ROTATE_270
    else -> if (mirror) ExifInterface.ORIENTATION_FLIP_HORIZONTAL else ExifInterface.ORIENTATION_NORMAL
  }

  private val COPIED_TAGS = listOf(
    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
    ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_SHUTTER_SPEED_VALUE,
    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF
  )

  /**
   * Writes the JPEG to the app's cache, tags it, then copies it into Pictures/TACTS. [description]
   * says how the picture was made ("Night, 8 frames"), so the file does not pass for a single shot.
   */
  fun save(context: Context, bitmap: Bitmap, name: String, quality: Int, reference: ByteArray, orientation: Int, description: String): Uri? {
    val file = File(context.cacheDir, "$name.jpg")
    try {
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
      val exif = ExifInterface(file.absolutePath)
      try {
        val src = ExifInterface(ByteArrayInputStream(reference))
        for (tag in COPIED_TAGS) src.getAttribute(tag)?.let { exif.setAttribute(tag, it) }
      } catch (_: Exception) {
      }
      if (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == null) {
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date()))
      }
      exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
      exif.setAttribute(ExifInterface.TAG_SOFTWARE, "T.A.C.T.S.")
      exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description)
      exif.saveAttributes()

      val resolver = context.contentResolver
      val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TACTS")
      }
      val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
      return try {
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("no output stream")
        uri
      } catch (e: Exception) {
        resolver.delete(uri, null, null)
        null
      }
    } catch (e: Exception) {
      android.util.Log.w("TACTS", "saving merged photo failed", e)
      return null
    } finally {
      file.delete()
    }
  }
}
