package dev.jarvis.bridge

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * Optional "power mode" through Shizuku (https://shizuku.rikka.app): runs shell commands with
 * adb-level rights, without root. The user installs Shizuku and starts it with wireless debugging.
 */
object ShizukuShell {
  const val REQUEST = 4712

  fun running(): Boolean = try {
    Shizuku.pingBinder()
  } catch (_: Throwable) {
    false
  }

  fun granted(): Boolean = running() && try {
    !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
  } catch (_: Throwable) {
    false
  }

  fun request(): String {
    if (!running()) return "not_running"
    if (granted()) return "granted"
    return try {
      Shizuku.requestPermission(REQUEST); "asked"
    } catch (e: Throwable) {
      "error"
    }
  }

  /** Runs `sh -c cmd` as the shell user. Returns exit code and combined output. */
  fun exec(cmd: String, timeoutS: Long = 30): Pair<Int, String> {
    if (!granted()) throw IllegalStateException("Power mode is off. Start Shizuku and allow Jarvis in the app's Phone tab.")
    // Shizuku keeps newProcess private in API 13 but it is the documented way to run one-off shell commands.
    val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
    m.isAccessible = true
    val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
    val out = StringBuilder()
    val reader = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { start() }
    val err = StringBuilder()
    val errReader = Thread { err.append(p.errorStream.bufferedReader().readText()) }.apply { start() }
    val done = p.waitFor(timeoutS, TimeUnit.SECONDS)
    if (!done) p.destroy()
    reader.join(1000); errReader.join(1000)
    val text = (out.toString() + (if (err.isNotEmpty()) "\n[stderr]\n$err" else "")).trim()
    return (if (done) p.exitValue() else -1) to (if (done) text else "$text\n(timed out after ${timeoutS}s)")
  }
}
