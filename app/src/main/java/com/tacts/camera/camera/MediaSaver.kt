package com.tacts.camera.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.camera.core.ImageCapture
import androidx.camera.video.MediaStoreOutputOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything is saved to the shared gallery: Pictures/TACTS and Movies/TACTS. */
object MediaSaver {
  private const val FOLDER = "TACTS"

  fun newName(): String =
    "TACTS_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

  private fun values(name: String, mime: String, dir: String) = ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
    put(MediaStore.MediaColumns.MIME_TYPE, mime)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$FOLDER")
    }
  }

  fun photoOptions(context: Context, name: String, mime: String = "image/jpeg") =
    ImageCapture.OutputFileOptions.Builder(
      context.contentResolver,
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
      values(name, mime, Environment.DIRECTORY_PICTURES)
    ).build()

  fun videoOptions(context: Context, name: String) =
    MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
      .setContentValues(values(name, "video/mp4", Environment.DIRECTORY_MOVIES))
      .build()

  /** Saves an already processed photo (one with a look applied) as JPEG. */
  fun saveBitmap(context: Context, bitmap: Bitmap, name: String, quality: Int = 95): Uri? {
    val resolver = context.contentResolver
    val uri = resolver.insert(
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
      values(name, "image/jpeg", Environment.DIRECTORY_PICTURES)
    ) ?: return null
    return try {
      resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
      uri
    } catch (e: Exception) {
      resolver.delete(uri, null, null)
      null
    }
  }

  /** A small preview of the last capture for the gallery button. */
  fun thumbnail(context: Context, uri: Uri): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      context.contentResolver.loadThumbnail(uri, Size(192, 192), null)
    } else {
      context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 16 })
      }
    }
  } catch (e: Exception) {
    null
  }
}
