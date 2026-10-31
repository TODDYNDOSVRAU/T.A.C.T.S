package com.tacts.camera.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor

/** One captured frame, kept as its JPEG: eight 12 MP frames take about 30 MB this way, 400 MB as bitmaps. */
class Frame(val jpeg: ByteArray) {
  val width: Int
  val height: Int

  init {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
    width = o.outWidth
    height = o.outHeight
  }
}

/** A frame's weight at a pixel; an interface of its own so the call for every pixel does not box. */
fun interface Weight {
  fun at(frame: Int, x: Int, y: Int): Float
}

/** Where each part of a frame sits against the reference: a grid of shifts, in full-size pixels. */
class Flow(private val w: Int, private val h: Int, private val dx: FloatArray, private val dy: FloatArray) {
  val minDy = dy.min()
  val maxDy = dy.max()
  val minDx = dx.min()
  val maxDx = dx.max()

  /** The shift at every pixel of row [y], interpolated between tile centres. */
  fun row(y: Float, outDx: FloatArray, outDy: FloatArray) {
    val ty = (y / h * TILES_Y - 0.5f).coerceIn(0f, TILES_Y - 1f)
    val t0 = floor(ty).toInt().coerceAtMost(TILES_Y - 2)
    val fy = ty - t0
    val n = outDx.size
    for (x in 0 until n) {
      val tx = (x.toFloat() / w * TILES_X - 0.5f).coerceIn(0f, TILES_X - 1f)
      val s0 = floor(tx).toInt().coerceAtMost(TILES_X - 2)
      val fx = tx - s0
      val i00 = t0 * TILES_X + s0
      val i10 = i00 + TILES_X
      outDx[x] = lerp(lerp(dx[i00], dx[i00 + 1], fx), lerp(dx[i10], dx[i10 + 1], fx), fy)
      outDy[x] = lerp(lerp(dy[i00], dy[i00 + 1], fx), lerp(dy[i10], dy[i10 + 1], fx), fy)
    }
  }

  companion object {
    const val TILES_X = 8
    const val TILES_Y = 6
    fun zero(w: Int, h: Int) = Flow(w, h, FloatArray(TILES_X * TILES_Y), FloatArray(TILES_X * TILES_Y))
  }
}

/**
 * Photos made from several frames: night (averaged, less noise), HDR (three exposures fused) and
 * super-resolution (shifted frames added on a grid twice as fine). Every frame is first aligned to the
 * reference, tile by tile, so a hand-held burst lines up; parts that moved between frames are left to
 * the reference instead of doubling. Frames stay as JPEG and are decoded a band at a time, so memory
 * stays near the size of the result.
 */
object MultiFrame {
  private const val SCALE = 4
  private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

  /** Average of the frames, each pixel weighted by how well it matches the reference. */
  fun night(frames: List<Frame>, ref: Int = 0, onStep: (String) -> Unit = {}): Bitmap {
    onStep("Aligning")
    val flows = align(frames, ref)
    onStep("Merging")
    return merge1x(frames, flows, ref) { _, _, _ -> 1f }
  }

  /**
   * Exposure fusion: every pixel comes from the frames where it is best exposed, with the weights
   * smoothed so the change from one frame to another does not show as an edge.
   */
  fun hdr(frames: List<Frame>, ref: Int, onStep: (String) -> Unit = {}): Bitmap {
    onStep("Aligning")
    val flows = align(frames, ref)
    onStep("Fusing")
    val w = frames[ref].width
    val h = frames[ref].height
    val maps = frames.indices.map { i -> exposureWeights(frames[i], flows[i], w, h) }
    // Normalised across frames.
    val n = maps[0].size
    for (k in 0 until n) {
      var s = 0f
      for (m in maps) s += m[k]
      for (m in maps) m[k] = m[k] / s
    }
    val sw = (w + SCALE - 1) / SCALE
    val sh = (h + SCALE - 1) / SCALE
    return merge1x(frames, flows, ref, robust = false) { f, x, y ->
      sample(maps[f], sw, sh, x.toFloat() / SCALE, y.toFloat() / SCALE)
    }
  }

  /**
   * Shift-and-add on a grid twice as fine: hand shake moves each frame by a fraction of a pixel, so
   * together they sample between the pixels of one frame. The detail gained is real but modest after
   * the phone's own processing, far from what a sensor with four times the pixels records.
   */
  fun superRes(frames: List<Frame>, ref: Int = 0, onStep: (String) -> Unit = {}): Bitmap {
    onStep("Aligning")
    val flows = align(frames, ref)
    onStep("Merging")
    return merge2x(frames, flows, ref)
  }

  // ---- alignment ----

  private class Small(val w: Int, val h: Int, val l: FloatArray)

  private fun small(frame: Frame): Small {
    val b = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size, BitmapFactory.Options().apply { inSampleSize = SCALE })
    val w = b.width
    val h = b.height
    val px = IntArray(w * h)
    b.getPixels(px, 0, w, 0, 0, w, h)
    b.recycle()
    return Small(w, h, FloatArray(w * h) { luma(px[it]) })
  }

  /** The image's levels mapped so its histogram matches [ref]'s: different exposures compare alike. */
  private fun matchTo(img: Small, ref: Small): Small {
    fun cdf(s: Small): FloatArray {
      val hist = IntArray(256)
      for (v in s.l) hist[v.toInt().coerceIn(0, 255)]++
      val c = FloatArray(256)
      var acc = 0
      for (i in 0 until 256) { acc += hist[i]; c[i] = acc.toFloat() / s.l.size }
      return c
    }
    val a = cdf(img)
    val b = cdf(ref)
    val map = FloatArray(256)
    var j = 0
    for (i in 0 until 256) {
      while (j < 255 && b[j] < a[i]) j++
      map[i] = j.toFloat()
    }
    return Small(img.w, img.h, FloatArray(img.l.size) { map[img.l[it].toInt().coerceIn(0, 255)] })
  }

  private fun shrink(s: Small, f: Int): Small {
    val w = s.w / f
    val h = s.h / f
    val out = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
      var sum = 0f
      for (j in 0 until f) for (i in 0 until f) sum += s.l[(y * f + j) * s.w + x * f + i]
      out[y * w + x] = sum / (f * f)
    }
    return Small(w, h, out)
  }

  /** Mean absolute difference over a box, with the image shifted by (dx, dy). */
  private fun sad(ref: Small, img: Small, x0: Int, y0: Int, x1: Int, y1: Int, dx: Int, dy: Int, step: Int): Float {
    var sum = 0f
    var n = 0
    var y = y0
    while (y < y1) {
      val iy = y + dy
      if (iy in 0 until img.h) {
        var x = x0
        val rRow = y * ref.w
        val iRow = iy * img.w
        while (x < x1) {
          val ix = x + dx
          if (ix in 0 until img.w) {
            sum += abs(ref.l[rRow + x] - img.l[iRow + ix])
            n++
          }
          x += step
        }
      }
      y += step
    }
    return if (n < 16) Float.MAX_VALUE else sum / n
  }

  private class Best(val x: Int, val y: Int, val fx: Float, val fy: Float, val cost: Float)

  private fun search(ref: Small, img: Small, x0: Int, y0: Int, x1: Int, y1: Int, cx: Int, cy: Int, r: Int, step: Int): Best {
    val size = 2 * r + 1
    val costs = FloatArray(size * size)
    var bi = 0
    for (j in 0 until size) for (i in 0 until size) {
      val c = sad(ref, img, x0, y0, x1, y1, cx + i - r, cy + j - r, step)
      costs[j * size + i] = c
      if (c < costs[bi]) bi = j * size + i
    }
    val bx = bi % size
    val by = bi / size
    // A parabola through the best cost and its neighbours places the minimum between pixels.
    fun sub(a: Float, b: Float, c: Float): Float {
      val d = a - 2 * b + c
      return if (d <= 1e-6f || a == Float.MAX_VALUE || c == Float.MAX_VALUE) 0f else ((a - c) / (2 * d)).coerceIn(-0.5f, 0.5f)
    }
    val fx = if (bx in 1 until size - 1) sub(costs[by * size + bx - 1], costs[bi], costs[by * size + bx + 1]) else 0f
    val fy = if (by in 1 until size - 1) sub(costs[(by - 1) * size + bx], costs[bi], costs[(by + 1) * size + bx]) else 0f
    return Best(cx + bx - r, cy + by - r, fx, fy, costs[bi])
  }

  /** A flow for every frame against [ref]; the reference's own is zero. */
  fun align(frames: List<Frame>, ref: Int): List<Flow> {
    val w = frames[ref].width
    val h = frames[ref].height
    val refSmall = small(frames[ref])
    val refCoarse = shrink(refSmall, 4)
    val pool = Executors.newFixedThreadPool(threads)
    try {
      return frames.indices.map { i ->
        pool.submit(Callable {
          if (i == ref) Flow.zero(w, h) else alignOne(refSmall, refCoarse, matchTo(small(frames[i]), refSmall), w, h)
        })
      }.map { it.get() }
    } finally {
      pool.shutdown()
    }
  }

  private fun alignOne(ref: Small, refCoarse: Small, img: Small, w: Int, h: Int): Flow {
    // Whole-frame shift on a 1/16 image, then refined on the 1/4 one.
    val coarse = shrink(img, 4)
    val r = 10
    val g0 = search(refCoarse, coarse, r, r, refCoarse.w - r, refCoarse.h - r, 0, 0, r, 1)
    val g = search(ref, img, 16, 16, ref.w - 16, ref.h - 16, g0.x * 4, g0.y * 4, 4, 2)

    // Then tile by tile, which follows a small turn of the phone as well.
    val tx = Flow.TILES_X
    val ty = Flow.TILES_Y
    val dx = FloatArray(tx * ty)
    val dy = FloatArray(tx * ty)
    val tw = ref.w / tx
    val th = ref.h / ty
    for (j in 0 until ty) for (i in 0 until tx) {
      val x0 = i * tw
      val y0 = j * th
      val k = j * tx + i
      // Flat tiles (sky, a wall) give no reliable match; they take the whole-frame shift.
      if (contrast(ref, x0, y0, x0 + tw, y0 + th) < 4f) {
        dx[k] = (g.x + g.fx) * SCALE
        dy[k] = (g.y + g.fy) * SCALE
        continue
      }
      val b = search(ref, img, x0, y0, x0 + tw, y0 + th, g.x, g.y, 3, 1)
      dx[k] = (b.x + b.fx) * SCALE
      dy[k] = (b.y + b.fy) * SCALE
    }
    return Flow(w, h, median3(dx, tx, ty), median3(dy, tx, ty))
  }

  private fun contrast(s: Small, x0: Int, y0: Int, x1: Int, y1: Int): Float {
    var sum = 0f
    var sq = 0f
    var n = 0
    for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
      val v = s.l[y * s.w + x]
      sum += v
      sq += v * v
      n++
    }
    val mean = sum / n
    return kotlin.math.sqrt((sq / n - mean * mean).coerceAtLeast(0f))
  }

  /** A 3×3 median over the tile grid, so one tile fooled by a moving object follows its neighbours. */
  private fun median3(v: FloatArray, w: Int, h: Int): FloatArray = FloatArray(v.size) { k ->
    val x = k % w
    val y = k / w
    val n = ArrayList<Float>(9)
    for (j in -1..1) for (i in -1..1) {
      val xx = x + i
      val yy = y + j
      if (xx in 0 until w && yy in 0 until h) n.add(v[yy * w + xx])
    }
    n.sort()
    n[n.size / 2]
  }

  // ---- merging ----

  @Suppress("DEPRECATION")
  private fun decoders(frames: List<Frame>) = frames.map { BitmapRegionDecoder.newInstance(it.jpeg, 0, it.jpeg.size, false)!! }

  /** Rows [y0, y1) of a frame, clamped to the frame, as ARGB pixels. */
  private class Band(val y0: Int, val rows: Int, val w: Int, val px: IntArray)

  private fun band(decoder: BitmapRegionDecoder, w: Int, h: Int, y0: Int, y1: Int): Band? {
    val a = y0.coerceIn(0, h)
    val b = y1.coerceIn(0, h)
    if (b - a < 1) return null
    val bmp = decoder.decodeRegion(Rect(0, a, w, b), null) ?: return null
    val px = IntArray(w * (b - a))
    bmp.getPixels(px, 0, w, 0, 0, w, b - a)
    bmp.recycle()
    return Band(a, b - a, w, px)
  }

  /**
   * One output the size of the reference. [weight] gives a frame's weight at a pixel; with [robust],
   * a pixel that differs from the reference by more than noise counts less, down to nothing.
   */
  private fun merge1x(
    frames: List<Frame>,
    flows: List<Flow>,
    ref: Int,
    robust: Boolean = true,
    weight: Weight
  ): Bitmap {
    val w = frames[ref].width
    val h = frames[ref].height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val decs = decoders(frames)
    val strip = 256
    val pool = Executors.newFixedThreadPool(threads)
    try {
      (0 until h step strip).map { sy0 ->
        pool.submit {
          val sy1 = minOf(h, sy0 + strip)
          val rows = sy1 - sy0
          val refBand = band(decs[ref], w, h, sy0, sy1) ?: error("Could not read the reference frame")
          val r = FloatArray(w * rows)
          val gg = FloatArray(w * rows)
          val b = FloatArray(w * rows)
          val ws = FloatArray(w * rows)
          val rowDx = FloatArray(w)
          val rowDy = FloatArray(w)
          for (f in frames.indices) {
            if (frames[f].width != w || frames[f].height != h) continue
            val flow = flows[f]
            val bd = if (f == ref) refBand else band(decs[f], w, h, sy0 + floor(flow.minDy).toInt() - 1, sy1 + ceil(flow.maxDy).toInt() + 2) ?: continue
            for (y in 0 until rows) {
              val gy = sy0 + y
              flow.row(gy.toFloat(), rowDx, rowDy)
              for (x in 0 until w) {
                val p = if (f == ref) bd.px[y * w + x] else sampleBand(bd, x + rowDx[x], gy + rowDy[x])
                if (p == NONE) continue
                var wt = weight.at(f, x, gy)
                if (robust && f != ref) wt *= agreement(p, refBand.px[y * w + x])
                if (wt <= 0f) continue
                val k = y * w + x
                r[k] += ((p shr 16) and 0xFF) * wt
                gg[k] += ((p shr 8) and 0xFF) * wt
                b[k] += (p and 0xFF) * wt
                ws[k] += wt
              }
            }
          }
          val px = IntArray(w * rows)
          for (k in px.indices) {
            val s = ws[k]
            px[k] = if (s <= 1e-4f) refBand.px[k] else pack(r[k] / s, gg[k] / s, b[k] / s)
          }
          synchronized(out) { out.setPixels(px, 0, w, 0, sy0, w, rows) }
        }
      }.forEach { it.get() }
    } finally {
      pool.shutdown()
      decs.forEach { it.recycle() }
    }
    return out
  }

  /** Twice the reference's width and height, each frame's pixels splatted where they land on the fine grid. */
  private fun merge2x(frames: List<Frame>, flows: List<Flow>, ref: Int): Bitmap {
    val w = frames[ref].width
    val h = frames[ref].height
    val ow = w * 2
    val oh = h * 2
    val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
    val decs = decoders(frames)
    // A JPEG band is decoded from the top of the file each time, so every frame is decoded once per
    // band of 512 reference rows; the fine grid is then built in strips of 64 rows across the cores.
    val big = 512
    val strip = 64
    val pool = Executors.newFixedThreadPool(threads)
    try {
      var b0 = 0
      while (b0 < h) {
        val b1 = minOf(h, b0 + big)
        val bands = frames.indices.map { f ->
          pool.submit(Callable {
            if (frames[f].width != w || frames[f].height != h) null
            else band(decs[f], w, h, b0 - 3 + floor(flows[f].minDy).toInt(), b1 + 3 + ceil(flows[f].maxDy).toInt())
          })
        }.map { it.get() }
        val refBand = bands[ref] ?: error("Could not read the reference frame")
        (b0 * 2 until b1 * 2 step strip).map { oy0 ->
          pool.submit {
            splat(frames, flows, bands, refBand, ref, w, ow, oh, oy0, minOf(b1 * 2, oy0 + strip), out)
          }
        }.forEach { it.get() }
        b0 = b1
      }
    } finally {
      pool.shutdown()
      decs.forEach { it.recycle() }
    }
    return out
  }

  /** Output rows [oy0, oy1) of the fine grid, from the frames' decoded bands, sharpened and written to [out]. */
  private fun splat(
    frames: List<Frame>,
    flows: List<Flow>,
    bands: List<Band?>,
    refBand: Band,
    ref: Int,
    w: Int,
    ow: Int,
    oh: Int,
    oy0: Int,
    oy1: Int,
    out: Bitmap
  ) {
    // One row more above and below, for the sharpening.
    val e0 = (oy0 - 1).coerceAtLeast(0)
    val e1 = (oy1 + 1).coerceAtMost(oh)
    val rows = e1 - e0
    val r = FloatArray(ow * rows)
    val gg = FloatArray(ow * rows)
    val b = FloatArray(ow * rows)
    val ws = FloatArray(ow * rows)
    val ry0 = e0 / 2 - 2
    val ry1 = (e1 + 1) / 2 + 2
    val rowDx = FloatArray(w)
    val rowDy = FloatArray(w)
    for (f in frames.indices) {
      val bd = bands[f] ?: continue
      val flow = flows[f]
      // Only the frame rows that can land in these output rows.
      val y0 = (ry0 + floor(flow.minDy).toInt() - 1 - bd.y0).coerceAtLeast(0)
      val y1 = (ry1 + ceil(flow.maxDy).toInt() + 1 - bd.y0).coerceAtMost(bd.rows)
      for (y in y0 until y1) {
        val fy = bd.y0 + y
        flow.row(fy.toFloat(), rowDx, rowDy)
        for (x in 0 until w) {
          // Where this pixel's centre lands on the fine grid.
          val rx = x - rowDx[x]
          val ryf = fy - rowDy[x]
          val oxf = rx * 2f + 0.5f
          val oyf = ryf * 2f + 0.5f - e0
          val ox = floor(oxf).toInt()
          val oy = floor(oyf).toInt()
          if (oy < -1 || oy >= rows || ox < -1 || ox >= ow) continue
          val p = bd.px[y * w + x]
          var a = 1f
          if (f != ref) {
            val q = refAt(refBand, rx, ryf)
            if (q == NONE) continue
            a = agreement(p, q)
            if (a <= 0f) continue
          }
          val fx = oxf - ox
          val fyy = oyf - oy
          val pr = ((p shr 16) and 0xFF).toFloat()
          val pg = ((p shr 8) and 0xFF).toFloat()
          val pb = (p and 0xFF).toFloat()
          for (j in 0..1) {
            val yy = oy + j
            if (yy !in 0 until rows) continue
            val wy = if (j == 0) 1f - fyy else fyy
            for (i in 0..1) {
              val xx = ox + i
              if (xx !in 0 until ow) continue
              val wt = (if (i == 0) 1f - fx else fx) * wy * a
              val k = yy * ow + xx
              r[k] += pr * wt
              gg[k] += pg * wt
              b[k] += pb * wt
              ws[k] += wt
            }
          }
        }
      }
    }
    for (k in ws.indices) {
      val s = ws[k]
      if (s > 1e-4f) { r[k] /= s; gg[k] /= s; b[k] /= s }
    }
    // A light unsharp mask on the fine grid: the splat softens edges by about half a pixel.
    val first = oy0 - e0
    val n = oy1 - oy0
    val px = IntArray(ow * n)
    for (y in 0 until n) {
      val yy = first + y
      val up = (yy - 1).coerceAtLeast(0)
      val dn = (yy + 1).coerceAtMost(rows - 1)
      for (x in 0 until ow) {
        val l = (x - 1).coerceAtLeast(0)
        val rr = (x + 1).coerceAtMost(ow - 1)
        val k = yy * ow + x
        val u = up * ow + x
        val d = dn * ow + x
        val ll = yy * ow + l
        val rk = yy * ow + rr
        px[y * ow + x] = pack(sharp(r, k, u, d, ll, rk), sharp(gg, k, u, d, ll, rk), sharp(b, k, u, d, ll, rk))
      }
    }
    synchronized(out) { out.setPixels(px, 0, ow, 0, oy0, ow, n) }
  }

  /** Unsharp mask of one value against its four neighbours. */
  private fun sharp(c: FloatArray, k: Int, up: Int, dn: Int, l: Int, r: Int): Float {
    val blur = (c[up] + c[dn] + c[l] + c[r] + 4 * c[k]) / 8f
    return c[k] + 0.8f * (c[k] - blur)
  }

  /** Stands for "no pixel": decoded JPEG pixels are always opaque, so 0 (transparent black) never occurs. */
  private const val NONE = 0

  /** 1 when two pixels agree within noise, falling to 0 for a clear difference (something moved). */
  private fun agreement(p: Int, q: Int): Float {
    val d = abs(luma(p) - luma(q))
    return when {
      d < 12f -> 1f
      d > 36f -> 0f
      else -> 1f - (d - 12f) / 24f
    }
  }

  /** The band's nearest pixel at full-frame coordinates; [NONE] outside it. */
  private fun refAt(band: Band, x: Float, y: Float): Int {
    val xi = (x + 0.5f).toInt()
    val yi = (y + 0.5f).toInt() - band.y0
    if (x < -0.5f || xi >= band.w || yi < 0 || yi >= band.rows || y + 0.5f < 0f) return NONE
    return band.px[yi * band.w + xi]
  }

  /** Bilinear sample of a band at full-frame coordinates; [NONE] outside it. */
  private fun sampleBand(band: Band, x: Float, y: Float): Int {
    val ly = y - band.y0
    if (x < 0f || ly < 0f || x > band.w - 1f || ly > band.rows - 1f) return NONE
    val x0 = x.toInt().coerceAtMost(band.w - 2).coerceAtLeast(0)
    val y0 = ly.toInt().coerceAtMost((band.rows - 2).coerceAtLeast(0))
    val fx = x - x0
    val fy = ly - y0
    val w = band.w
    val y1 = (y0 + 1).coerceAtMost(band.rows - 1)
    val p00 = band.px[y0 * w + x0]
    val p10 = band.px[y0 * w + x0 + 1]
    val p01 = band.px[y1 * w + x0]
    val p11 = band.px[y1 * w + x0 + 1]
    return pack(bilinear(p00, p10, p01, p11, 16, fx, fy), bilinear(p00, p10, p01, p11, 8, fx, fy), bilinear(p00, p10, p01, p11, 0, fx, fy))
  }

  private fun bilinear(p00: Int, p10: Int, p01: Int, p11: Int, shift: Int, fx: Float, fy: Float): Float {
    val a = ((p00 shr shift) and 0xFF).toFloat()
    val b = ((p10 shr shift) and 0xFF).toFloat()
    val c = ((p01 shr shift) and 0xFF).toFloat()
    val d = ((p11 shr shift) and 0xFF).toFloat()
    return lerp(lerp(a, b, fx), lerp(c, d, fx), fy)
  }

  /**
   * How well exposed each part of a frame is, on the 1/4 grid in reference coordinates: highest at mid
   * grey, falling towards black and white, then blurred so the weights change gently.
   */
  private fun exposureWeights(frame: Frame, flow: Flow, w: Int, h: Int): FloatArray {
    val s = small(frame)
    val sw = (w + SCALE - 1) / SCALE
    val sh = (h + SCALE - 1) / SCALE
    val out = FloatArray(sw * sh)
    val rowDx = FloatArray(w)
    val rowDy = FloatArray(w)
    for (y in 0 until sh) {
      flow.row((y * SCALE).toFloat(), rowDx, rowDy)
      for (x in 0 until sw) {
        val fx = ((x * SCALE + rowDx[(x * SCALE).coerceAtMost(w - 1)]) / SCALE).toInt().coerceIn(0, s.w - 1)
        val fy = ((y * SCALE + rowDy[(x * SCALE).coerceAtMost(w - 1)]) / SCALE).toInt().coerceIn(0, s.h - 1)
        val v = s.l[fy * s.w + fx] / 255f - 0.5f
        out[y * sw + x] = exp(-v * v / (2f * 0.2f * 0.2f)) + 1e-3f
      }
    }
    repeat(2) { boxBlur(out, sw, sh, 8) }
    return out
  }

  private fun boxBlur(v: FloatArray, w: Int, h: Int, r: Int) {
    val tmp = FloatArray(v.size)
    for (y in 0 until h) {
      var sum = 0f
      for (x in -r..r) sum += v[y * w + x.coerceIn(0, w - 1)]
      for (x in 0 until w) {
        tmp[y * w + x] = sum / (2 * r + 1)
        sum += v[y * w + (x + r + 1).coerceAtMost(w - 1)] - v[y * w + (x - r).coerceAtLeast(0)]
      }
    }
    for (x in 0 until w) {
      var sum = 0f
      for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
      for (y in 0 until h) {
        v[y * w + x] = sum / (2 * r + 1)
        sum += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
      }
    }
  }

  private fun sample(m: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
    val x0 = x.toInt().coerceIn(0, w - 1)
    val y0 = y.toInt().coerceIn(0, h - 1)
    val x1 = (x0 + 1).coerceAtMost(w - 1)
    val y1 = (y0 + 1).coerceAtMost(h - 1)
    val fx = (x - x0).coerceIn(0f, 1f)
    val fy = (y - y0).coerceIn(0f, 1f)
    return lerp(lerp(m[y0 * w + x0], m[y0 * w + x1], fx), lerp(m[y1 * w + x0], m[y1 * w + x1], fx), fy)
  }

  private fun luma(p: Int) = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)

  private fun pack(r: Float, g: Float, b: Float) =
    (0xFF shl 24) or ((r + 0.5f).toInt().coerceIn(0, 255) shl 16) or ((g + 0.5f).toInt().coerceIn(0, 255) shl 8) or (b + 0.5f).toInt().coerceIn(0, 255)
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
