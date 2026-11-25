package dev.jarvis.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** What the QR code on the desktop carries: jarvis://pair?v=1&name=..&hosts=a,b&port=8767&pid=..&k=.. */
data class PairOffer(val pcName: String, val hosts: List<String>, val port: Int, val pairId: String, val secret: ByteArray) {
  companion object {
    fun parse(uri: String): PairOffer {
      val u = Uri.parse(uri.trim())
      require(u.scheme == "jarvis" && u.host == "pair") { "That isn't a Jarvis pairing code." }
      val hosts = (u.getQueryParameter("hosts") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
      require(hosts.isNotEmpty()) { "The pairing code has no addresses." }
      val secret = Crypto.unb64url(u.getQueryParameter("k") ?: "")
      require(secret.size == 32) { "The pairing code is damaged." }
      return PairOffer(
        u.getQueryParameter("name") ?: "Jarvis", hosts, (u.getQueryParameter("port") ?: "8767").toInt(),
        u.getQueryParameter("pid") ?: "", secret,
      )
    }
  }
}

/**
 * Keeps one encrypted WebSocket to desktop Jarvis. Tries each known address (LAN, Tailscale…),
 * reconnects with backoff and immediately when the network changes.
 */
class BridgeClient(private val ctx: Context, private val tools: PhoneTools) {
  private val tag = "JarvisBridge"
  private val main = Handler(Looper.getMainLooper())
  private val http = OkHttpClient.Builder()
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .build()
  private val toolPool = Executors.newSingleThreadExecutor()

  @Volatile private var ws: WebSocket? = null
  @Volatile private var attempt = 0
  @Volatile private var stopped = true
  @Volatile private var authed = false
  private var challenge = ByteArray(0)
  private var key = ByteArray(0)
  private var sendSeq = 0L
  private var recvSeq = 0L
  private var hostIdx = 0
  private var failures = 0
  private var offer: PairOffer? = null
  private var pairCallback: ((Boolean, String) -> Unit)? = null
  private var currentHost = ""

  private val netCb = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      if (!stopped && !authed) main.post { failures = 0; reconnect(0) }
    }
  }

  val connected get() = authed

  fun start() {
    if (!stopped) return
    stopped = false
    try {
      (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(netCb)
    } catch (_: Exception) {
    }
    if (Prefs.pairing(ctx) == null && offer == null) {
      Bus.setStatus("unpaired")
      return
    }
    connect()
  }

  fun stop() {
    stopped = true
    main.removeCallbacksAndMessages(null)
    try {
      (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(netCb)
    } catch (_: Exception) {
    }
    ws?.close(1000, "bye")
    ws = null
    authed = false
  }

  fun pair(uri: String, cb: (Boolean, String) -> Unit) {
    val o = try {
      PairOffer.parse(uri)
    } catch (e: Exception) {
      cb(false, e.message ?: "Bad code"); return
    }
    offer = o
    pairCallback = cb
    hostIdx = 0
    failures = 0
    ws?.close(1000, "re-pairing")
    authed = false
    stopped = false
    connect()
  }

  fun unpair() {
    if (authed) send(JSONObject().put("t", "unpair"))
    main.postDelayed({
      Prefs.clearPairing(ctx)
      ws?.close(1000, "unpaired")
      authed = false
      Bus.setStatus("unpaired")
    }, 300)
  }

  fun reconnectNow() {
    failures = 0
    ws?.close(1000, "reconnect")
    authed = false
    reconnect(0)
  }

  // ---------------------------------------------------------------- connection
  private fun hosts(): Pair<List<String>, Int> {
    offer?.let { return it.hosts to it.port }
    val p = Prefs.pairing(ctx) ?: return emptyList<String>() to 8767
    val last = Prefs.lastHost(ctx)
    val list = (listOfNotNull(last) + p.hosts).distinct()
    return list to p.port
  }

  private fun reconnect(delayMs: Long) {
    if (stopped) return
    main.removeCallbacksAndMessages(null)
    main.postDelayed({ connect() }, delayMs)
  }

  private fun connect() {
    if (stopped) return
    val (hosts, port) = hosts()
    if (hosts.isEmpty()) {
      Bus.setStatus("unpaired"); return
    }
    val host = hosts[hostIdx % hosts.size]
    currentHost = host
    val my = ++attempt
    authed = false
    sendSeq = 0; recvSeq = 0
    val url = if (host.startsWith("ws://") || host.startsWith("wss://")) host else "ws://${hostPort(host, port)}/phone"
    Bus.setStatus("connecting", JSONObject().put("host", host).put("pc", offer?.pcName ?: Prefs.pairing(ctx)?.pcName))
    BridgeService.instance?.notify("Connecting to ${offer?.pcName ?: Prefs.pairing(ctx)?.pcName ?: "Jarvis"}…")
    ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
      override fun onMessage(webSocket: WebSocket, text: String) {
        if (my != attempt) return
        try {
          handle(webSocket, JSONObject(text))
        } catch (e: Exception) {
          Log.w(tag, "bad frame", e)
          webSocket.close(4400, "bad frame")
        }
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (my != attempt) return
        Log.i(tag, "connect to $host failed: ${t.message}")
        dropped("Can't reach $host")
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (my != attempt) return
        if (code == 4401) {
          // the desktop no longer knows this phone
          if (offer == null) {
            Prefs.clearPairing(ctx)
            Bus.setStatus("unpaired", JSONObject().put("reason", "This PC removed the phone. Pair again."))
            return
          }
        }
        dropped(reason.ifEmpty { "Disconnected" })
      }
    })
  }

  private fun hostPort(host: String, port: Int): String = when {
    host.startsWith("[") -> if (host.contains("]:")) host else "$host:$port"
    host.count { it == ':' } > 1 -> "[$host]:$port"          // bare IPv6
    host.contains(":") -> host                               // already host:port
    else -> "$host:$port"
  }

  private fun dropped(why: String) {
    val wasAuthed = authed
    authed = false
    ws = null
    if (stopped) return
    val (hosts, _) = hosts()
    if (!wasAuthed) hostIdx++
    if (offer != null && hostIdx >= hosts.size) {
      val cb = pairCallback
      offer = null; pairCallback = null; hostIdx = 0
      cb?.invoke(false, "Couldn't reach the PC. Is the phone on the same Wi-Fi or Tailscale network?")
      Bus.setStatus(if (Prefs.pairing(ctx) != null) "disconnected" else "unpaired", JSONObject().put("reason", why))
      if (Prefs.pairing(ctx) != null) reconnect(2000)
      return
    }
    val delay = if (hostIdx % maxOf(hosts.size, 1) != 0) 200L else {
      failures++
      minOf(60_000L, 1500L * (1L shl minOf(failures, 6)))
    }
    Bus.setStatus("disconnected", JSONObject().put("reason", why).put("retryInMs", delay))
    BridgeService.instance?.notify("Offline · retrying")
    reconnect(delay)
  }

  // ---------------------------------------------------------------- protocol
  private fun handle(sock: WebSocket, msg: JSONObject) {
    if (!authed) {
      when (msg.optString("t")) {
        "hello" -> {
          challenge = Crypto.unb64(msg.getString("challenge"))
          val o = offer
          val p = Prefs.pairing(ctx)
          val body = JSONObject()
            .put("challenge", Crypto.b64(challenge))
            .put("name", deviceName())
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("app", appVersion())
          key = o?.secret ?: p?.deviceKey ?: run { sock.close(1000, "no key"); return }
          val auth = JSONObject().put("t", "auth").put("device", Prefs.deviceId(ctx))
            .put("mode", if (o != null) "pair" else "device")
            .put("box", Crypto.sealJson(key, body, Crypto.aad("auth|", challenge)))
          if (o != null) auth.put("pid", o.pairId)
          sock.send(auth.toString())
        }
        "welcome" -> {
          val w = Crypto.openJson(key, msg.getJSONObject("box"), Crypto.aad("welcome|", challenge))
          val o = offer
          val hosts = w.optJSONArray("hosts")?.let { a -> (0 until a.length()).map { a.getString(it) } }
          if (o != null) {
            val dk = Crypto.unb64(w.getString("device_key"))
            Prefs.savePairing(ctx, dk, w.optString("name", o.pcName), ((hosts ?: emptyList()) + o.hosts).distinct(), w.optInt("port", o.port))
            key = dk
            offer = null
            pairCallback?.invoke(true, w.optString("name", o.pcName))
            pairCallback = null
          } else if (hosts != null) {
            Prefs.updateHosts(ctx, hosts + (Prefs.pairing(ctx)?.hosts ?: emptyList()), w.optInt("port").takeIf { it > 0 })
          }
          Prefs.setLastHost(ctx, currentHost)
          authed = true
          failures = 0
          hostIdx = 0
          val pc = Prefs.pairing(ctx)?.pcName ?: "Jarvis"
          Bus.setStatus("connected", JSONObject().put("pc", pc).put("host", currentHost)
            .put("user", w.optString("user")).put("assistant", w.optString("assistant", "Jarvis")))
          BridgeService.instance?.notify("Connected to $pc")
          send(JSONObject().put("t", "caps").put("caps", Capabilities.get(ctx)))
          if (w.has("state")) Bus.desktopEvent(JSONObject().put("type", "state").put("state", w.getString("state")))
        }
        "denied" -> {
          val reason = msg.optString("reason", "The PC refused this phone.")
          val cb = pairCallback
          if (offer != null) {
            offer = null; pairCallback = null
            cb?.invoke(false, reason)
            sock.close(1000, "denied")
            Bus.setStatus(if (Prefs.pairing(ctx) != null) "disconnected" else "unpaired", JSONObject().put("reason", reason))
            if (Prefs.pairing(ctx) != null) reconnect(1500)
          } else {
            Prefs.clearPairing(ctx)
            sock.close(1000, "denied")
            Bus.setStatus("unpaired", JSONObject().put("reason", reason))
          }
          attempt++
        }
      }
      return
    }
    val m = Crypto.openJson(key, msg, Crypto.aad("s2c|", challenge))
    val seq = m.optLong("seq")
    if (seq <= recvSeq) throw IllegalStateException("replayed frame")
    recvSeq = seq
    when (m.optString("t")) {
      "event" -> Bus.desktopEvent(m.getJSONObject("ev"))
      "tts" -> AudioOut.enqueue(ctx, m.getString("wav"))
      "stop_audio" -> AudioOut.stop()
      "call" -> runCall(m)
      "hosts" -> m.optJSONArray("hosts")?.let { a -> Prefs.updateHosts(ctx, (0 until a.length()).map { a.getString(it) }) }
      "revoked" -> {
        Prefs.clearPairing(ctx)
        sock.close(1000, "revoked")
        Bus.setStatus("unpaired", JSONObject().put("reason", "This PC removed the phone."))
      }
    }
  }

  private fun runCall(m: JSONObject) {
    val id = m.getString("id")
    val action = m.getString("action")
    val args = m.optJSONObject("args") ?: JSONObject()
    toolPool.execute {
      val r = try {
        tools.run(action, args)
      } catch (e: Throwable) {
        ToolResult.err("${e.javaClass.simpleName}: ${e.message}")
      }
      Bus.action(action, PhoneTools.describe(action, args), !r.isError)
      val out = JSONObject().put("t", "result").put("id", id).put("text", r.text).put("is_error", r.isError)
      if (r.imageB64 != null) out.put("image_b64", r.imageB64).put("image_type", r.imageType)
      if (r.data != null) out.put("data", r.data)
      send(out)
    }
  }

  @Synchronized
  fun send(obj: JSONObject): Boolean {
    val s = ws ?: return false
    if (!authed) return false
    obj.put("seq", ++sendSeq)
    return s.send(Crypto.sealJson(key, obj, Crypto.aad("c2s|", challenge)).toString())
  }

  private fun deviceName(): String =
    try {
      android.provider.Settings.Global.getString(ctx.contentResolver, "device_name")
    } catch (_: Exception) {
      null
    } ?: "${Build.MANUFACTURER} ${Build.MODEL}"

  private fun appVersion(): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
  } catch (_: Exception) {
    "?"
  }

  fun hostsJson(): JSONArray = JSONArray(hosts().first)
}
