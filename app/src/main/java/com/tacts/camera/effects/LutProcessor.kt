package com.tacts.camera.effects

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * The look and the LUT on the GPU, for the preview and the recorded video at once: CameraX hands the
 * camera's frames to [LutProcessor] as an OES texture, and each frame is drawn to every output through
 * a colour matrix and a 3D LUT. Built as a CameraEffect, so the encoder records what the screen shows.
 */
class LutEffect(val processor: LutProcessor, targets: Int = PREVIEW or VIDEO_CAPTURE) : CameraEffect(
  targets,
  processor.executor,
  processor,
  { it.printStackTrace() }
)

class LutProcessor : SurfaceProcessor {
  private val thread = HandlerThread("tacts-gl").apply { start() }
  private val handler = Handler(thread.looper)
  val executor = Executor { handler.post(it) }

  private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
  private var context: EGLContext = EGL14.EGL_NO_CONTEXT
  private var config: EGLConfig? = null
  private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
  private var program = 0
  private var oesTex = 0
  private var lutTex = 0
  private var lutSize = 0f

  private var input: SurfaceTexture? = null
  private var inputSurface: Surface? = null
  private val outputs = mutableMapOf<SurfaceOutput, EGLSurface>()
  private val texMatrix = FloatArray(16)
  private val outMatrix = FloatArray(16)

  // Colour matrix (column-major 4x4) and offset, from an android ColorMatrix; identity by default.
  private var colorMatrix = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
  private var colorOffset = floatArrayOf(0f, 0f, 0f, 0f)
  private var pendingLut: Lut? = null
  private var lutChanged = false
  private var released = false

  init {
    handler.post { initGl() }
  }

  /** Sets the look as an android 4x5 colour matrix (offsets in 0..255), or null for none. */
  fun setColorMatrix(m: FloatArray?) = handler.post {
    if (m == null) {
      colorMatrix = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
      colorOffset = floatArrayOf(0f, 0f, 0f, 0f)
    } else {
      // Row-major 4x5 to column-major 4x4 plus offset.
      colorMatrix = FloatArray(16) { i -> val col = i / 4; val row = i % 4; m[row * 5 + col] }
      colorOffset = floatArrayOf(m[4] / 255f, m[9] / 255f, m[14] / 255f, m[19] / 255f)
    }
  }

  fun setLut(lut: Lut?) = handler.post {
    pendingLut = lut
    lutChanged = true
  }

  // ---- SurfaceProcessor ----

  override fun onInputSurface(request: SurfaceRequest) {
    if (released) {
      request.willNotProvideSurface()
      return
    }
    val texture = SurfaceTexture(oesTex).apply {
      setDefaultBufferSize(request.resolution.width, request.resolution.height)
      setOnFrameAvailableListener({ draw() }, handler)
    }
    val surface = Surface(texture)
    input?.release()
    inputSurface?.release()
    input = texture
    inputSurface = surface
    request.provideSurface(surface, executor) {
      texture.setOnFrameAvailableListener(null)
      texture.release()
      surface.release()
      if (input === texture) {
        input = null
        inputSurface = null
      }
    }
  }

  override fun onOutputSurface(output: SurfaceOutput) {
    if (released) {
      output.close()
      return
    }
    val surface = output.getSurface(executor) {
      outputs.remove(output)?.let { EGL14.eglDestroySurface(display, it) }
      output.close()
    }
    val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
    outputs[output] = egl
  }

  fun release() = handler.post {
    released = true
    outputs.forEach { (out, egl) ->
      EGL14.eglDestroySurface(display, egl)
      out.close()
    }
    outputs.clear()
    input?.release()
    inputSurface?.release()
    if (display != EGL14.EGL_NO_DISPLAY) {
      EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
      EGL14.eglDestroySurface(display, pbuffer)
      EGL14.eglDestroyContext(display, context)
      EGL14.eglTerminate(display)
    }
    thread.quitSafely()
  }

  // ---- GL ----

  private fun initGl() {
    display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val version = IntArray(2)
    EGL14.eglInitialize(display, version, 0, version, 1)
    val attribs = intArrayOf(
      EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
      EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
      EGL_RECORDABLE_ANDROID, 1,
      EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
      EGL14.EGL_NONE
    )
    val configs = arrayOfNulls<EGLConfig>(1)
    val count = IntArray(1)
    EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0)
    config = configs[0]
    context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
    pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
    EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)

    program = link(VERTEX, FRAGMENT)
    val tex = IntArray(2)
    GLES20.glGenTextures(2, tex, 0)
    oesTex = tex[0]
    lutTex = tex[1]
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
  }

  private fun uploadLut() {
    lutChanged = false
    val lut = pendingLut
    if (lut == null) {
      lutSize = 0f
      return
    }
    val n = lut.size
    // A strip of n slices, one per blue level: x = b * n + r, y = g.
    val bytes = ByteBuffer.allocateDirect(n * n * n * 3)
    for (g in 0 until n) for (b in 0 until n) for (r in 0 until n) {
      val i = (b * n * n + g * n + r) * 3
      bytes.put((lut.data[i] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
      bytes.put((lut.data[i + 1] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
      bytes.put((lut.data[i + 2] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
    }
    bytes.position(0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, n * n, n, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, bytes)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    lutSize = n.toFloat()
  }

  private fun draw() {
    val texture = input ?: return
    if (released) return
    EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
    texture.updateTexImage()
    texture.getTransformMatrix(texMatrix)
    if (lutChanged) uploadLut()
    for ((out, egl) in outputs) {
      EGL14.eglMakeCurrent(display, egl, egl, context)
      GLES20.glViewport(0, 0, out.size.width, out.size.height)
      out.updateTransformMatrix(outMatrix, texMatrix)
      GLES20.glUseProgram(program)
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
      GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
      GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex)
      GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uLut"), 1)
      GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLutSize"), lutSize)
      GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, outMatrix, 0)
      GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uColor"), 1, false, colorMatrix, 0)
      GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uOffset"), 1, colorOffset, 0)
      val pos = GLES20.glGetAttribLocation(program, "aPos")
      val uv = GLES20.glGetAttribLocation(program, "aUv")
      GLES20.glEnableVertexAttribArray(pos)
      GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
      GLES20.glEnableVertexAttribArray(uv)
      GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, QUAD_UV)
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
      EGLExt.eglPresentationTimeANDROID(display, egl, texture.timestamp)
      EGL14.eglSwapBuffers(display, egl)
    }
  }

  private fun link(vs: String, fs: String): Int {
    fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also {
      GLES20.glShaderSource(it, src)
      GLES20.glCompileShader(it)
    }
    return GLES20.glCreateProgram().also {
      GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vs))
      GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fs))
      GLES20.glLinkProgram(it)
    }
  }

  companion object {
    private const val EGL_RECORDABLE_ANDROID = 0x3142

    private fun floats(vararg v: Float): FloatBuffer =
      ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

    private val QUAD = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val QUAD_UV = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    private const val VERTEX = """
      attribute vec4 aPos;
      attribute vec4 aUv;
      uniform mat4 uTexMatrix;
      varying vec2 vUv;
      void main() {
        gl_Position = aPos;
        vUv = (uTexMatrix * aUv).xy;
      }
    """

    // The look's matrix first, then the LUT: two samples of the slice strip blended along blue.
    private const val FRAGMENT = """
      #extension GL_OES_EGL_image_external : require
      precision mediump float;
      uniform samplerExternalOES uTex;
      uniform sampler2D uLut;
      uniform float uLutSize;
      uniform mat4 uColor;
      uniform vec4 uOffset;
      varying vec2 vUv;
      vec3 applyLut(vec3 c) {
        float n = uLutSize;
        float b = c.b * (n - 1.0);
        float b0 = floor(b);
        float b1 = min(b0 + 1.0, n - 1.0);
        float y = (c.g * (n - 1.0) + 0.5) / n;
        float x = c.r * (n - 1.0) + 0.5;
        vec3 s0 = texture2D(uLut, vec2((b0 * n + x) / (n * n), y)).rgb;
        vec3 s1 = texture2D(uLut, vec2((b1 * n + x) / (n * n), y)).rgb;
        return mix(s0, s1, b - b0);
      }
      void main() {
        vec3 c = texture2D(uTex, vUv).rgb;
        c = clamp((uColor * vec4(c, 1.0)).rgb + uOffset.rgb, 0.0, 1.0);
        if (uLutSize > 1.0) c = applyLut(c);
        gl_FragColor = vec4(c, 1.0);
      }
    """
  }
}
