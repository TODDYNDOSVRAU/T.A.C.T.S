package com.tacts.camera.effects

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tacts.camera.camera.GammaProfile
import com.tacts.camera.camera.RequestOptions
import java.io.File
import kotlin.math.pow

/**
 * A 3D LUT: [size]³ RGB entries, red changing fastest, then green, then blue (the .cube order),
 * values 0..1.
 */
class Lut(val name: String, val size: Int, val data: FloatArray) {

  /** Trilinear lookup of one colour (0..1 per channel). */
  fun map(r: Float, g: Float, b: Float, out: FloatArray) {
    val n = size - 1
    val fr = r * n
    val fg = g * n
    val fb = b * n
    val r0 = fr.toInt().coerceIn(0, n)
    val g0 = fg.toInt().coerceIn(0, n)
    val b0 = fb.toInt().coerceIn(0, n)
    val r1 = minOf(r0 + 1, n)
    val g1 = minOf(g0 + 1, n)
    val b1 = minOf(b0 + 1, n)
    val dr = fr - r0
    val dg = fg - g0
    val db = fb - b0
    for (c in 0 until 3) {
      fun at(ri: Int, gi: Int, bi: Int) = data[((bi * size + gi) * size + ri) * 3 + c]
      val c00 = at(r0, g0, b0) * (1 - dr) + at(r1, g0, b0) * dr
      val c10 = at(r0, g1, b0) * (1 - dr) + at(r1, g1, b0) * dr
      val c01 = at(r0, g0, b1) * (1 - dr) + at(r1, g0, b1) * dr
      val c11 = at(r0, g1, b1) * (1 - dr) + at(r1, g1, b1) * dr
      out[c] = (c00 * (1 - dg) + c10 * dg) * (1 - db) + (c01 * (1 - dg) + c11 * dg) * db
    }
  }

  /** The photo through this LUT, on the CPU (the GPU effect only covers preview and video). */
  fun applyTo(source: Bitmap): Bitmap {
    val w = source.width
    val h = source.height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val row = IntArray(w)
    val rgb = FloatArray(3)
    for (y in 0 until h) {
      source.getPixels(row, 0, w, 0, y, w, 1)
      for (x in 0 until w) {
        val p = row[x]
        map(((p shr 16) and 0xFF) / 255f, ((p shr 8) and 0xFF) / 255f, (p and 0xFF) / 255f, rgb)
        row[x] = (0xFF shl 24) or (to8(rgb[0]) shl 16) or (to8(rgb[1]) shl 8) or to8(rgb[2])
      }
      out.setPixels(row, 0, w, 0, y, w, 1)
    }
    return out
  }

  private fun to8(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

  companion object {
    /** Reads an Adobe/Resolve .cube file (3D only). */
    fun parseCube(name: String, text: String): Lut {
      var size = 0
      val values = ArrayList<Float>()
      var minIn = 0f
      var maxIn = 1f
      text.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        val parts = line.split(Regex("\\s+"))
        when {
          parts[0] == "LUT_3D_SIZE" -> size = parts[1].toInt()
          parts[0] == "DOMAIN_MIN" -> minIn = parts[1].toFloat()
          parts[0] == "DOMAIN_MAX" -> maxIn = parts[1].toFloat()
          parts[0] == "TITLE" || parts[0].startsWith("LUT_1D") -> Unit
          parts.size >= 3 && parts[0].first().let { it.isDigit() || it == '-' || it == '.' } ->
            parts.take(3).forEach { values.add(it.toFloat()) }
        }
      }
      require(size >= 2 && values.size == size * size * size * 3) { "Not a 3D .cube LUT" }
      val span = (maxIn - minIn).takeIf { it > 0f } ?: 1f
      return Lut(name, size, FloatArray(values.size) { ((values[it] - minIn) / span).coerceIn(0f, 1f) })
    }

    /** Builds a LUT from a colour function. */
    fun generate(name: String, size: Int = 33, f: (Float, Float, Float) -> Triple<Float, Float, Float>): Lut {
      val data = FloatArray(size * size * size * 3)
      for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
        val (rr, gg, bb) = f(r / (size - 1f), g / (size - 1f), b / (size - 1f))
        val i = ((b * size + g) * size + r) * 3
        data[i] = rr.coerceIn(0f, 1f)
        data[i + 1] = gg.coerceIn(0f, 1f)
        data[i + 2] = bb.coerceIn(0f, 1f)
      }
      return Lut(name, size, data)
    }
  }
}

/**
 * The LUTs on offer: built-in ones, made here from formulas (so nothing is copied from anyone), and
 * the .cube files the user imported, kept in the app's own storage.
 */
class LutLibrary(private val context: Context) {
  private val dir = File(context.filesDir, "luts").apply { mkdirs() }

  fun names(): List<String> = BUILT_IN.keys.toList() + (dir.listFiles()?.filter { it.extension.equals("cube", true) }
    ?.map { it.nameWithoutExtension }?.sorted() ?: emptyList())

  fun isBuiltIn(name: String) = name in BUILT_IN

  fun load(name: String?): Lut? {
    if (name == null) return null
    BUILT_IN[name]?.let { return it() }
    val file = File(dir, "$name.cube")
    return if (file.exists()) runCatching { Lut.parseCube(name, file.readText()) }.getOrNull() else null
  }

  /** Copies a .cube file the user picked into the library. Returns its name. */
  fun import(uri: Uri): String {
    val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
      ?: error("Could not read the file")
    val base = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.ifBlank { null } ?: "Imported"
    val name = base.replace(Regex("[^A-Za-z0-9 _.-]"), "_").take(40)
    Lut.parseCube(name, text)
    File(dir, "$name.cube").writeText(text)
    return name
  }

  fun delete(name: String) {
    File(dir, "$name.cube").delete()
  }

  companion object {
    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b
    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun smooth(x: Float) = x * x * (3f - 2f * x)

    /** Undoes one of the app's own log curves into the standard picture, for monitoring or burn-in. */
    private fun logToStandard(profile: GammaProfile): (Float, Float, Float) -> Triple<Float, Float, Float> {
      // Invert the transfer by table lookup.
      val table = FloatArray(1024) { i -> RequestOptions.transfer(profile, i / 1023f) }
      fun inverse(v: Float): Float {
        var lo = 0
        var hi = table.size - 1
        while (lo < hi) {
          val mid = (lo + hi) / 2
          if (table[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo / 1023f
      }
      return { r, g, b ->
        Triple(
          RequestOptions.transfer(GammaProfile.STANDARD, inverse(r)),
          RequestOptions.transfer(GammaProfile.STANDARD, inverse(g)),
          RequestOptions.transfer(GammaProfile.STANDARD, inverse(b))
        )
      }
    }

    val BUILT_IN: Map<String, () -> Lut> = linkedMapOf(
      "Log to Rec.709" to { Lut.generate("Log to Rec.709", 33, logToStandard(GammaProfile.LOG)) },
      "Log HC to Rec.709" to { Lut.generate("Log HC to Rec.709", 33, logToStandard(GammaProfile.LOG_HC)) },
      "Flat to Rec.709" to { Lut.generate("Flat to Rec.709", 33, logToStandard(GammaProfile.FLAT)) },
      "Teal & orange" to {
        Lut.generate("Teal & orange") { r, g, b ->
          val l = luma(r, g, b)
          // Shadows towards teal, highlights and skin towards orange, a little more contrast.
          val t = smooth(l)
          val c = { v: Float -> ((v - 0.5f) * 1.12f + 0.5f) }
          Triple(c(mix(r * 0.9f, r * 1.08f + 0.03f, t)), c(mix(g * 1.02f + 0.02f, g * 0.98f, t)), c(mix(b * 1.1f + 0.04f, b * 0.82f, t)))
        }
      },
      "Bleach bypass" to {
        Lut.generate("Bleach bypass") { r, g, b ->
          val l = luma(r, g, b)
          val c = { v: Float -> smooth(((v - 0.5f) * 1.25f + 0.5f).coerceIn(0f, 1f)) }
          Triple(c(mix(l, r, 0.45f)), c(mix(l, g, 0.45f)), c(mix(l, b, 0.45f)))
        }
      },
      "Warm film" to {
        Lut.generate("Warm film") { r, g, b ->
          // Lifted blacks, rolled-off highlights, a warm cast.
          val curve = { v: Float -> 0.04f + 0.92f * (1f - (1f - v).pow(1.35f)) }
          Triple(curve(r * 1.04f), curve(g), curve(b * 0.9f))
        }
      },
      "Cold steel" to {
        Lut.generate("Cold steel") { r, g, b ->
          val l = luma(r, g, b)
          Triple(mix(l, r, 0.6f) * 0.92f, mix(l, g, 0.6f) * 0.98f, (mix(l, b, 0.6f) * 1.1f + 0.03f))
        }
      },
      "Day for night" to {
        Lut.generate("Day for night") { r, g, b ->
          val l = luma(r, g, b)
          val dark = { v: Float -> v.pow(1.8f) * 0.55f }
          Triple(dark(mix(l, r, 0.3f)) * 0.8f, dark(mix(l, g, 0.3f)) * 0.95f, dark(mix(l, b, 0.3f)) * 1.35f + 0.02f)
        }
      },
      "High contrast mono" to {
        Lut.generate("High contrast mono") { r, g, b ->
          val l = smooth(((luma(r, g, b) - 0.5f) * 1.3f + 0.5f).coerceIn(0f, 1f))
          Triple(l, l, l)
        }
      }
    )
  }
}
