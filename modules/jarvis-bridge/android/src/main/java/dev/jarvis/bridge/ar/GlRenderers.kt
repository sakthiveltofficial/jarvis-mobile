package dev.jarvis.bridge.ar

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal object Gl {
  fun floats(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
  fun floats(v: FloatArray): FloatBuffer = floats(v.size).apply { put(v); position(0) }

  fun program(vs: String, fs: String): Int {
    val p = GLES20.glCreateProgram()
    GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
    GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
    GLES20.glLinkProgram(p)
    val ok = IntArray(1)
    GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
    if (ok[0] == 0) {
      val log = GLES20.glGetProgramInfoLog(p)
      GLES20.glDeleteProgram(p)
      throw IllegalStateException("GL link failed: $log")
    }
    return p
  }

  private fun shader(type: Int, src: String): Int {
    val s = GLES20.glCreateShader(type)
    GLES20.glShaderSource(s, src)
    GLES20.glCompileShader(s)
    val ok = IntArray(1)
    GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
    if (ok[0] == 0) {
      val log = GLES20.glGetShaderInfoLog(s)
      GLES20.glDeleteShader(s)
      Log.e("JarvisAR", "shader: $log")
      throw IllegalStateException("GL compile failed: $log")
    }
    return s
  }
}

/** Draws the camera image behind everything (ARCore's external OES texture on a full-screen quad). */
internal class BackgroundRenderer {
  var textureId = -1
    private set
  private var program = 0
  private var aPos = 0
  private var aTex = 0
  private val quad = Gl.floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
  private val tex = Gl.floats(8)
  private var texReady = false

  fun create() {
    val t = IntArray(1)
    GLES20.glGenTextures(1, t, 0)
    textureId = t[0]
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    program = Gl.program(
      """
      attribute vec4 a_Position;
      attribute vec2 a_TexCoord;
      varying vec2 v_TexCoord;
      void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }
      """,
      """
      #extension GL_OES_EGL_image_external : require
      precision mediump float;
      varying vec2 v_TexCoord;
      uniform samplerExternalOES sTexture;
      void main() { gl_FragColor = texture2D(sTexture, v_TexCoord); }
      """,
    )
    aPos = GLES20.glGetAttribLocation(program, "a_Position")
    aTex = GLES20.glGetAttribLocation(program, "a_TexCoord")
    texReady = false
  }

  fun draw(frame: Frame) {
    if (frame.hasDisplayGeometryChanged() || !texReady) {
      quad.position(0); tex.position(0)
      frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, tex)
      quad.position(0); tex.position(0)
      texReady = true
    }
    if (frame.timestamp == 0L) return
    GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    GLES20.glDepthMask(false)
    GLES20.glDisable(GLES20.GL_BLEND)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
    GLES20.glUseProgram(program)
    GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
    GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, tex)
    GLES20.glEnableVertexAttribArray(aPos)
    GLES20.glEnableVertexAttribArray(aTex)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    GLES20.glDisableVertexAttribArray(aPos)
    GLES20.glDisableVertexAttribArray(aTex)
    GLES20.glDepthMask(true)
  }
}

/** Glowing points: ARCore's feature points while it looks for surfaces, and a beacon on each pinned item. */
internal class PointRenderer {
  private var program = 0
  private var aPos = 0
  private var uMvp = 0
  private var uColor = 0
  private var uSize = 0
  private var buf = Gl.floats(4 * 2048)

  fun create() {
    program = Gl.program(
      """
      uniform mat4 u_MVP;
      uniform float u_Size;
      attribute vec4 a_Position;
      void main() { gl_Position = u_MVP * vec4(a_Position.xyz, 1.0); gl_PointSize = u_Size; }
      """,
      """
      precision mediump float;
      uniform vec4 u_Color;
      void main() {
        float d = length(gl_PointCoord - 0.5);
        if (d > 0.5) discard;
        gl_FragColor = vec4(u_Color.rgb, u_Color.a * pow(1.0 - d * 2.0, 1.4));
      }
      """,
    )
    aPos = GLES20.glGetAttribLocation(program, "a_Position")
    uMvp = GLES20.glGetUniformLocation(program, "u_MVP")
    uColor = GLES20.glGetUniformLocation(program, "u_Color")
    uSize = GLES20.glGetUniformLocation(program, "u_Size")
  }

  /** points: xyzw floats (ARCore's point cloud layout). */
  fun draw(points: FloatBuffer, count: Int, viewProj: FloatArray, rgba: FloatArray, sizePx: Float) {
    if (count <= 0) return
    GLES20.glEnable(GLES20.GL_BLEND)
    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
    GLES20.glDepthMask(false)
    GLES20.glUseProgram(program)
    GLES20.glUniformMatrix4fv(uMvp, 1, false, viewProj, 0)
    GLES20.glUniform4fv(uColor, 1, rgba, 0)
    GLES20.glUniform1f(uSize, sizePx)
    points.position(0)
    GLES20.glVertexAttribPointer(aPos, 4, GLES20.GL_FLOAT, false, 16, points)
    GLES20.glEnableVertexAttribArray(aPos)
    GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
    GLES20.glDisableVertexAttribArray(aPos)
    GLES20.glDepthMask(true)
  }

  fun drawOne(x: Float, y: Float, z: Float, viewProj: FloatArray, rgba: FloatArray, sizePx: Float) {
    buf.position(0)
    buf.put(x); buf.put(y); buf.put(z); buf.put(1f)
    draw(buf, 1, viewProj, rgba, sizePx)
  }
}
