package dev.jarvis.bridge

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Turns the accessibility tree into a short numbered list the desktop agent can act on:
 *   [7] Button "Send" #send_btn (512,1210) click
 * Coordinates are in "view space": the screen scaled so its long edge is at most 1280 px,
 * the same space as phone screenshots, so the model can mix both freely.
 */
object ScreenTree {
  const val MAX_EDGE = 1280f
  private const val MAX_ITEMS = 220

  data class Item(val node: AccessibilityNodeInfo, val bounds: Rect)

  @Volatile var last: List<Item> = emptyList()
    private set

  fun screenSize(ctx: Context): Point {
    val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    return if (Build.VERSION.SDK_INT >= 30) {
      val b = wm.currentWindowMetrics.bounds; Point(b.width(), b.height())
    } else {
      Point().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(it) }
    }
  }

  fun scale(ctx: Context): Float {
    val p = screenSize(ctx)
    return minOf(1f, MAX_EDGE / maxOf(p.x, p.y).toFloat())
  }

  fun toScreen(ctx: Context, x: Number, y: Number): Pair<Float, Float> {
    val s = scale(ctx)
    return x.toFloat() / s to y.toFloat() / s
  }

  fun dump(ctx: Context, svc: JarvisAccessibilityService, query: String? = null): String {
    val size = screenSize(ctx)
    val s = scale(ctx)
    val items = ArrayList<Item>()
    val lines = ArrayList<String>()
    val roots = svc.roots()
    val screen = Rect(0, 0, size.x, size.y)
    val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    for ((root, win) in roots) {
      val label = when (win?.type) {
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        null -> root.packageName?.toString() ?: "app"
        else -> (win.title?.toString()?.takeIf { it.isNotBlank() } ?: root.packageName?.toString() ?: "app")
      }
      if (win?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
        lines.add("-- keyboard is open --"); continue
      }
      val before = lines.size
      walk(root, 0, items, lines, screen, s, q)
      if (lines.size > before) lines.add(before, "-- window: $label --")
      if (items.size >= MAX_ITEMS) break
    }
    last = items
    val head = StringBuilder()
    head.append("App: ${svc.foregroundPackage.ifEmpty { roots.firstOrNull()?.first?.packageName ?: "?" }}")
    if (svc.foregroundClass.isNotEmpty()) head.append(" (${svc.foregroundClass.substringAfterLast('.')})")
    head.append("\nScreen ${(size.x * s).toInt()}x${(size.y * s).toInt()} (coordinates below and in screenshots use this size).")
    if (q != null) head.append("\nFiltered by \"$query\".")
    head.append("\nUse tap with index=N, or x/y. ")
    if (items.size >= MAX_ITEMS) head.append("List truncated; scroll or filter with query. ")
    return head.toString() + "\n" + (if (lines.isEmpty()) "(no readable elements: try a screenshot)" else lines.joinToString("\n"))
  }

  private fun walk(n: AccessibilityNodeInfo, depth: Int, items: MutableList<Item>, lines: MutableList<String>,
                   screen: Rect, s: Float, q: String?) {
    if (items.size >= MAX_ITEMS || depth > 40) return
    if (!n.isVisibleToUser) return
    val r = Rect().also { n.getBoundsInScreen(it) }
    if (!Rect.intersects(r, screen) || r.width() <= 1 || r.height() <= 1) return
    val text = n.text?.toString()?.trim().orEmpty()
    val desc = n.contentDescription?.toString()?.trim().orEmpty()
    val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim().orEmpty() else ""
    val actionable = n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable || n.isCheckable
    val labelled = text.isNotEmpty() || desc.isNotEmpty()
    if (actionable || labelled) {
      val match = q == null || text.lowercase().contains(q) || desc.lowercase().contains(q) ||
        (n.viewIdResourceName ?: "").lowercase().contains(q)
      if (match) {
        val idx = items.size
        items.add(Item(n, r))
        lines.add(describe(idx, n, r, s, text, desc, hint))
      }
    }
    for (i in 0 until n.childCount) {
      val c = try {
        n.getChild(i)
      } catch (_: Exception) {
        null
      } ?: continue
      walk(c, depth + 1, items, lines, screen, s, q)
    }
  }

  private fun describe(i: Int, n: AccessibilityNodeInfo, r: Rect, s: Float, text: String, desc: String, hint: String): String {
    val cls = n.className?.toString()?.substringAfterLast('.') ?: "View"
    val sb = StringBuilder("[$i] $cls")
    val shownText = if (n.isPassword) "••••" else text
    if (shownText.isNotEmpty()) sb.append(" \"").append(shownText.take(120).replace("\n", " ")).append('"')
    if (desc.isNotEmpty() && desc != text) sb.append(" desc=\"").append(desc.take(80)).append('"')
    if (n.isEditable && hint.isNotEmpty() && hint != text) sb.append(" hint=\"").append(hint.take(60)).append('"')
    n.viewIdResourceName?.substringAfter(":id/")?.takeIf { it.isNotEmpty() }?.let { sb.append(" #").append(it) }
    sb.append(" (").append((r.exactCenterX() * s).toInt()).append(',').append((r.exactCenterY() * s).toInt()).append(')')
    val flags = ArrayList<String>()
    if (n.isClickable) flags.add("click")
    if (n.isLongClickable) flags.add("long")
    if (n.isEditable) flags.add("input")
    if (n.isScrollable) flags.add("scroll")
    if (n.isCheckable) flags.add(if (n.isChecked) "checked" else "unchecked")
    if (n.isFocused) flags.add("focused")
    if (n.isSelected) flags.add("selected")
    if (!n.isEnabled) flags.add("disabled")
    if (flags.isNotEmpty()) sb.append(' ').append(flags.joinToString(","))
    return sb.toString()
  }

  fun node(index: Int): Item? = last.getOrNull(index)
}
