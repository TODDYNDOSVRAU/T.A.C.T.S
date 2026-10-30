package com.tacts.camera.filters

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * The colour looks. Each one is a single 4x5 colour matrix, so the same numbers tint the live preview
 * (as a layer paint on the preview view) and the saved photo (drawn once through a paint), and what
 * you see is what you get.
 */
enum class Look(val label: String) {
  NATURAL("Natural"),
  VIVID("Vivid"),
  WARM("Warm"),
  COOL("Cool"),
  FADE("Fade"),
  MONO("Mono"),
  NOIR("Noir"),
  SEPIA("Sepia");

  /** Null for [NATURAL]: nothing is drawn through a filter. */
  fun matrix(): ColorMatrix? = when (this) {
    NATURAL -> null
    VIVID -> saturation(1.45f).then(contrast(1.1f))
    WARM -> scale(1.08f, 1.0f, 0.86f).then(saturation(1.1f))
    COOL -> scale(0.9f, 1.0f, 1.1f)
    FADE -> saturation(0.75f).then(contrast(0.8f)).then(lift(18f))
    MONO -> saturation(0f)
    NOIR -> saturation(0f).then(contrast(1.4f))
    SEPIA -> saturation(0f).then(
      ColorMatrix(
        floatArrayOf(
          0.393f, 0.769f, 0.189f, 0f, 0f,
          0.349f, 0.686f, 0.168f, 0f, 0f,
          0.272f, 0.534f, 0.131f, 0f, 0f,
          0f, 0f, 0f, 1f, 0f
        )
      )
    )
  }

  fun paint(): Paint? = matrix()?.let { Paint().apply { colorFilter = ColorMatrixColorFilter(it) } }
}

/** Draws [source] through the look. Returns [source] itself for [Look.NATURAL]. */
fun Look.applyTo(source: Bitmap): Bitmap {
  val paint = paint() ?: return source
  val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
  Canvas(out).drawBitmap(source, 0f, 0f, paint)
  return out
}

private fun saturation(s: Float) = ColorMatrix().apply { setSaturation(s) }

private fun scale(r: Float, g: Float, b: Float) = ColorMatrix().apply { setScale(r, g, b, 1f) }

// Contrast around mid grey (128 in the 0..255 space the matrix works in).
private fun contrast(c: Float): ColorMatrix {
  val t = 128f * (1f - c)
  return ColorMatrix(
    floatArrayOf(
      c, 0f, 0f, 0f, t,
      0f, c, 0f, 0f, t,
      0f, 0f, c, 0f, t,
      0f, 0f, 0f, 1f, 0f
    )
  )
}

private fun lift(amount: Float) = ColorMatrix(
  floatArrayOf(
    1f, 0f, 0f, 0f, amount,
    0f, 1f, 0f, 0f, amount,
    0f, 0f, 1f, 0f, amount,
    0f, 0f, 0f, 1f, 0f
  )
)

/** This matrix followed by [next]. */
private fun ColorMatrix.then(next: ColorMatrix) = ColorMatrix(next).also { it.preConcat(this) }
