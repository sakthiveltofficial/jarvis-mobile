package dev.jarvis.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reads what's on screen and acts on it for Jarvis: UI tree, taps, gestures, typing, global keys,
 * and (Android 11+) screenshots without a capture prompt.
 */
class JarvisAccessibilityService : AccessibilityService() {
  companion object {
    @Volatile var instance: JarvisAccessibilityService? = null
  }

  @Volatile var foregroundPackage: String = ""
  @Volatile var foregroundClass: String = ""

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
      event.packageName?.toString()?.let { foregroundPackage = it }
      event.className?.toString()?.let { foregroundClass = it }
    }
  }

  override fun onInterrupt() {}

  override fun onDestroy() {
    instance = null
    super.onDestroy()
  }

  override fun onUnbind(intent: android.content.Intent?): Boolean {
    instance = null
    return super.onUnbind(intent)
  }

  // ---------------------------------------------------------------- reading the screen
  fun roots(): List<Pair<AccessibilityNodeInfo, AccessibilityWindowInfo?>> {
    val out = ArrayList<Pair<AccessibilityNodeInfo, AccessibilityWindowInfo?>>()
    try {
      // Active app first, then dialogs, keyboard and system UI on top of it.
      val ws = windows.sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isActive }.thenByDescending { it.layer })
      for (w in ws) w.root?.let { out.add(it to w) }
    } catch (_: Exception) {
    }
    if (out.isEmpty()) rootInActiveWindow?.let { out.add(it to null) }
    return out
  }

  // ---------------------------------------------------------------- gestures
  private fun gesture(path: Path, durationMs: Long): Boolean {
    val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, maxOf(1L, durationMs))).build()
    val latch = CountDownLatch(1)
    var ok = false
    val dispatched = dispatchGesture(g, object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription?) {
        ok = true; latch.countDown()
      }

      override fun onCancelled(gestureDescription: GestureDescription?) {
        latch.countDown()
      }
    }, null)
    if (!dispatched) return false
    latch.await(durationMs + 3000, TimeUnit.MILLISECONDS)
    return ok
  }

  fun tap(x: Float, y: Float, durationMs: Long = 60): Boolean =
    gesture(Path().apply { moveTo(x, y) }, durationMs)

  fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean =
    gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, durationMs)

  fun clickNode(n: AccessibilityNodeInfo, long: Boolean = false): Boolean {
    var cur: AccessibilityNodeInfo? = n
    val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
    var depth = 0
    while (cur != null && depth < 6) {
      if ((if (long) cur.isLongClickable else cur.isClickable) && cur.isEnabled && cur.performAction(action)) return true
      cur = cur.parent
      depth++
    }
    val r = Rect().also { n.getBoundsInScreen(it) }
    return tap(r.exactCenterX(), r.exactCenterY(), if (long) 700 else 60)
  }

  fun setText(n: AccessibilityNodeInfo, text: String): Boolean {
    val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
    n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
  }

  fun imeEnter(n: AccessibilityNodeInfo?): Boolean {
    if (Build.VERSION.SDK_INT >= 30) {
      val target = n ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
      if (target != null && target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
    }
    return false
  }

  fun focusedInput(): AccessibilityNodeInfo? = try {
    findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
  } catch (_: Exception) {
    null
  }

  // ---------------------------------------------------------------- screenshots (Android 11+)
  fun screenshot(): Bitmap? {
    if (Build.VERSION.SDK_INT < 30) return null
    val latch = CountDownLatch(1)
    var bmp: Bitmap? = null
    takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
      override fun onSuccess(result: ScreenshotResult) {
        try {
          val hb = result.hardwareBuffer
          val wrapped = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
          bmp = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
          hb.close()
        } catch (_: Exception) {
        }
        latch.countDown()
      }

      override fun onFailure(errorCode: Int) {
        latch.countDown()
      }
    })
    latch.await(4, TimeUnit.SECONDS)
    return bmp
  }
}
