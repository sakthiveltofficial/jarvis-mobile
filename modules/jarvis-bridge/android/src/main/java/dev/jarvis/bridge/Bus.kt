package dev.jarvis.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * In-process hub between the native bridge (service, accessibility, tools) and the JS UI.
 * Keeps short histories so the UI can catch up after it was closed while the service ran.
 */
object Bus {
  @Volatile var listener: ((String, Map<String, Any?>) -> Unit)? = null

  @Volatile var status: JSONObject = JSONObject().put("state", "unpaired")
    private set

  private val history = ArrayDeque<JSONObject>()
  private val actions = ArrayDeque<JSONObject>()

  private val KEEP = setOf(
    "user", "assistant", "tool_start", "tool_end", "confirm", "confirm_done", "error", "info", "bg_update",
  )

  fun setStatus(state: String, extra: JSONObject? = null) {
    val s = JSONObject().put("state", state).put("at", System.currentTimeMillis())
    extra?.keys()?.forEach { s.put(it, extra.get(it)) }
    status = s
    emit("onStatus", s)
  }

  fun desktopEvent(ev: JSONObject) {
    dev.jarvis.bridge.ar.OrbState.onEvent(ev)
    if (ev.optString("type") in KEEP) synchronized(history) {
      history.addLast(ev)
      while (history.size > 300) history.removeFirst()
    }
    emit("onEvent", ev)
  }

  fun action(action: String, summary: String, ok: Boolean) {
    val a = JSONObject().put("action", action).put("summary", summary).put("ok", ok).put("at", System.currentTimeMillis())
    synchronized(actions) {
      actions.addLast(a)
      while (actions.size > 100) actions.removeFirst()
    }
    emit("onAction", a)
  }

  fun level(v: Double) = emit("onLevel", JSONObject().put("value", v))

  fun history(): List<Map<String, Any?>> = synchronized(history) { history.map { toMap(it) } }
  fun actions(): List<Map<String, Any?>> = synchronized(actions) { actions.map { toMap(it) } }

  private fun emit(name: String, o: JSONObject) {
    try {
      listener?.invoke(name, toMap(o))
    } catch (_: Exception) {
    }
  }

  fun toMap(o: JSONObject): Map<String, Any?> {
    val m = HashMap<String, Any?>()
    for (k in o.keys()) m[k] = conv(o.opt(k))
    return m
  }

  private fun conv(v: Any?): Any? = when (v) {
    is JSONObject -> toMap(v)
    is JSONArray -> (0 until v.length()).map { conv(v.opt(it)) }
    JSONObject.NULL -> null
    else -> v
  }
}
