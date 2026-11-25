package dev.jarvis.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reconnects to Jarvis after a reboot or app update, if the phone is paired and auto-start is on. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(ctx: Context, intent: Intent) {
    val a = intent.action ?: return
    if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED && a != "android.intent.action.QUICKBOOT_POWERON") return
    if (Prefs.pairing(ctx) == null || !Prefs.bool(ctx, "auto_start", true)) return
    try {
      BridgeService.start(ctx)
    } catch (_: Exception) {
    }
  }
}
