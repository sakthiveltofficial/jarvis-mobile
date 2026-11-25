package dev.jarvis.bridge

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** What the phone currently lets Jarvis do. Shown in the app's Phone tab and sent to the desktop. */
object Capabilities {
  private fun has(ctx: Context, vararg p: String) = p.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

  fun accessibilityEnabled(ctx: Context): Boolean {
    val me = ComponentName(ctx, JarvisAccessibilityService::class.java).flattenToString()
    val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
    return list.split(':').any { it.equals(me, true) } || JarvisAccessibilityService.instance != null
  }

  fun get(ctx: Context): JSONObject {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val a11y = accessibilityEnabled(ctx)
    return JSONObject()
      .put("accessibility", a11y)
      .put("screenCapture", ScreenCapture.active || (Build.VERSION.SDK_INT >= 30 && a11y))
      .put("notifications", NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName))
      .put("postNotifications", Build.VERSION.SDK_INT < 33 || has(ctx, Manifest.permission.POST_NOTIFICATIONS))
      .put("microphone", has(ctx, Manifest.permission.RECORD_AUDIO))
      .put("sms", has(ctx, Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS))
      .put("calls", has(ctx, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG) &&
        (Build.VERSION.SDK_INT < 28 || has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)))
      .put("contacts", has(ctx, Manifest.permission.READ_CONTACTS))
      .put("location", has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION))
      .put("files", if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else has(ctx, Manifest.permission.READ_EXTERNAL_STORAGE))
      .put("overlay", Settings.canDrawOverlays(ctx))
      .put("battery", pm.isIgnoringBatteryOptimizations(ctx.packageName))
      .put("shizuku", ShizukuShell.granted())
      .put("shizukuRunning", ShizukuShell.running())
      .put("apiLevel", Build.VERSION.SDK_INT)
  }
}
