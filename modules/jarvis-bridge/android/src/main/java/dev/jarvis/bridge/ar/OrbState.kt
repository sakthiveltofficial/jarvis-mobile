package dev.jarvis.bridge.ar

import android.os.SystemClock
import org.json.JSONObject

/**
 * What the AR orb should look like right now. Fed straight from desktop events (Bus.desktopEvent) and the
 * phone's own audio, so the GL thread never has to wait on JavaScript.
 *
 * The palette is the desktop orb's (ui/orb.js in the jarvis repo, v0.6.0+):
 * [colour A, colour B, sparkle colour, speed, turbulence, sparkle amount].
 */
object OrbState {
  class Look(val a: Int, val b: Int, val c: Int, val speed: Float, val turb: Float, val spark: Float)

  val MOODS: Map<String, Look> = mapOf(
    "neutral" to Look(0x8fd16a, 0xe9d66a, 0xffffff, 0.8f, 0.6f, 0.06f),
    "listening" to Look(0xc8f04a, 0x7fd6a0, 0xffffff, 1.0f, 0.7f, 0.08f),
    "thinking" to Look(0x9b6bff, 0xe07bff, 0xffffff, 1.35f, 0.9f, 0.12f),
    "calm" to Look(0x4f9dff, 0x9ad7ff, 0xffffff, 0.6f, 0.5f, 0.05f),
    "happy" to Look(0xffb347, 0xffe08a, 0xfff6d8, 1.0f, 0.8f, 0.12f),
    "excited" to Look(0xffc53d, 0xff7a3d, 0xfff2b0, 1.6f, 1.2f, 0.28f),
    "curious" to Look(0x3ee6c1, 0xb4ff8a, 0xffffff, 1.05f, 0.85f, 0.12f),
    "focused" to Look(0x6f8cff, 0xb8c4ff, 0xffffff, 0.8f, 0.4f, 0.06f),
    "concerned" to Look(0x2ec4b6, 0x9be7e0, 0xffffff, 0.55f, 0.45f, 0.04f),
    "apologetic" to Look(0xb9a3ff, 0xffc2e0, 0xffffff, 0.5f, 0.45f, 0.04f),
    "playful" to Look(0xff6fd8, 0xffd36f, 0xffffff, 1.4f, 1.0f, 0.22f),
    "u_stressed" to Look(0x3cc9b5, 0xa6f0e4, 0xffffff, 0.4f, 0.3f, 0.03f),
    "u_excited" to Look(0xffc53d, 0xff8f4d, 0xfff2b0, 1.4f, 1.1f, 0.24f),
    "u_down" to Look(0xff9fb2, 0xffd6a5, 0xffffff, 0.5f, 0.45f, 0.05f),
    "u_tired" to Look(0x8ea6ff, 0xc7b8ff, 0xffffff, 0.45f, 0.4f, 0.04f),
  )

  @Volatile var agentState = "idle"
  @Volatile var phoneSpeaking = false
  @Volatile private var aiMood: String? = null
  @Volatile private var aiAt = 0L
  @Volatile private var userMood: String? = null
  @Volatile private var userAt = 0L
  /** Bumped when a tool starts, so the orb can flash a little. */
  @Volatile var toolPulse = 0L

  fun onEvent(ev: JSONObject) {
    when (ev.optString("type")) {
      "state" -> agentState = ev.optString("state", "idle")
      "ai_mood" -> ev.optString("mood").takeIf { MOODS.containsKey(it) }?.let { aiMood = it; aiAt = now() }
      "mood" -> { userMood = ev.optString("mood").ifEmpty { null }; userAt = now() }
      "phone_audio" -> phoneSpeaking = ev.optBoolean("playing")
      "tool_start" -> toolPulse = now()
    }
  }

  private fun now() = SystemClock.elapsedRealtime()

  /** Same rule as the desktop: Jarvis's own mood while speaking or for 25 s, else how the user sounds (5 min). */
  fun moodKey(): String {
    val t = now()
    var key = "neutral"
    if (state() == "listening" || state() == "transcribing") key = "listening"
    val um = userMood
    if (um != null && um != "neutral" && t - userAt < 300_000) key = "u_$um"
    val am = aiMood
    if (am != null && (t - aiAt < 25_000 || state() == "speaking")) key = am
    return if (MOODS.containsKey(key)) key else "neutral"
  }

  fun state(): String = if (phoneSpeaking) "speaking" else agentState
}
