package dev.jarvis.bridge

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONObject

class JarvisBridgeModule : Module() {
  private val ctx: Context get() = appContext.reactContext ?: throw IllegalStateException("No context")
  private var capturePromise: Promise? = null
  private val CAPTURE_REQ = 4713

  override fun definition() = ModuleDefinition {
    Name("JarvisBridge")

    Events("onStatus", "onEvent", "onAction", "onLevel")

    OnCreate {
      Bus.listener = { name, body -> sendEvent(name, body) }
    }

    OnDestroy {
      Bus.listener = null
    }

    Function("getState") {
      val p = Prefs.pairing(ctx)
      mapOf(
        "status" to Bus.toMap(Bus.status),
        "serviceRunning" to (BridgeService.instance != null),
        "pairing" to p?.let { mapOf("pc" to it.pcName, "hosts" to it.hosts, "port" to it.port, "deviceId" to it.deviceId) },
        "settings" to Bus.toMap(Prefs.settings(ctx)),
        "capabilities" to Bus.toMap(Capabilities.get(ctx)),
      )
    }

    Function("capabilities") { Bus.toMap(Capabilities.get(ctx)) }
    Function("history") { Bus.history() }
    Function("actions") { Bus.actions() }

    Function("start") {
      Prefs.setBool(ctx, "auto_start", true)
      BridgeService.start(ctx)
    }

    Function("stop") {
      Prefs.setBool(ctx, "auto_start", false)
      BridgeService.stop(ctx)
    }

    Function("reconnect") {
      BridgeService.instance?.client?.reconnectNow() ?: BridgeService.start(ctx)
    }

    AsyncFunction("pair") { uri: String, promise: Promise ->
      BridgeService.pendingPair = { ok, msg -> promise.resolve(mapOf("ok" to ok, "message" to msg)) }
      Prefs.setBool(ctx, "auto_start", true)
      BridgeService.start(ctx, uri)
    }

    Function("unpair") {
      BridgeService.instance?.client?.unpair() ?: run {
        Prefs.clearPairing(ctx); Bus.setStatus("unpaired")
      }
    }

    Function("setHosts") { hosts: List<String> ->
      Prefs.updateHosts(ctx, hosts.map { it.trim() }.filter { it.isNotEmpty() })
      BridgeService.instance?.client?.reconnectNow()
    }

    Function("setSetting") { key: String, value: Boolean ->
      val k = mapOf("speakOnPhone" to "speak_on_phone", "forwardNotifications" to "forward_notifications", "autoStart" to "auto_start")[key] ?: key
      Prefs.setBool(ctx, k, value)
    }

    Function("getString") { key: String -> ctx.getSharedPreferences("jarvis_bridge_ui", Context.MODE_PRIVATE).getString(key, null) }

    Function("setString") { key: String, value: String ->
      ctx.getSharedPreferences("jarvis_bridge_ui", Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    Function("sendText") { text: String ->
      send(JSONObject().put("t", "user_text").put("text", text).put("speak", Prefs.bool(ctx, "speak_on_phone", true)))
    }

    Function("confirm") { id: String, approve: Boolean ->
      send(JSONObject().put("t", "confirm").put("id", id).put("approve", approve))
    }

    Function("stopAgent") {
      AudioOut.stop()
      send(JSONObject().put("t", "stop"))
    }

    Function("stopSpeaking") {
      AudioOut.stop()
      send(JSONObject().put("t", "stop_speaking"))
    }

    Function("startRecording") {
      VoiceRecorder.start()
      send(JSONObject().put("t", "listening"))
    }

    Function("stopRecording") { sendIt: Boolean ->
      val wav = VoiceRecorder.stop()
      if (!sendIt || wav == null) return@Function false
      send(JSONObject().put("t", "audio").put("wav", Crypto.b64(wav)).put("speak", Prefs.bool(ctx, "speak_on_phone", true)))
    }

    AsyncFunction("requestPermissions") { group: String, promise: Promise ->
      val perms = when (group) {
        "microphone" -> listOf(Manifest.permission.RECORD_AUDIO)
        "sms" -> listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS)
        "calls" -> listOfNotNull(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE,
          if (Build.VERSION.SDK_INT >= 28) Manifest.permission.ANSWER_PHONE_CALLS else null)
        "contacts" -> listOf(Manifest.permission.READ_CONTACTS)
        "location" -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        "postNotifications" -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        "files" -> if (Build.VERSION.SDK_INT < 30) listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyList()
        else -> emptyList()
      }
      val pm = appContext.permissions
      if (perms.isEmpty() || pm == null) {
        promise.resolve(Bus.toMap(Capabilities.get(ctx))); return@AsyncFunction
      }
      pm.askForPermissions({ _ ->
        promise.resolve(Bus.toMap(Capabilities.get(ctx)))
        pushCaps()
      }, *perms.toTypedArray())
    }

    Function("openSettings") { kind: String ->
      val pkg = ctx.packageName
      val i = when (kind) {
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "notifications" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        "files" -> if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$pkg"))
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg"))
        "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))
        "shizuku" -> ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
          ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
      }
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      try {
        (appContext.currentActivity ?: ctx).startActivity(i)
      } catch (e: Exception) {
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(fallback)
      }
    }

    Function("requestShizuku") { ShizukuShell.request() }

    AsyncFunction("requestScreenCapture") { promise: Promise ->
      val act = appContext.currentActivity ?: run { promise.resolve(false); return@AsyncFunction }
      if (BridgeService.instance == null) BridgeService.start(ctx)
      capturePromise = promise
      val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
      act.startActivityForResult(mpm.createScreenCaptureIntent(), CAPTURE_REQ)
    }

    Function("stopScreenCapture") { ScreenCapture.stop() }

    OnActivityResult { _, payload ->
      if (payload.requestCode != CAPTURE_REQ) return@OnActivityResult
      val p = capturePromise
      capturePromise = null
      val data = payload.data
      if (payload.resultCode != Activity.RESULT_OK || data == null) {
        p?.resolve(false); return@OnActivityResult
      }
      try {
        BridgeService.instance?.enableProjectionType()
        ScreenCapture.begin(ctx, payload.resultCode, data)
        p?.resolve(true)
        pushCaps()
      } catch (e: Exception) {
        p?.reject("E_CAPTURE", e.message ?: "Screen capture failed", e)
      }
    }

    OnActivityEntersForeground { pushCaps() }
  }

  private fun send(o: JSONObject): Boolean = BridgeService.instance?.client?.send(o) ?: false

  private fun pushCaps() {
    try {
      send(JSONObject().put("t", "caps").put("caps", Capabilities.get(ctx)))
    } catch (_: Exception) {
    }
  }
}
