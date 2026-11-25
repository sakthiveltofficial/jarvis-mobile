package dev.jarvis.bridge

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread

/**
 * Screen capture fallback through MediaProjection, for Android 10 and for apps whose windows
 * the accessibility screenshot can't read. Captures straight at "view space" size.
 */
object ScreenCapture {
  private var projection: MediaProjection? = null
  private var display: VirtualDisplay? = null
  private var reader: ImageReader? = null
  private var latest: Image? = null
  private val thread = HandlerThread("jarvis-capture").apply { start() }
  private val handler = Handler(thread.looper)

  val active get() = projection != null

  @Synchronized
  fun begin(ctx: Context, resultCode: Int, data: Intent) {
    stop()
    val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val mp = mpm.getMediaProjection(resultCode, data) ?: throw IllegalStateException("Screen capture was refused")
    mp.registerCallback(object : MediaProjection.Callback() {
      override fun onStop() {
        stop()
      }
    }, handler)
    val size = ScreenTree.screenSize(ctx)
    val s = ScreenTree.scale(ctx)
    val w = (size.x * s).toInt().coerceAtLeast(2)
    val h = (size.y * s).toInt().coerceAtLeast(2)
    val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
    r.setOnImageAvailableListener({ rd ->
      val img = try {
        rd.acquireLatestImage()
      } catch (_: Exception) {
        null
      } ?: return@setOnImageAvailableListener
      synchronized(this) {
        latest?.close()
        latest = img
      }
    }, handler)
    val dpi = ctx.resources.displayMetrics.densityDpi
    display = mp.createVirtualDisplay("jarvis-capture", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
      r.surface, null, handler)
    reader = r
    projection = mp
  }

  @Synchronized
  fun grab(): Bitmap? {
    val img = latest ?: return null
    val plane = img.planes[0]
    val pixelStride = plane.pixelStride
    val rowPadding = plane.rowStride - pixelStride * img.width
    val bmp = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
    plane.buffer.rewind()
    bmp.copyPixelsFromBuffer(plane.buffer)
    return if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
  }

  @Synchronized
  fun stop() {
    try {
      latest?.close()
    } catch (_: Exception) {
    }
    latest = null
    display?.release(); display = null
    reader?.close(); reader = null
    val p = projection
    projection = null
    try {
      p?.stop()
    } catch (_: Exception) {
    }
  }
}
