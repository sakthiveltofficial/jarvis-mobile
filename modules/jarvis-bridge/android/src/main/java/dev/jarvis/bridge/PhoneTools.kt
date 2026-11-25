package dev.jarvis.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.accessibilityservice.AccessibilityService as A11y

data class ToolResult(
  val text: String,
  val isError: Boolean = false,
  val imageB64: String? = null,
  val imageType: String = "image/jpeg",
  val data: JSONObject? = null,
) {
  companion object {
    fun ok(t: String) = ToolResult(t)
    fun err(t: String) = ToolResult(t, isError = true)
  }
}

/** Everything desktop Jarvis can do on the phone. Each action takes JSON args and returns text (+ image). */
class PhoneTools(private val ctx: Context) {
  private val main = Handler(Looper.getMainLooper())
  private val time = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
  private var ringing: android.media.Ringtone? = null

  companion object {
    val ACTIONS = listOf(
      "status", "screen", "screenshot", "tap", "long_press", "swipe", "scroll", "type", "key", "open_app",
      "list_apps", "open_url", "notifications", "sms", "call", "contacts", "files", "clipboard",
      "device_info", "torch", "volume", "media", "ring", "location", "shell",
    )

    fun describe(action: String, a: JSONObject): String = when (action) {
      "tap", "long_press" -> if (a.has("index")) "$action #${a.optInt("index")}" else "$action (${a.opt("x")}, ${a.opt("y")})"
      "type" -> "type \"${a.optString("text").take(40)}\""
      "key" -> "key ${a.optString("key")}"
      "open_app" -> "open ${a.optString("app")}"
      "open_url" -> "open ${a.optString("url").take(60)}"
      "sms" -> "sms ${a.optString("op", "list")} ${a.optString("to")}".trim()
      "call" -> "call ${a.optString("op", "dial")} ${a.optString("number")}".trim()
      "files" -> "files ${a.optString("op", "list")} ${a.optString("path")}".trim()
      "notifications" -> "notifications ${a.optString("op", "list")}"
      "shell" -> "shell ${a.optString("command").take(60)}"
      "scroll", "swipe" -> "$action ${a.optString("direction")}".trim()
      else -> action
    }
  }

  fun run(action: String, a: JSONObject): ToolResult {
    BridgeService.instance?.holdWake(60_000)
    return when (action) {
      "status" -> ToolResult.ok(status())
      "screen" -> screen(a.optString("query"))
      "screenshot" -> screenshot()
      "tap" -> tap(a, long = false)
      "long_press" -> tap(a, long = true)
      "swipe" -> swipe(a)
      "scroll" -> scroll(a)
      "type" -> type(a)
      "key" -> key(a.optString("key"), a)
      "open_app" -> openApp(a.optString("app", a.optString("name")), a)
      "list_apps" -> listApps(a.optString("query"))
      "open_url" -> openUrl(a.optString("url"), a)
      "notifications" -> notifications(a)
      "sms" -> sms(a)
      "call" -> call(a)
      "contacts" -> contacts(a.optString("query"), a.optInt("limit", 20))
      "files" -> files(a)
      "clipboard" -> clipboard(a)
      "device_info" -> ToolResult.ok(deviceInfo())
      "torch" -> torch(a.optBoolean("on", true))
      "volume" -> volume(a)
      "media" -> media(a.optString("command", "toggle"))
      "ring" -> ring(a.optBoolean("on", true))
      "location" -> location()
      "shell" -> shell(a.optString("command"), a.optLong("timeout", 30))
      else -> ToolResult.err("Unknown phone action $action. Available: ${ACTIONS.joinToString()}")
    }
  }

  // ---------------------------------------------------------------- helpers
  private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

  private fun need(p: String, what: String): ToolResult? =
    if (has(p)) null else ToolResult.err("The phone hasn't allowed $what yet. Ask the user to turn it on in the Jarvis phone app (Phone tab).")

  private fun a11y(): JarvisAccessibilityService = JarvisAccessibilityService.instance
    ?: throw IllegalStateException("Screen control is off. Ask the user to enable Jarvis under Settings › Accessibility on the phone (the Jarvis app's Phone tab has a shortcut).")

  private fun after(a: JSONObject, msg: String): ToolResult {
    if (!a.optBoolean("look", true) || JarvisAccessibilityService.instance == null) return ToolResult.ok(msg)
    SystemClock.sleep(a.optLong("wait_ms", 700))
    return try {
      ToolResult.ok(msg + "\n\nScreen now:\n" + ScreenTree.dump(ctx, a11y()))
    } catch (e: Exception) {
      ToolResult.ok(msg)
    }
  }

  private fun startActivity(i: Intent) {
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // Starting from the accessibility service's context is allowed from the background on modern Android.
    val c: Context = JarvisAccessibilityService.instance ?: ctx
    c.startActivity(i)
  }

  // ---------------------------------------------------------------- screen
  private fun status(): String {
    val caps = Capabilities.get(ctx)
    return caps.keys().asSequence().filter { it != "apiLevel" }.joinToString("\n") { "- $it: ${if (caps.optBoolean(it)) "on" else "off"}" }
  }

  private fun screen(query: String?): ToolResult = ToolResult.ok(ScreenTree.dump(ctx, a11y(), query))

  private fun screenshot(): ToolResult {
    var bmp: Bitmap? = JarvisAccessibilityService.instance?.screenshot()
    var how = "accessibility"
    if (bmp == null && ScreenCapture.active) {
      bmp = ScreenCapture.grab(); how = "capture"
    }
    if (bmp == null && ShizukuShell.granted()) {
      val f = File(ctx.cacheDir, "jarvis-shot.png")
      ShizukuShell.exec("screencap -p > /data/local/tmp/jarvis-shot.png && cat /data/local/tmp/jarvis-shot.png > '${f.absolutePath}' ; rm -f /data/local/tmp/jarvis-shot.png", 15)
      if (f.exists()) bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
      how = "shizuku"
    }
    if (bmp == null) {
      return ToolResult.err(
        if (Build.VERSION.SDK_INT >= 30 && JarvisAccessibilityService.instance == null)
          "Screen control is off on the phone, so I can't take a screenshot. Use the Jarvis app's Phone tab to enable it."
        else "Couldn't capture the screen (the app may block screenshots). Try `screen` for the element list instead."
      )
    }
    val size = ScreenTree.screenSize(ctx)
    val s = ScreenTree.scale(ctx)
    val w = (size.x * s).toInt()
    val h = (size.y * s).toInt()
    val scaled = if (bmp.width != w || bmp.height != h) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 78, out)
    return ToolResult("Phone screenshot ${w}x$h via $how. Tap coordinates use this image's pixels.",
      imageB64 = Crypto.b64(out.toByteArray()), imageType = "image/jpeg")
  }

  private fun point(a: JSONObject): Pair<Float, Float>? {
    if (a.has("index")) {
      val it = ScreenTree.node(a.getInt("index")) ?: return null
      return it.bounds.exactCenterX() to it.bounds.exactCenterY()
    }
    if (a.has("x") && a.has("y")) return ScreenTree.toScreen(ctx, a.getDouble("x"), a.getDouble("y"))
    return null
  }

  private fun tap(a: JSONObject, long: Boolean): ToolResult {
    val svc = a11y()
    if (a.has("index")) {
      val item = ScreenTree.node(a.getInt("index"))
        ?: return ToolResult.err("No element #${a.optInt("index")} in the last screen read. Call screen again.")
      val ok = svc.clickNode(item.node, long)
      return if (ok) after(a, "${if (long) "Long-pressed" else "Tapped"} #${a.getInt("index")}.") else ToolResult.err("The tap didn't go through.")
    }
    val p = point(a) ?: return ToolResult.err("Give index (from screen) or x and y.")
    val ok = svc.tap(p.first, p.second, if (long) 700 else 60)
    return if (ok) after(a, "${if (long) "Long-pressed" else "Tapped"} (${a.opt("x")}, ${a.opt("y")}).") else ToolResult.err("The tap was cancelled.")
  }

  private fun swipe(a: JSONObject): ToolResult {
    val svc = a11y()
    val size = ScreenTree.screenSize(ctx)
    val dir = a.optString("direction")
    val (x1, y1, x2, y2) = if (dir.isNotEmpty()) {
      val cx = size.x / 2f; val cy = size.y / 2f; val dx = size.x * 0.35f; val dy = size.y * 0.3f
      when (dir) {
        "up" -> listOf(cx, cy + dy, cx, cy - dy)        // finger moves up: content scrolls down
        "down" -> listOf(cx, cy - dy, cx, cy + dy)
        "left" -> listOf(cx + dx, cy, cx - dx, cy)
        "right" -> listOf(cx - dx, cy, cx + dx, cy)
        else -> return ToolResult.err("direction must be up, down, left or right")
      }
    } else {
      val (a1, b1) = ScreenTree.toScreen(ctx, a.getDouble("x"), a.getDouble("y"))
      val (a2, b2) = ScreenTree.toScreen(ctx, a.getDouble("to_x"), a.getDouble("to_y"))
      listOf(a1, b1, a2, b2)
    }
    val ok = svc.swipe(x1, y1, x2, y2, a.optLong("duration_ms", 350))
    return if (ok) after(a, "Swiped ${dir.ifEmpty { "" }}".trim() + ".") else ToolResult.err("The swipe was cancelled.")
  }

  private fun scroll(a: JSONObject): ToolResult {
    val dir = a.optString("direction", "down")
    if (a.has("index")) {
      val item = ScreenTree.node(a.getInt("index")) ?: return ToolResult.err("No element #${a.optInt("index")}. Call screen again.")
      var n: android.view.accessibility.AccessibilityNodeInfo? = item.node
      while (n != null && !n.isScrollable) n = n.parent
      if (n != null) {
        val act = when (dir) {
          "up", "left" -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
          else -> android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        if (n.performAction(act)) return after(a, "Scrolled $dir.")
      }
    }
    // Scrolling content down means swiping the finger up.
    val swipeDir = mapOf("down" to "up", "up" to "down", "left" to "right", "right" to "left")[dir] ?: "up"
    return swipe(JSONObject(a.toString()).put("direction", swipeDir).put("duration_ms", 450))
  }

  private fun type(a: JSONObject): ToolResult {
    val svc = a11y()
    val text = a.optString("text")
    val target = if (a.has("index")) ScreenTree.node(a.getInt("index"))?.node else svc.focusedInput()
    if (target == null) {
      if (ShizukuShell.granted()) {
        ShizukuShell.exec("input text " + shellQuote(text.replace(" ", "%s")))
        if (a.optBoolean("submit")) ShizukuShell.exec("input keyevent 66")
        return after(a, "Typed with power mode.")
      }
      return ToolResult.err("No text field is focused. Tap the field first, or pass index of an input from screen.")
    }
    val existing = if (a.optBoolean("clear", true)) "" else {
      val showingHint = Build.VERSION.SDK_INT >= 26 && target.isShowingHintText
      if (showingHint) "" else target.text?.toString().orEmpty()
    }
    if (!svc.setText(target, existing + text)) {
      svc.clickNode(target)
      SystemClock.sleep(300)
      val f = svc.focusedInput() ?: return ToolResult.err("That element doesn't accept text.")
      if (!svc.setText(f, existing + text)) return ToolResult.err("Couldn't type into that field.")
    }
    if (a.optBoolean("submit")) {
      if (!svc.imeEnter(target) && ShizukuShell.granted()) ShizukuShell.exec("input keyevent 66")
    }
    return after(a, "Typed \"${text.take(60)}\"${if (a.optBoolean("submit")) " and pressed enter" else ""}.")
  }

  private fun key(k: String, a: JSONObject): ToolResult {
    val svc = JarvisAccessibilityService.instance
    val global = mapOf(
      "back" to A11y.GLOBAL_ACTION_BACK, "home" to A11y.GLOBAL_ACTION_HOME, "recents" to A11y.GLOBAL_ACTION_RECENTS,
      "notifications" to A11y.GLOBAL_ACTION_NOTIFICATIONS, "quick_settings" to A11y.GLOBAL_ACTION_QUICK_SETTINGS,
      "power_dialog" to A11y.GLOBAL_ACTION_POWER_DIALOG,
    ).toMutableMap()
    if (Build.VERSION.SDK_INT >= 28) {
      global["lock"] = A11y.GLOBAL_ACTION_LOCK_SCREEN
      global["take_screenshot"] = A11y.GLOBAL_ACTION_TAKE_SCREENSHOT
    }
    global[k]?.let { id ->
      val s = svc ?: return ToolResult.err("Screen control is off on the phone.")
      return if (s.performGlobalAction(id)) after(a, "Pressed $k.") else ToolResult.err("The phone refused $k.")
    }
    if (k == "enter" && svc?.imeEnter(null) == true) return after(a, "Pressed enter.")
    val code = mapOf(
      "enter" to KeyEvent.KEYCODE_ENTER, "delete" to KeyEvent.KEYCODE_DEL, "tab" to KeyEvent.KEYCODE_TAB,
      "volume_up" to KeyEvent.KEYCODE_VOLUME_UP, "volume_down" to KeyEvent.KEYCODE_VOLUME_DOWN,
      "power" to KeyEvent.KEYCODE_POWER, "wakeup" to KeyEvent.KEYCODE_WAKEUP, "menu" to KeyEvent.KEYCODE_MENU,
      "search" to KeyEvent.KEYCODE_SEARCH, "camera" to KeyEvent.KEYCODE_CAMERA,
    )[k] ?: k.toIntOrNull()
    if (code == null) return ToolResult.err("Unknown key $k. Try back, home, recents, notifications, quick_settings, lock, enter, delete, volume_up, volume_down, power, wakeup.")
    if (k == "volume_up" || k == "volume_down") {
      val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (k == "volume_up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
      return ToolResult.ok("Volume ${if (k == "volume_up") "up" else "down"}.")
    }
    if (!ShizukuShell.granted()) return ToolResult.err("Key $k needs power mode (Shizuku) on the phone.")
    ShizukuShell.exec("input keyevent $code")
    return after(a, "Pressed $k.")
  }

  // ---------------------------------------------------------------- apps
  private data class App(val label: String, val pkg: String)

  private fun apps(): List<App> {
    val pm = ctx.packageManager
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(i, 0).map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
      .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
  }

  private fun listApps(q: String?): ToolResult {
    val list = apps().filter { q.isNullOrBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }
    return ToolResult.ok(if (list.isEmpty()) "No matching apps." else list.joinToString("\n") { "${it.label} (${it.pkg})" })
  }

  private fun openApp(name: String, a: JSONObject): ToolResult {
    if (name.isBlank()) return ToolResult.err("Which app?")
    val all = apps()
    val app = all.firstOrNull { it.pkg.equals(name, true) } ?: all.firstOrNull { it.label.equals(name, true) }
      ?: all.firstOrNull { it.label.startsWith(name, true) } ?: all.firstOrNull { it.label.contains(name, true) }
      ?: all.firstOrNull { it.pkg.contains(name.replace(" ", ""), true) }
      ?: return ToolResult.err("No app called \"$name\" on the phone. Use list_apps to see what's installed.")
    val launch = ctx.packageManager.getLaunchIntentForPackage(app.pkg) ?: return ToolResult.err("${app.label} can't be opened.")
    main.post { startActivity(launch) }
    SystemClock.sleep(900)
    return after(a, "Opened ${app.label}.")
  }

  private fun openUrl(url: String, a: JSONObject): ToolResult {
    if (url.isBlank()) return ToolResult.err("Give a url.")
    val u = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url)) url else "https://$url"
    main.post { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
    SystemClock.sleep(1200)
    return after(a, "Opened $u.")
  }

  // ---------------------------------------------------------------- notifications
  private fun notifications(a: JSONObject): ToolResult {
    val svc = JarvisNotificationService.instance
      ?: return ToolResult.err("Notification access is off. Ask the user to allow it in the Jarvis app's Phone tab.")
    return when (a.optString("op", "list")) {
      "list" -> ToolResult.ok(svc.list(a.optInt("limit", 15), a.optString("app").ifBlank { null }))
      "dismiss" -> ToolResult.ok(svc.dismiss(a.optString("key")))
      "open" -> ToolResult.ok(svc.open(a.optString("key")))
      "reply" -> ToolResult.ok(svc.reply(a.optString("key"), a.optString("text")))
      else -> ToolResult.err("op must be list, open, dismiss or reply")
    }
  }

  // ---------------------------------------------------------------- comms
  private fun resolveNumber(to: String): Pair<String, String>? {
    val digits = to.filter { it.isDigit() || it == '+' }
    if (digits.length >= 3 && digits.length >= to.count { !it.isWhitespace() } - 3) return digits to to
    if (!has(Manifest.permission.READ_CONTACTS)) return null
    val c = ctx.contentResolver.query(
      ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
      arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
      "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$to%"), null,
    ) ?: return null
    c.use { if (it.moveToFirst()) return it.getString(1) to it.getString(0) }
    return null
  }

  @SuppressLint("MissingPermission")
  private fun sms(a: JSONObject): ToolResult {
    when (a.optString("op", "list")) {
      "send" -> {
        need(Manifest.permission.SEND_SMS, "sending SMS")?.let { return it }
        val to = a.optString("to")
        val text = a.optString("text")
        if (to.isBlank() || text.isBlank()) return ToolResult.err("Give to and text.")
        val (num, who) = resolveNumber(to) ?: return ToolResult.err("Couldn't find a number for \"$to\".")
        val mgr = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = mgr.divideMessage(text)
        mgr.sendMultipartTextMessage(num, null, parts, null, null)
        return ToolResult.ok("Sent SMS to $who ($num).")
      }
      "list" -> {
        need(Manifest.permission.READ_SMS, "reading SMS")?.let { return it }
        val q = a.optString("query")
        val limit = a.optInt("limit", 15).coerceIn(1, 100)
        val sel = if (q.isNotBlank()) "${Telephony.Sms.BODY} LIKE ? OR ${Telephony.Sms.ADDRESS} LIKE ?" else null
        val args = if (q.isNotBlank()) arrayOf("%$q%", "%$q%") else null
        val c = ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,
          arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
          sel, args, "${Telephony.Sms.DATE} DESC") ?: return ToolResult.err("SMS store unavailable.")
        val out = ArrayList<String>()
        c.use {
          while (it.moveToNext() && out.size < limit) {
            val dir = if (it.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT) "to" else "from"
            val unread = if (it.getInt(4) == 0) " (unread)" else ""
            out.add("- ${time.format(Date(it.getLong(2)))} $dir ${contactName(it.getString(0))}$unread: ${it.getString(1)?.take(400)}")
          }
        }
        return ToolResult.ok(if (out.isEmpty()) "No messages." else out.joinToString("\n"))
      }
      else -> return ToolResult.err("op must be send or list")
    }
  }

  private fun contactName(number: String?): String {
    if (number.isNullOrBlank() || !has(Manifest.permission.READ_CONTACTS)) return number ?: "?"
    return try {
      val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
      ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) "${it.getString(0)} ($number)" else number
      } ?: number
    } catch (_: Exception) {
      number
    }
  }

  @SuppressLint("MissingPermission")
  @Suppress("DEPRECATION")
  private fun call(a: JSONObject): ToolResult {
    when (a.optString("op", "dial")) {
      "dial" -> {
        need(Manifest.permission.CALL_PHONE, "phone calls")?.let { return it }
        val (num, who) = resolveNumber(a.optString("number", a.optString("to"))) ?: return ToolResult.err("Couldn't find that number.")
        main.post { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num)))) }
        return ToolResult.ok("Calling $who ($num).")
      }
      "end", "answer" -> {
        if (Build.VERSION.SDK_INT < 28) return ToolResult.err("Needs Android 9 or newer.")
        need(Manifest.permission.ANSWER_PHONE_CALLS, "answering and ending calls")?.let { return it }
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        if (a.optString("op") == "end") {
          val ended = tm.endCall()
          return if (ended) ToolResult.ok("Hung up.") else ToolResult.err("No call to end.")
        }
        tm.acceptRingingCall()
        return ToolResult.ok("Answered.")
      }
      "log" -> {
        need(Manifest.permission.READ_CALL_LOG, "the call log")?.let { return it }
        val limit = a.optInt("limit", 15).coerceIn(1, 100)
        val c = ctx.contentResolver.query(CallLog.Calls.CONTENT_URI,
          arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
          null, null, "${CallLog.Calls.DATE} DESC") ?: return ToolResult.err("Call log unavailable.")
        val out = ArrayList<String>()
        c.use {
          while (it.moveToNext() && out.size < limit) {
            val type = when (it.getInt(2)) {
              CallLog.Calls.INCOMING_TYPE -> "incoming"; CallLog.Calls.OUTGOING_TYPE -> "outgoing"
              CallLog.Calls.MISSED_TYPE -> "missed"; CallLog.Calls.REJECTED_TYPE -> "rejected"; else -> "other"
            }
            val name = it.getString(1)?.takeIf { n -> n.isNotBlank() } ?: it.getString(0)
            out.add("- ${time.format(Date(it.getLong(3)))} $type $name (${it.getString(0)}) ${it.getLong(4)}s")
          }
        }
        return ToolResult.ok(if (out.isEmpty()) "No calls." else out.joinToString("\n"))
      }
      else -> return ToolResult.err("op must be dial, answer, end or log")
    }
  }

  private fun contacts(q: String, limit: Int): ToolResult {
    need(Manifest.permission.READ_CONTACTS, "contacts")?.let { return it }
    val c = ctx.contentResolver.query(
      ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
      arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
      if (q.isNotBlank()) "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?" else null,
      if (q.isNotBlank()) arrayOf("%$q%") else null,
      "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
    ) ?: return ToolResult.err("Contacts unavailable.")
    val byName = LinkedHashMap<String, MutableSet<String>>()
    c.use { while (it.moveToNext() && byName.size < limit) byName.getOrPut(it.getString(0) ?: "?") { linkedSetOf() }.add(it.getString(1) ?: "") }
    return ToolResult.ok(if (byName.isEmpty()) "No matching contacts." else byName.entries.joinToString("\n") { "${it.key}: ${it.value.joinToString(", ")}" })
  }

  // ---------------------------------------------------------------- files
  private fun file(path: String): File {
    val root = Environment.getExternalStorageDirectory()
    val p = path.trim().ifEmpty { "/" }
    return when {
      p.startsWith("/storage") || p.startsWith("/sdcard") || p.startsWith("/data/local/tmp") -> File(p)
      p.startsWith("~") -> File(root, p.removePrefix("~").trimStart('/'))
      else -> File(root, p.trimStart('/'))
    }
  }

  private fun filesAllowed(): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else has(Manifest.permission.READ_EXTERNAL_STORAGE)

  private fun files(a: JSONObject): ToolResult {
    if (!filesAllowed()) return ToolResult.err("File access is off. Ask the user to allow \"All files access\" in the Jarvis app's Phone tab.")
    val f = file(a.optString("path"))
    return when (a.optString("op", "list")) {
      "list" -> {
        if (!f.isDirectory) return ToolResult.err("${f.path} isn't a folder.")
        val items = f.listFiles().orEmpty().sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        ToolResult.ok("${f.path}:\n" + items.take(300).joinToString("\n") {
          if (it.isDirectory) "  ${it.name}/" else "  ${it.name}  ${human(it.length())}  ${time.format(Date(it.lastModified()))}"
        } + if (items.size > 300) "\n  … ${items.size - 300} more" else "")
      }
      "info" -> ToolResult.ok("${f.path}: ${if (f.isDirectory) "folder" else human(f.length())}, modified ${time.format(Date(f.lastModified()))}")
      "read" -> {
        if (!f.isFile) return ToolResult.err("No file at ${f.path}.")
        val bytes = f.inputStream().use { ins -> val b = ByteArray(24_000); var n = 0; while (n < b.size) { val r = ins.read(b, n, b.size - n); if (r < 0) break; n += r }; b.copyOf(n) }
        ToolResult.ok(String(bytes, Charsets.UTF_8).take(12_000))
      }
      "read_b64" -> {
        if (!f.isFile) return ToolResult.err("No file at ${f.path}.")
        if (f.length() > 25L * 1024 * 1024) return ToolResult.err("${f.name} is over 25 MB.")
        ToolResult("Read ${f.name} (${human(f.length())}).", data = JSONObject().put("b64", Crypto.b64(f.readBytes())).put("name", f.name))
      }
      "write" -> {
        f.parentFile?.mkdirs()
        if (a.optBoolean("append")) f.appendText(a.optString("content")) else f.writeText(a.optString("content"))
        ToolResult.ok("Wrote ${f.path}.")
      }
      "write_b64" -> {
        f.parentFile?.mkdirs()
        f.writeBytes(Crypto.unb64(a.optString("data_b64")))
        ToolResult.ok("Saved ${f.path} (${human(f.length())}).")
      }
      "mkdir" -> ToolResult.ok(if (f.mkdirs() || f.isDirectory) "Folder ${f.path} ready." else "Couldn't create ${f.path}.")
      "delete" -> ToolResult.ok(if (f.deleteRecursively()) "Deleted ${f.path}." else "Couldn't delete ${f.path}.")
      "move" -> {
        val to = file(a.optString("to"))
        to.parentFile?.mkdirs()
        ToolResult.ok(if (f.renameTo(to)) "Moved to ${to.path}." else "Couldn't move ${f.path}.")
      }
      else -> ToolResult.err("op must be list, info, read, write, mkdir, delete, move, read_b64 or write_b64")
    }
  }

  private fun human(n: Long): String = when {
    n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "%.1f MB".format(n / (1L shl 20).toDouble())
    n >= 1024 -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
  }

  // ---------------------------------------------------------------- system
  private fun clipboard(a: JSONObject): ToolResult {
    val latch = CountDownLatch(1)
    var res = ToolResult.err("Clipboard unavailable.")
    main.post {
      try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        res = if (a.optString("op", "get") == "set") {
          cm.setPrimaryClip(ClipData.newPlainText("Jarvis", a.optString("text")))
          ToolResult.ok("Copied to the phone's clipboard.")
        } else {
          val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
          if (t == null) ToolResult.err("Android only lets apps read the clipboard while they're open. Open the Jarvis app, or use power mode.")
          else ToolResult.ok(t)
        }
      } catch (e: Exception) {
        res = ToolResult.err(e.message ?: "Clipboard error")
      }
      latch.countDown()
    }
    latch.await(3, TimeUnit.SECONDS)
    return res
  }

  private fun deviceInfo(): String {
    val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    val st = StatFs(Environment.getExternalStorageDirectory().path)
    val ringer = when (am.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" }
    val vol = { s: Int -> "${am.getStreamVolume(s) * 100 / maxOf(1, am.getStreamMaxVolume(s))}%" }
    return listOf(
      "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
      "Battery: ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%${if (bm.isCharging) ", charging" else ""}",
      "Screen: ${if (pm.isInteractive) "on" else "off"}; app in front: ${JarvisAccessibilityService.instance?.foregroundPackage ?: "unknown"}",
      "Sound: ringer $ringer, media ${vol(AudioManager.STREAM_MUSIC)}, ring ${vol(AudioManager.STREAM_RING)}, alarm ${vol(AudioManager.STREAM_ALARM)}",
      "Storage: ${human(st.availableBytes)} free of ${human(st.totalBytes)}",
      "Time: ${time.format(Date())}",
    ).joinToString("\n")
  }

  private fun torch(on: Boolean): ToolResult {
    val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
      ?: return ToolResult.err("This phone has no flashlight.")
    cm.setTorchMode(id, on)
    return ToolResult.ok("Flashlight ${if (on) "on" else "off"}.")
  }

  private fun volume(a: JSONObject): ToolResult {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val stream = when (a.optString("stream", "media")) {
      "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM
      "notification" -> AudioManager.STREAM_NOTIFICATION; "call" -> AudioManager.STREAM_VOICE_CALL
      else -> AudioManager.STREAM_MUSIC
    }
    a.optString("ringer").takeIf { it.isNotBlank() }?.let { mode ->
      return try {
        am.ringerMode = when (mode) { "silent" -> AudioManager.RINGER_MODE_SILENT; "vibrate" -> AudioManager.RINGER_MODE_VIBRATE; else -> AudioManager.RINGER_MODE_NORMAL }
        ToolResult.ok("Ringer set to $mode.")
      } catch (e: SecurityException) {
        ToolResult.err("Android needs Do Not Disturb access to change the ringer mode.")
      }
    }
    val level = a.optInt("level", -1)
    if (level < 0) return ToolResult.ok("Volume is ${am.getStreamVolume(stream) * 100 / maxOf(1, am.getStreamMaxVolume(stream))}%.")
    val max = am.getStreamMaxVolume(stream)
    am.setStreamVolume(stream, (level.coerceIn(0, 100) * max + 50) / 100, AudioManager.FLAG_SHOW_UI)
    return ToolResult.ok("Volume set to $level%.")
  }

  private fun media(cmd: String): ToolResult {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val code = when (cmd) {
      "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
      "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
      "stop" -> KeyEvent.KEYCODE_MEDIA_STOP; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    }
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    return ToolResult.ok("Media: $cmd.")
  }

  private fun ring(on: Boolean): ToolResult {
    ringing?.stop(); ringing = null
    if (!on) return ToolResult.ok("Stopped ringing.")
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    val r = RingtoneManager.getRingtone(ctx, uri) ?: return ToolResult.err("No ringtone available.")
    r.audioAttributes = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build()
    r.play()
    ringing = r
    main.postDelayed({ if (ringing === r) { r.stop(); ringing = null } }, 30_000)
    return ToolResult.ok("The phone is ringing at full volume for 30 seconds.")
  }

  @SuppressLint("MissingPermission")
  private fun location(): ToolResult {
    if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION))
      return ToolResult.err("Location is off for Jarvis on the phone. Ask the user to allow it in the Phone tab.")
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var best: Location? = null
    if (Build.VERSION.SDK_INT >= 30) {
      val latch = CountDownLatch(1)
      val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
      try {
        lm.getCurrentLocation(provider, null, ctx.mainExecutor) { best = it; latch.countDown() }
        latch.await(12, TimeUnit.SECONDS)
      } catch (_: Exception) {
      }
    }
    if (best == null) {
      best = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }
    val l = best ?: return ToolResult.err("No location fix yet (is location turned on?).")
    return ToolResult.ok("Phone is at %.5f, %.5f (±%.0f m, %s). Map: https://maps.google.com/?q=%.5f,%.5f".format(
      l.latitude, l.longitude, l.accuracy, time.format(Date(l.time)), l.latitude, l.longitude))
  }

  private fun shell(cmd: String, timeout: Long): ToolResult {
    if (cmd.isBlank()) return ToolResult.err("Give a command.")
    val (code, out) = ShizukuShell.exec(cmd, timeout.coerceIn(1, 300))
    return ToolResult("exit $code\n${out.take(12_000)}", isError = code != 0)
  }

  private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
