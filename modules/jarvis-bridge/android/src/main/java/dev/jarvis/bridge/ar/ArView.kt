package dev.jarvis.bridge.ar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.ImageInsufficientQualityException
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.Promise
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt

/** Recent camera frames (JPEG), so a request to the PC can reference one by id instead of moving it through JS. */
object ArCaptures {
  private val map = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 6
  }

  @Synchronized fun put(id: String, jpeg: ByteArray) { map[id] = jpeg }
  @Synchronized fun get(id: String): ByteArray? = map[id]
}

/** Where things are in the room: what a frame saw, so a box the PC finds later can be turned into a 3D anchor. */
private class Capture(
  val view: FloatArray, val proj: FloatArray, val pose: Pose, val tracking: Boolean,
  val depth: ShortArray?, val dw: Int, val dh: Int, val dStride: Int,
  /** view-normalized -> image-normalized: origin, +u step, +v step */
  val affine: FloatArray?,
)

private class Target(val id: String, val image: String, val widthM: Float, val offX: Float, val offZ: Float)

/**
 * Jarvis AR: an ARCore session drawn with OpenGL ES. Renders the camera, the particle orb and a glowing beacon on
 * each anchored item; reports where those items are on screen (onFrame) so React Native can draw notes and
 * smart-home controls next to them. Pinned spots are re-found in later sessions with ARCore Augmented Images
 * (a photo of the spot is the marker), so nothing goes to a cloud service.
 */
class ArView(context: Context, appContext: AppContext) : ExpoView(context, appContext), GLSurfaceView.Renderer {
  companion object {
    private val worker = Executors.newSingleThreadExecutor()
  }

  private val tag = "JarvisAR"
  private val onArStatus by EventDispatcher()
  private val onFrame by EventDispatcher()
  private val onTargetFound by EventDispatcher()

  private val main = Handler(Looper.getMainLooper())
  private val density = resources.displayMetrics.density

  private val gl = GLSurfaceView(context).also {
    it.preserveEGLContextOnPause = true
    it.setEGLContextClientVersion(2)
    it.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
    it.setRenderer(this)
    it.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
  }

  @Volatile private var session: Session? = null
  private var config: Config? = null
  private val bg = BackgroundRenderer()
  private val points = PointRenderer()
  private val orb = OrbRenderer()
  private var glReady = false
  private var textureBound = false
  private var geometryDirty = true
  private var vw = 1
  private var vh = 1
  private var lastFrame: Frame? = null
  private var lastTracking = false

  private val anchors = ConcurrentHashMap<String, Anchor>()
  private var orbAnchor: Anchor? = null
  @Volatile var orbVisible = true
  @Volatile var showPoints = true
  private val captures = object : LinkedHashMap<String, Capture>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Capture>?) = size > 6
  }
  private var pendingCapture: Pair<Int, Promise>? = null

  private var targets: Map<String, Target> = emptyMap()
  private var targetsJson = ""
  @Volatile private var pendingDb: AugmentedImageDatabase? = null

  private val view = FloatArray(16)
  private val proj = FloatArray(16)
  private val vp = FloatArray(16)
  private val model = FloatArray(16)
  private val t0 = SystemClock.elapsedRealtime()
  private var lastT = 0f
  private var lastEmit = 0L
  private var lastStatus = ""

  init {
    addView(gl)
  }

  // ------------------------------------------------------------------ layout & lifecycle
  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    measureChild(gl, widthMeasureSpec, heightMeasureSpec)
    setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    gl.measure(MeasureSpec.makeMeasureSpec(right - left, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(bottom - top, MeasureSpec.EXACTLY))
    gl.layout(0, 0, right - left, bottom - top)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    startSession()
    gl.onResume()
  }

  override fun onDetachedFromWindow() {
    stopSession()
    super.onDetachedFromWindow()
  }

  private fun status(state: String, message: String = "") {
    val key = "$state|$message"
    if (key == lastStatus) return
    lastStatus = key
    main.post { onArStatus(mapOf("state" to state, "message" to message)) }
  }

  private fun startSession() {
    if (session != null) return
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      status("error", "Jarvis needs the camera for AR."); return
    }
    try {
      val s = Session(context)
      val c = Config(s)
      c.focusMode = Config.FocusMode.AUTO
      c.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
      c.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
      c.lightEstimationMode = Config.LightEstimationMode.DISABLED
      if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) c.depthMode = Config.DepthMode.AUTOMATIC
      s.configure(c)
      s.resume()
      config = c
      session = s
      textureBound = false
      geometryDirty = true
      status("starting", if (c.depthMode == Config.DepthMode.AUTOMATIC) "depth" else "")
      if (targetsJson.isNotEmpty()) buildTargets()
    } catch (e: Exception) {
      Log.w(tag, "AR session failed", e)
      status("error", when (e) {
        is com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException -> "Install Google Play Services for AR to use AR."
        is com.google.ar.core.exceptions.UnavailableApkTooOldException -> "Update Google Play Services for AR."
        is com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException -> "This phone doesn't support ARCore."
        is CameraNotAvailableException -> "The camera is busy in another app."
        else -> e.message ?: e.javaClass.simpleName
      })
    }
  }

  private fun stopSession() {
    gl.onPause()                         // waits for the GL thread to stop drawing
    val s = session ?: return
    session = null
    lastFrame = null
    for (a in anchors.values) try { a.detach() } catch (_: Exception) {}
    anchors.clear()
    orbAnchor = null
    pendingCapture?.second?.reject("E_AR", "AR closed", null)
    pendingCapture = null
    try { s.pause() } catch (_: Exception) {}
    worker.execute { try { s.close() } catch (_: Exception) {} }
  }

  // ------------------------------------------------------------------ props
  fun setTargets(json: String) {
    if (json == targetsJson) return
    targetsJson = json
    buildTargets()
  }

  /** Builds the Augmented Images database (photos of pinned spots) off the GL thread, then applies it. */
  private fun buildTargets() {
    val s = session ?: return
    val json = targetsJson
    worker.execute {
      val list = HashMap<String, Target>()
      try {
        val arr = JSONArray(json.ifEmpty { "[]" })
        val db = AugmentedImageDatabase(s)
        for (i in 0 until arr.length()) {
          val o = arr.getJSONObject(i)
          val t = Target(o.getString("id"), o.optString("image"), o.optDouble("widthM", 0.3).toFloat(),
            o.optDouble("offX", 0.0).toFloat(), o.optDouble("offZ", 0.0).toFloat())
          val f = File(t.image.removePrefix("file://"))
          if (!f.isFile) continue
          val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath) ?: continue
          try {
            db.addImage(t.id, bmp, t.widthM)
            list[t.id] = t
          } catch (_: Exception) {
          }
        }
        targets = list
        pendingDb = db
      } catch (e: Exception) {
        Log.w(tag, "targets", e)
      }
    }
  }

  // ------------------------------------------------------------------ GL
  override fun onSurfaceCreated(unused: GL10?, cfg: EGLConfig?) {
    GLES20.glClearColor(0f, 0f, 0f, 1f)
    try {
      bg.create(); points.create(); orb.create()
      glReady = true
      textureBound = false
    } catch (e: Exception) {
      Log.e(tag, "GL init", e)
      status("error", "Graphics failed to start: ${e.message}")
    }
  }

  override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
    GLES20.glViewport(0, 0, width, height)
    vw = width; vh = height
    geometryDirty = true
  }

  override fun onDrawFrame(unused: GL10?) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
    val s = session ?: return
    if (!glReady) return
    try {
      drawFrame(s)
    } catch (e: CameraNotAvailableException) {
      status("error", "The camera is busy in another app.")
    } catch (e: Throwable) {
      Log.w(tag, "frame", e)
    }
  }

  private fun drawFrame(s: Session) {
    if (!textureBound) { s.setCameraTextureName(bg.textureId); textureBound = true }
    if (geometryDirty) { s.setDisplayGeometry(display?.rotation ?: 0, vw, vh); geometryDirty = false }
    pendingDb?.let { db ->
      pendingDb = null
      config?.let { c -> c.augmentedImageDatabase = db; s.configure(c) }
    }
    val frame = s.update()
    lastFrame = frame
    val cam = frame.camera
    bg.draw(frame)

    val nowMs = SystemClock.elapsedRealtime()
    val t = (nowMs - t0) / 1000f
    val dt = (t - lastT).coerceIn(0f, 0.1f)
    lastT = t
    val tracking = cam.trackingState == TrackingState.TRACKING
    if (tracking) {
      cam.getProjectionMatrix(proj, 0, 0.05f, 60f)
      cam.getViewMatrix(view, 0)
      Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
    }
    pendingCapture?.let { (maxSide, promise) ->
      pendingCapture = null
      capture(frame, tracking, maxSide, promise)
    }

    if (!tracking) {
      lastTracking = false
      emitFrame(frame, nowMs, false)
      return
    }
    if (!lastTracking) status("tracking")
    lastTracking = true

    // pinned spots seen again (Augmented Images)
    if (targets.isNotEmpty()) {
      for (img in frame.getUpdatedTrackables(AugmentedImage::class.java)) {
        if (img.trackingState != TrackingState.TRACKING || img.trackingMethod != AugmentedImage.TrackingMethod.FULL_TRACKING) continue
        val tgt = targets[img.name] ?: continue
        if (anchors.containsKey(tgt.id)) continue
        anchors[tgt.id] = img.createAnchor(img.centerPose.compose(Pose.makeTranslation(tgt.offX, 0f, tgt.offZ)))
        main.post { onTargetFound(mapOf("id" to tgt.id)) }
      }
    }

    // feature points while ARCore is still learning the room
    if (showPoints) {
      val pc = frame.acquirePointCloud()
      try {
        val buf = pc.points
        points.draw(buf, buf.remaining() / 4, vp, floatArrayOf(0.85f, 0.95f, 0.6f, 0.55f), 6f * density / 2.6f)
      } finally {
        pc.release()
      }
    }

    // beacons on pinned items
    val pulse = 0.65f + 0.35f * sin(t * 3f)
    for ((id, a) in anchors) {
      if (a.trackingState != TrackingState.TRACKING || id.startsWith("seen:")) continue   // seen: = remembered objects, shown only when found
      val p = a.pose.compose(Pose.makeTranslation(0f, 0.02f, 0f))
      val col = when {
        id.startsWith("ha:") -> floatArrayOf(0.84f, 0.95f, 0.29f, pulse)
        id.startsWith("obj:") -> floatArrayOf(1f, 0.7f, 0.28f, pulse)
        else -> floatArrayOf(0.62f, 0.85f, 1f, pulse)
      }
      points.drawOne(p.tx(), p.ty(), p.tz(), vp, col, 34f * density / 2.6f)
    }

    // the orb
    if (orbVisible) {
      if (orbAnchor == null) {
        // first time: float it ~80 cm ahead, a little below eye level
        val p = cam.displayOrientedPose.compose(Pose.makeTranslation(0f, -0.08f, -0.8f))
        orbAnchor = s.createAnchor(Pose.makeTranslation(p.tx(), p.ty(), p.tz()))
      }
      val a = orbAnchor
      if (a != null && a.trackingState == TrackingState.TRACKING) {
        val cp = cam.pose
        val bob = sin(t * 1.1f) * 0.015f
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, a.pose.tx(), a.pose.ty() + bob, a.pose.tz())
        val yaw = Math.toDegrees(atan2((cp.tx() - a.pose.tx()).toDouble(), (cp.tz() - a.pose.tz()).toDouble())).toFloat()
        Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
        orb.draw(t, dt, model, view, proj, 0.16f, vh / 3000f)
      }
    }
    emitFrame(frame, nowMs, true)
  }

  /** Screen positions (dp) of the orb and every anchored item, ~20 times a second. */
  private fun emitFrame(frame: Frame, nowMs: Long, tracking: Boolean) {
    if (nowMs - lastEmit < if (tracking) 50 else 250) return
    lastEmit = nowMs
    val items = ArrayList<Map<String, Any>>()
    if (tracking) {
      val cp = frame.camera.pose
      val all = HashMap(anchors)
      val oa = orbAnchor
      if (oa != null && orbVisible) all["orb"] = oa
      for ((id, a) in all) {
        if (a.trackingState != TrackingState.TRACKING) continue
        val p = if (id == "orb") a.pose else a.pose.compose(Pose.makeTranslation(0f, 0.02f, 0f))
        val v = floatArrayOf(p.tx(), p.ty(), p.tz(), 1f)
        val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, vp, 0, v, 0)
        val dx = p.tx() - cp.tx(); val dy = p.ty() - cp.ty(); val dz = p.tz() - cp.tz()
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (clip[3] <= 0.01f) {
          items.add(mapOf("id" to id, "x" to -1.0, "y" to -1.0, "dist" to dist.toDouble(), "on" to false)); continue
        }
        val nx = clip[0] / clip[3]; val ny = clip[1] / clip[3]
        val x = (nx + 1f) / 2f * vw / density
        val y = (1f - ny) / 2f * vh / density
        val on = nx in -1.1f..1.1f && ny in -1.1f..1.1f
        items.add(mapOf("id" to id, "x" to x.toDouble(), "y" to y.toDouble(), "dist" to dist.toDouble(), "on" to on))
      }
    }
    val cam = frame.camera
    val reason = when (cam.trackingFailureReason) {
      TrackingFailureReason.NONE -> ""
      TrackingFailureReason.INSUFFICIENT_LIGHT -> "It's too dark here."
      TrackingFailureReason.EXCESSIVE_MOTION -> "Move a little slower."
      TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point at something with more detail."
      TrackingFailureReason.CAMERA_UNAVAILABLE -> "The camera is busy."
      else -> "Move your phone slowly around the room."
    }
    var planes = 0
    session?.let { s -> for (pl in s.getAllTrackables(Plane::class.java)) if (pl.trackingState == TrackingState.TRACKING && pl.subsumedBy == null) planes++ }
    val ev = mapOf("tracking" to tracking, "reason" to reason, "planes" to planes, "items" to items)
    main.post { onFrame(ev) }
  }

  // ------------------------------------------------------------------ capture
  fun requestCapture(maxSide: Int, promise: Promise) {
    gl.queueEvent {
      if (session == null) { promise.reject("E_AR", "AR isn't running", null); return@queueEvent }
      pendingCapture?.second?.reject("E_AR", "Replaced by a newer capture", null)
      pendingCapture = maxSide to promise
    }
  }

  /** Reads the camera image as drawn (before the orb), plus what's needed to place things found in it later. */
  private fun capture(frame: Frame, tracking: Boolean, maxSide: Int, promise: Promise) {
    val w = vw; val h = vh
    val px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
    GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
    val id = UUID.randomUUID().toString().substring(0, 12)

    var depth: ShortArray? = null
    var dw = 0; var dh = 0; var ds = 0
    if (tracking && config?.depthMode == Config.DepthMode.AUTOMATIC) {
      try {
        val img = frame.acquireDepthImage16Bits()
        try {
          val pl = img.planes[0]
          val sb = pl.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
          depth = ShortArray(sb.remaining()).also { sb.get(it) }
          dw = img.width; dh = img.height; ds = pl.rowStride / 2
        } finally { img.close() }
      } catch (_: Exception) {
      }
    }
    var affine: FloatArray? = null
    try {
      val inp = Gl.floats(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f))
      val out = Gl.floats(6)
      frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, inp, Coordinates2d.IMAGE_NORMALIZED, out)
      val o = FloatArray(6).also { out.position(0); out.get(it) }
      affine = floatArrayOf(o[0], o[1], o[2] - o[0], o[3] - o[1], o[4] - o[0], o[5] - o[1])
    } catch (_: Exception) {
    }
    captures[id] = Capture(view.clone(), proj.clone(), frame.camera.displayOrientedPose, tracking, depth, dw, dh, ds, affine)

    worker.execute {
      try {
        val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        px.position(0)
        raw.copyPixelsFromBuffer(px)
        val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
        val m = android.graphics.Matrix().apply { postScale(scale, -scale) }   // GL rows are bottom-up
        val bmp = Bitmap.createBitmap(raw, 0, 0, w, h, m, true)
        raw.recycle()
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, bos)
        val jpeg = bos.toByteArray()
        ArCaptures.put(id, jpeg)
        promise.resolve(mapOf("id" to id, "width" to bmp.width, "height" to bmp.height, "tracking" to tracking,
          "thumb" to Base64.encodeToString(thumb(bmp), Base64.NO_WRAP)))
        bmp.recycle()
      } catch (e: Exception) {
        promise.reject("E_CAPTURE", e.message ?: "Capture failed", e)
      }
    }
  }

  private fun thumb(b: Bitmap): ByteArray {
    val s = 240f / maxOf(b.width, b.height)
    val t = Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    val bos = ByteArrayOutputStream()
    t.compress(Bitmap.CompressFormat.JPEG, 70, bos)
    if (t !== b) t.recycle()
    return bos.toByteArray()
  }

  // ------------------------------------------------------------------ anchors
  private fun ray(c: Capture, u: Float, v: Float): FloatArray {
    val ndcX = 2f * u - 1f
    val ndcY = 1f - 2f * v
    return floatArrayOf((ndcX + c.proj[8]) / c.proj[0], (ndcY + c.proj[9]) / c.proj[5], -1f)
  }

  /** Turn a point in a captured frame (fractions of the image) into an anchor: depth, then surfaces, then a guess. */
  fun anchorFromCapture(captureId: String, u: Double, v: Double, id: String, promise: Promise) {
    gl.queueEvent {
      val s = session
      val c = captures[captureId]
      if (s == null || c == null || !c.tracking) {
        promise.resolve(mapOf("ok" to false, "error" to "That view wasn't tracked; look around a little and try again.")); return@queueEvent
      }
      try {
        val d = ray(c, u.toFloat(), v.toFloat())
        var point: FloatArray? = null
        var method = "guess"
        val depth = c.depth
        val af = c.affine
        if (depth != null && af != null) {
          val iu = af[0] + u.toFloat() * af[2] + v.toFloat() * af[4]
          val iv = af[1] + u.toFloat() * af[3] + v.toFloat() * af[5]
          val mm = sampleDepth(depth, c.dw, c.dh, c.dStride, (iu * c.dw).toInt(), (iv * c.dh).toInt())
          if (mm in 150..8000) {
            val m = mm / 1000f
            point = c.pose.transformPoint(floatArrayOf(d[0] * m, d[1] * m, -m)); method = "depth"
          }
        }
        if (point == null) {
          val len = sqrt(d[0] * d[0] + d[1] * d[1] + 1f)
          val dir = c.pose.rotateVector(floatArrayOf(d[0] / len, d[1] / len, -1f / len))
          val o = c.pose.translation
          var best = Float.MAX_VALUE
          for (pl in s.getAllTrackables(Plane::class.java)) {
            if (pl.trackingState != TrackingState.TRACKING || pl.subsumedBy != null) continue
            val cp = pl.centerPose
            val n = cp.yAxis
            val den = dir[0] * n[0] + dir[1] * n[1] + dir[2] * n[2]
            if (kotlin.math.abs(den) < 1e-4f) continue
            val tt = ((cp.tx() - o[0]) * n[0] + (cp.ty() - o[1]) * n[1] + (cp.tz() - o[2]) * n[2]) / den
            if (tt < 0.15f || tt > 8f || tt >= best) continue
            val hit = floatArrayOf(o[0] + dir[0] * tt, o[1] + dir[1] * tt, o[2] + dir[2] * tt)
            if (pl.isPoseInPolygon(Pose.makeTranslation(hit))) { best = tt; point = hit; method = "surface" }
          }
          if (point == null) point = floatArrayOf(o[0] + dir[0] * 1.5f, o[1] + dir[1] * 1.5f, o[2] + dir[2] * 1.5f)
        }
        anchors.remove(id)?.detach()
        anchors[id] = s.createAnchor(Pose.makeTranslation(point))
        promise.resolve(mapOf("ok" to true, "method" to method))
      } catch (e: Exception) {
        promise.resolve(mapOf("ok" to false, "error" to (e.message ?: "Couldn't place it")))
      }
    }
  }

  private fun sampleDepth(d: ShortArray, w: Int, h: Int, stride: Int, x: Int, y: Int): Int {
    val vals = ArrayList<Int>()
    for (yy in (y - 3).coerceAtLeast(0)..(y + 3).coerceAtMost(h - 1)) {
      for (xx in (x - 3).coerceAtLeast(0)..(x + 3).coerceAtMost(w - 1)) {
        val i = yy * stride + xx
        if (i in d.indices) { val v = d[i].toInt() and 0xFFFF; if (v > 0) vals.add(v) }
      }
    }
    if (vals.isEmpty()) return -1
    vals.sort()
    return vals[vals.size / 2]
  }

  /** Hit-test a tap (dp) against what ARCore knows: surfaces, depth, feature points. */
  private fun hit(frame: Frame, x: Float, y: Float) =
    frame.hitTest(x * density, y * density).firstOrNull { h ->
      when (val tr = h.trackable) {
        is Plane -> tr.isPoseInPolygon(h.hitPose)
        is DepthPoint -> true
        is Point -> tr.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
        else -> false
      }
    }

  fun placeOrb(x: Double, y: Double, promise: Promise) {
    gl.queueEvent {
      val s = session
      val f = lastFrame
      if (s == null || f == null || f.camera.trackingState != TrackingState.TRACKING) {
        promise.resolve(mapOf("ok" to false, "error" to "Still finding the room. Move your phone slowly.")); return@queueEvent
      }
      val pose = if (x >= 0 && y >= 0) hit(f, x.toFloat(), y.toFloat())?.hitPose else null
      val p = if (pose != null) Pose.makeTranslation(pose.tx(), pose.ty() + 0.25f, pose.tz())
      else f.camera.displayOrientedPose.compose(Pose.makeTranslation(0f, -0.08f, -0.8f)).let { Pose.makeTranslation(it.tx(), it.ty(), it.tz()) }
      orbAnchor?.detach()
      orbAnchor = s.createAnchor(p)
      promise.resolve(mapOf("ok" to true, "onSurface" to (pose != null)))
    }
  }

  /**
   * Pin something at a tapped spot. With remember=true it also saves a photo of the spot (a crop of the camera
   * image around the tap) that ARCore can recognise later, so the note reappears when you come back.
   */
  fun pinAt(x: Double, y: Double, id: String, remember: Boolean, promise: Promise) {
    gl.queueEvent {
      val s = session
      val f = lastFrame
      if (s == null || f == null || f.camera.trackingState != TrackingState.TRACKING) {
        promise.resolve(mapOf("ok" to false, "error" to "Still finding the room. Move your phone slowly.")); return@queueEvent
      }
      val h = hit(f, x.toFloat(), y.toFloat())
      if (h == null) {
        promise.resolve(mapOf("ok" to false, "error" to "Point at a surface (a desk, the fridge door, a wall) and tap it.")); return@queueEvent
      }
      anchors.remove(id)?.detach()
      anchors[id] = h.createAnchor()
      val out = HashMap<String, Any>(mapOf("ok" to true))
      if (remember) {
        try {
          out.putAll(snapshotSpot(s, f, x.toFloat() * density, y.toFloat() * density, h.distance, id))
        } catch (e: Exception) {
          out["remember"] = false
          out["why"] = e.message ?: "camera image unavailable"
        }
      }
      promise.resolve(out)
    }
  }

  private fun snapshotSpot(s: Session, f: Frame, px: Float, py: Float, dist: Float, id: String): Map<String, Any> {
    val img = f.acquireCameraImage()
    try {
      val w = img.width; val h = img.height
      val inp = Gl.floats(floatArrayOf(px, py))
      val out = Gl.floats(2)
      f.transformCoordinates2d(Coordinates2d.VIEW, inp, Coordinates2d.IMAGE_PIXELS, out)
      val ix = out.get(0); val iy = out.get(1)
      val side = (minOf(w, h) * 0.75f).toInt()
      val cx = ix.toInt().coerceIn(side / 2, w - side / 2)
      val cy = iy.toInt().coerceIn(side / 2, h - side / 2)
      val plane = img.planes[0]
      val buf = plane.buffer
      val rs = plane.rowStride; val ps = plane.pixelStride
      val pixels = IntArray(side * side)
      for (r in 0 until side) {
        val row = (cy - side / 2 + r) * rs
        for (c in 0 until side) {
          val yv = buf.get(row + (cx - side / 2 + c) * ps).toInt() and 0xFF
          pixels[r * side + c] = (0xFF shl 24) or (yv shl 16) or (yv shl 8) or yv
        }
      }
      val bmp = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
      val fx = f.camera.imageIntrinsics.focalLength[0]
      val widthM = (side / fx * dist).coerceIn(0.05f, 3f)
      val good = try {
        AugmentedImageDatabase(s).addImage(id, bmp, widthM); true
      } catch (_: ImageInsufficientQualityException) {
        false
      }
      if (!good) return mapOf("remember" to false, "why" to "This spot has too little detail to find again. Pin it on something with a pattern or objects around it.")
      val dir = File(context.filesDir, "ar").apply { mkdirs() }
      val file = File(dir, "$id.png")
      file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
      return mapOf("remember" to true, "image" to file.absolutePath, "widthM" to widthM.toDouble(),
        "offX" to ((ix - cx) / side * widthM).toDouble(), "offZ" to ((iy - cy) / side * widthM).toDouble())
    } finally {
      img.close()
    }
  }

  fun removeAnchor(id: String) {
    gl.queueEvent { anchors.remove(id)?.detach() }
  }

  fun clearAnchors(prefix: String) {
    gl.queueEvent {
      for (k in anchors.keys.filter { it.startsWith(prefix) }) anchors.remove(k)?.detach()
    }
  }

  fun hideOrb() {
    gl.queueEvent { orbAnchor?.detach(); orbAnchor = null }
  }
}
