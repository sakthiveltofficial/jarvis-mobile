package dev.jarvis.bridge.ar

import android.opengl.GLES20
import android.opengl.Matrix
import dev.jarvis.bridge.AudioOut
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The desktop's particle orb (ui/orb.js), floating in the room: a GPU particle cloud that morphs with what Jarvis
 * is doing (idle sphere, listening ring, thinking vortex, working gear), takes its colours from the mood, ripples
 * outward with Jarvis's voice and leans in while you talk.
 */
internal class OrbRenderer(private val count: Int = 2200) {
  private var program = 0
  private val from: FloatBuffer = Gl.floats(count * 3)
  private val to: FloatBuffer = Gl.floats(count * 3)
  private val seed: FloatBuffer = Gl.floats(count * 4)
  private var shape = ""
  private var morphStart = -10f
  private val loc = HashMap<String, Int>()

  // current look (eased toward the target every frame)
  private val c1 = floatArrayOf(0.56f, 0.82f, 0.42f)
  private val c2 = floatArrayOf(0.91f, 0.84f, 0.42f)
  private val c3 = floatArrayOf(1f, 1f, 1f)
  private var speed = 0.8f
  private var turb = 0.6f
  private var spark = 0.06f
  private var swirl = 0f
  private var face = 0f
  private var spin = 0f
  private var tts = 0f
  private var mic = 0f
  private var first = true

  private val modelView = FloatArray(16)

  fun create() {
    program = Gl.program(VERT, FRAG)
    for (a in listOf("aFrom", "aTo", "aSeed")) loc[a] = GLES20.glGetAttribLocation(program, a)
    for (u in listOf("uTime", "uFace", "uMix", "uLevel", "uMic", "uTurb", "uFlow", "uSpin", "uSize", "uScale", "uBreath",
      "uRipple", "uSparkle", "uSwirl", "uC1", "uC2", "uC3", "uModelView", "uProj", "uOpacity", "uRadius")) {
      loc[u] = GLES20.glGetUniformLocation(program, u)
    }
    val r = Random(7)
    seed.position(0)
    repeat(count) { seed.put(r.nextFloat()); seed.put(r.nextFloat()); seed.put(r.nextFloat()); seed.put(r.nextFloat()) }
    seed.position(0)
    shape = ""
    morphTo("sphere", 0f, instant = true)
  }

  private fun morphTo(s: String, t: Float, instant: Boolean = false) {
    if (s == shape) return
    val pts = SHAPES[s]?.invoke(count) ?: return
    // start the new morph from where the last one was heading
    to.position(0); from.position(0)
    if (instant || shape.isEmpty()) from.put(pts) else from.put(to)
    to.position(0); to.put(pts)
    from.position(0); to.position(0)
    shape = s
    morphStart = if (instant) -10f else t
  }

  /**
   * model: the orb's pose (column-major 4x4, metres). radius in metres. pxPerUnit converts "size" to pixels for the
   * current viewport.
   */
  fun draw(t: Float, dt: Float, model: FloatArray, view: FloatArray, proj: FloatArray, radius: Float, pxScale: Float) {
    // ----- state -> shape
    val st = OrbState.state()
    val want = when (st) {
      "listening", "transcribing" -> "ring"
      "thinking" -> "vortex"
      "acting" -> "gear"
      else -> "sphere"
    }
    morphTo(want, t)

    // ----- audio: rise fast, fall slower
    val ttsT = AudioOut.level()
    val micT = dev.jarvis.bridge.VoiceRecorder.level
    tts += (ttsT - tts) * if (ttsT > tts) 0.35f else 0.08f
    mic += (micT - mic) * if (micT > mic) 0.3f else 0.07f

    // ----- mood -> look (same rules as the desktop)
    val look = OrbState.MOODS[OrbState.moodKey()] ?: OrbState.MOODS.getValue("neutral")
    val ta = rgb(look.a); val tb = rgb(look.b); val tc = rgb(look.c)
    var sp = look.speed; var tu = look.turb; var sk = look.spark; var sw = 0f
    val busy = st == "thinking" || st == "acting"
    if (busy) {
      val v = OrbState.MOODS.getValue("thinking")
      lerp(ta, rgb(v.a), 0.65f); lerp(tb, rgb(v.b), 0.65f)
      sp = max(sp, 1.2f); tu = max(tu, 0.8f); sk = max(sk, 0.1f)
      sw = if (st == "thinking") 0.9f else 0f
    }
    val k = if (first) 1f else 1f - exp(-dt * 2.2f)
    first = false
    lerp(c1, ta, k); lerp(c2, tb, k); lerp(c3, tc, k)
    speed += (sp - speed) * k; turb += (tu - turb) * k; spark += (sk - spark) * k; swirl += (sw - swirl) * k
    spin += dt * (0.12f + 0.18f * speed + tts * 0.5f)
    val faces = !(shape == "sphere" || shape == "vortex")
    face += ((if (faces) 1f else 0f) - face) * (1f - exp(-dt * 3f))

    Matrix.multiplyMM(modelView, 0, view, 0, model, 0)

    GLES20.glEnable(GLES20.GL_BLEND)
    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)     // additive: reads as light, like a hologram
    GLES20.glDepthMask(false)
    GLES20.glUseProgram(program)
    u1("uTime", t); u1("uFace", face); u1("uMix", min(1f, (t - morphStart) / 1.4f)); u1("uLevel", min(1.2f, tts))
    u1("uMic", min(1f, mic)); u1("uTurb", turb + tts * 0.6f); u1("uFlow", speed); u1("uSpin", spin)
    u1("uSize", 7f); u1("uScale", pxScale); u1("uBreath", if (st == "idle") 1f else 0.4f)
    u1("uRipple", if (st == "speaking") 1f else 0.3f); u1("uSparkle", spark + tts * 0.08f); u1("uSwirl", swirl)
    u1("uOpacity", 0.9f); u1("uRadius", radius)
    GLES20.glUniform3fv(loc.getValue("uC1"), 1, c1, 0)
    GLES20.glUniform3fv(loc.getValue("uC2"), 1, c2, 0)
    GLES20.glUniform3fv(loc.getValue("uC3"), 1, c3, 0)
    GLES20.glUniformMatrix4fv(loc.getValue("uModelView"), 1, false, modelView, 0)
    GLES20.glUniformMatrix4fv(loc.getValue("uProj"), 1, false, proj, 0)
    attr("aFrom", from, 3); attr("aTo", to, 3); attr("aSeed", seed, 4)
    GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
    for (a in listOf("aFrom", "aTo", "aSeed")) GLES20.glDisableVertexAttribArray(loc.getValue(a))
    GLES20.glDepthMask(true)
  }

  private fun u1(name: String, v: Float) = GLES20.glUniform1f(loc.getValue(name), v)

  private fun attr(name: String, b: FloatBuffer, n: Int) {
    val l = loc.getValue(name)
    b.position(0)
    GLES20.glVertexAttribPointer(l, n, GLES20.GL_FLOAT, false, 0, b)
    GLES20.glEnableVertexAttribArray(l)
  }

  private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
  private fun lerp(a: FloatArray, b: FloatArray, k: Float) { for (i in 0..2) a[i] += (b[i] - a[i]) * k }

  companion object {
    private fun rnd(a: Float = 1f) = (Random.nextFloat() * 2f - 1f) * a
    private fun gauss(): Float {
      var u = 0.0
      var v = 0.0
      while (u == 0.0) u = Random.nextDouble()
      while (v == 0.0) v = Random.nextDouble()
      return (sqrt(-2.0 * ln(u)) * cos(2 * PI * v)).toFloat()
    }

    private fun fill(n: Int, f: (Int, Int) -> FloatArray): FloatArray {
      val out = FloatArray(n * 3)
      for (i in 0 until n) { val v = f(i, n); out[i * 3] = v[0]; out[i * 3 + 1] = v[1]; out[i * 3 + 2] = v[2] }
      return out
    }

    private fun rotX(p: FloatArray, a: Float): FloatArray {
      val c = cos(a); val s = sin(a)
      var i = 0
      while (i < p.size) { val y = p[i + 1]; val z = p[i + 2]; p[i + 1] = y * c - z * s; p[i + 2] = y * s + z * c; i += 3 }
      return p
    }

    val SHAPES: Map<String, (Int) -> FloatArray> = mapOf(
      "sphere" to { n -> fill(n) { i, nn ->
        val y = 1f - (2f * (i + 0.5f)) / nn; val r = sqrt(max(0f, 1f - y * y)); val th = i * 2.39996323f
        val k = 0.97f + Random.nextFloat() * 0.06f
        floatArrayOf(cos(th) * r * k, y * k, sin(th) * r * k)
      } },
      "ring" to { n -> rotX(fill(n) { _, _ ->
        val t = Random.nextFloat() * 6.2832f; val a = Random.nextFloat() * 6.2832f
        val tube = 0.05f + 0.06f * abs(gauss()) * 0.5f; val rr = 1.05f + cos(a) * tube
        floatArrayOf(cos(t) * rr, sin(t) * rr, sin(a) * tube * 1.6f)
      }, 0.32f) },
      "vortex" to { n -> rotX(fill(n) { _, _ ->
        val r = Random.nextFloat().toDouble().pow(0.7).toFloat() * 1.3f; val arm = Random.nextInt(3)
        val t = r * 4.2f + arm * 2.0944f + gauss() * 0.22f * (1.2f - r)
        floatArrayOf(cos(t) * r, gauss() * 0.07f * (1.3f - r) + 0.02f * rnd(), sin(t) * r)
      }, 1.05f) },
      "gear" to { n -> fill(n) { _, _ ->
        if (Random.nextFloat() < 0.7f) {
          val t = Random.nextFloat() * 6.2832f; val tooth = if (cos(t * 9) > 0.15f) 1f else 0f
          val r = 0.82f + tooth * 0.2f + (if (Random.nextFloat() < 0.2f) Random.nextFloat() * 0.2f * tooth else 0f)
          floatArrayOf(cos(t) * r + rnd(0.01f), sin(t) * r + rnd(0.01f), rnd(0.06f))
        } else {
          val t = Random.nextFloat() * 6.2832f
          floatArrayOf(cos(t) * 0.32f + rnd(0.012f), sin(t) * 0.32f + rnd(0.012f), rnd(0.05f))
        }
      } },
    )

    // Ported from ui/orb.js (GLSL ES 1.0). Positions are unit-radius; uRadius scales them to metres.
    private const val VERT = """
      attribute vec3 aFrom;
      attribute vec3 aTo;
      attribute vec4 aSeed;
      uniform mat4 uModelView;
      uniform mat4 uProj;
      uniform float uTime, uFace, uMix, uLevel, uMic, uTurb, uFlow, uSpin, uSize, uScale, uBreath, uRipple, uSparkle, uSwirl, uRadius;
      uniform vec3 uC1, uC2, uC3;
      varying vec3 vColor;
      varying float vAlpha;

      vec3 flow(vec3 p, float t) {
        vec3 a = vec3(sin(p.y * 1.7 + t * 0.9 + aSeed.y * 6.28), sin(p.z * 1.9 + t * 0.7 + aSeed.z * 6.28), sin(p.x * 1.5 + t * 1.1 + aSeed.w * 6.28));
        vec3 b = vec3(sin(p.z * 3.1 - t * 1.3), sin(p.x * 2.9 + t * 1.2), sin(p.y * 3.3 - t * 0.8));
        return a * 0.65 + b * 0.35;
      }
      vec2 rot(vec2 v, float a) { float c = cos(a), s = sin(a); return vec2(c * v.x - s * v.y, s * v.x + c * v.y); }

      void main() {
        float m = clamp(uMix * 1.5 - aSeed.x * 0.5, 0.0, 1.0);
        m = m * m * (3.0 - 2.0 * m);
        vec3 p = mix(aFrom, aTo, m);
        float travel = sin(m * 3.14159);
        float r = length(p) + 1e-4;
        vec3 dir = p / r;
        p *= 1.0 + sin(uTime * 1.3 + aSeed.x * 0.6) * 0.025 * uBreath;
        p += flow(p * 1.3, uTime * uFlow) * (0.035 + 0.05 * uTurb + 0.22 * travel);
        p.xz = rot(p.xz, uSwirl * uTime * (0.6 + 0.8 / (0.4 + r)));
        float wave = sin(r * 11.0 - uTime * 7.0 + aSeed.y * 1.5) * 0.5 + 0.5;
        p += dir * uLevel * (0.08 + 0.2 * aSeed.y + uRipple * 0.2 * wave);
        p *= 1.0 - uMic * 0.14;
        p.z += uMic * 0.25 * (0.5 + aSeed.z);
        p.xz = rot(p.xz, mix(uSpin, sin(uTime * 0.6) * 0.35, uFace));

        vec4 mv = uModelView * vec4(p * uRadius, 1.0);
        gl_Position = uProj * mv;
        float size = uSize * (0.55 + aSeed.z * 0.9) * (1.0 + uLevel * 0.3 + travel * 0.3);
        gl_PointSize = max(1.0, size * uScale / max(0.05, -mv.z));

        float mixc = clamp(aSeed.w + 0.25 * sin(uTime * 0.7 + r * 3.0 + aSeed.x * 6.28), 0.0, 1.0);
        vec3 col = mix(uC1, uC2, mixc);
        float spark = step(1.0 - uSparkle, aSeed.y) * (0.5 + 0.5 * sin(uTime * 9.0 + aSeed.x * 40.0));
        col = mix(col, uC3, spark * 0.8);
        vColor = col;
        vAlpha = clamp(0.55 + 0.45 * (p.z + 1.0) * 0.5, 0.25, 1.0) * (0.75 + spark * 0.25);
      }
    """

    private const val FRAG = """
      precision mediump float;
      uniform float uOpacity;
      varying vec3 vColor;
      varying float vAlpha;
      void main() {
        float d = length(gl_PointCoord - 0.5);
        if (d > 0.5) discard;
        float a = pow(1.0 - d * 2.0, 1.6);
        gl_FragColor = vec4(vColor, a * vAlpha * uOpacity);
      }
    """
  }
}
