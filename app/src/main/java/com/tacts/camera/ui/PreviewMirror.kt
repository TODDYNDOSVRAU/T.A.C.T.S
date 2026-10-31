package com.tacts.camera.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.delay

/**
 * A small, low-resolution copy of the viewfinder, drawn in Compose under the real one.
 *
 * The camera draws into a TextureView, which Compose's layer recording cannot see, so the glass over
 * it would blur nothing. This copy is what the glass blurs: a 1/12-size frame read from the
 * TextureView about 15 times a second. Blurred, the low resolution does not show.
 */
@Composable
fun PreviewMirror(source: () -> TextureView?, modifier: Modifier = Modifier) {
  val state = remember { MirrorState() }
  var tick by remember { mutableIntStateOf(0) }

  LaunchedEffect(Unit) {
    while (true) {
      if (state.read(source())) tick++
      delay(FRAME_MS)
    }
  }

  Canvas(modifier.onGloballyPositioned { state.origin = it.positionInWindow().let { p -> floatArrayOf(p.x, p.y) } }) {
    @Suppress("UNUSED_EXPRESSION") tick
    val frame = state.shown ?: return@Canvas
    drawIntoCanvas { canvas ->
      val c = canvas.nativeCanvas
      c.save()
      // Where the view's parent sits relative to this canvas; the parent crops what the view's scale
      // pushes past its edges. Then the view's place and its own scale and rotation (CameraX uses both),
      // then the texture transform.
      c.translate(state.offsetX, state.offsetY)
      c.clipRect(0f, 0f, state.parentW, state.parentH)
      c.translate(state.viewLeft, state.viewTop)
      c.concat(state.viewMatrix)
      c.concat(state.matrix)
      c.drawBitmap(frame, null, RectF(0f, 0f, state.viewW, state.viewH), state.paint)
      c.restore()
    }
  }
}

private class MirrorState {
  /** Two frames, so the one on screen is never the one being written. */
  private var frames = arrayOfNulls<Bitmap>(2)
  private var next = 0
  var shown: Bitmap? = null
  val matrix = Matrix()
  val viewMatrix = Matrix()
  var parentW = 0f
  var parentH = 0f
  var viewLeft = 0f
  var viewTop = 0f
  val paint = Paint(Paint.FILTER_BITMAP_FLAG)
  var viewW = 0f
  var viewH = 0f
  var offsetX = 0f
  var offsetY = 0f
  var origin = floatArrayOf(0f, 0f)
  private val location = IntArray(2)

  fun read(view: TextureView?): Boolean {
    if (view == null || !view.isAvailable || view.width <= 0 || view.height <= 0) return false
    val w = (view.width / SCALE).coerceAtLeast(8)
    val h = (view.height / SCALE).coerceAtLeast(8)
    val target = frames[next]?.takeIf { it.width == w && it.height == h }
      ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { frames[next] = it }
    return try {
      view.getBitmap(target)
      view.getTransform(matrix)
      viewMatrix.set(view.matrix)
      val parent = view.parent as? android.view.View
      (parent ?: view).getLocationInWindow(location)
      parentW = (parent?.width ?: view.width).toFloat()
      parentH = (parent?.height ?: view.height).toFloat()
      viewLeft = if (parent != null) view.left.toFloat() else 0f
      viewTop = if (parent != null) view.top.toFloat() else 0f
      viewW = view.width.toFloat()
      viewH = view.height.toFloat()
      offsetX = location[0] - origin[0]
      offsetY = location[1] - origin[1]
      shown = target
      next = 1 - next
      true
    } catch (e: Exception) {
      false
    }
  }
}

private const val SCALE = 12
private const val FRAME_MS = 66L
