package dev.jarvis.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Foreground service that owns the connection to desktop Jarvis, so it survives the UI being closed. */
class BridgeService : Service() {
  companion object {
    @Volatile var instance: BridgeService? = null
    private const val CHANNEL = "jarvis_bridge"
    private const val NOTIF_ID = 4711
    const val ACTION_STOP = "dev.jarvis.bridge.STOP"
    const val EXTRA_PAIR = "pair_uri"
    @Volatile var pendingPair: ((Boolean, String) -> Unit)? = null

    fun start(ctx: Context, pairUri: String? = null) {
      val i = Intent(ctx, BridgeService::class.java)
      if (pairUri != null) i.putExtra(EXTRA_PAIR, pairUri)
      ContextCompat.startForegroundService(ctx, i)
    }

    fun stop(ctx: Context) {
      ctx.stopService(Intent(ctx, BridgeService::class.java))
    }
  }

  lateinit var tools: PhoneTools
  lateinit var client: BridgeClient
  private var wake: PowerManager.WakeLock? = null
  private var text = "Starting…"
  private var projecting = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    instance = this
    channel()
    goForeground()
    tools = PhoneTools(applicationContext)
    client = BridgeClient(applicationContext, tools)
    // A partial wake lock keeps tool calls responsive while the screen is off. Released in onDestroy.
    wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:bridge").apply { setReferenceCounted(false) }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      Prefs.setBool(this, "auto_start", false)
      stopSelf()
      return START_NOT_STICKY
    }
    val pair = intent?.getStringExtra(EXTRA_PAIR)
    if (pair != null) {
      val cb = pendingPair
      pendingPair = null
      client.pair(pair) { ok, msg -> cb?.invoke(ok, msg) }
    } else {
      client.start()
    }
    return START_STICKY
  }

  override fun onDestroy() {
    client.stop()
    ScreenCapture.stop()
    AudioOut.stop()
    wake?.let { if (it.isHeld) it.release() }
    instance = null
    Bus.setStatus("stopped")
    super.onDestroy()
  }

  fun holdWake(ms: Long) {
    try {
      wake?.acquire(ms)
    } catch (_: Exception) {
    }
  }

  // ---------------------------------------------------------------- notification
  private fun channel() {
    if (Build.VERSION.SDK_INT >= 26) {
      val nm = getSystemService(NotificationManager::class.java)
      val ch = NotificationChannel(CHANNEL, "Connection to Jarvis", NotificationManager.IMPORTANCE_LOW)
      ch.description = "Shows while your phone is linked to Jarvis on your computer"
      ch.setShowBadge(false)
      nm.createNotificationChannel(ch)
    }
  }

  private fun build(): Notification {
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    val pi = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val stop = PendingIntent.getService(
      this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return NotificationCompat.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.jarvis_bridge_notif)
      .setContentTitle("Jarvis")
      .setContentText(text)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setColor(0xFFD6F34A.toInt())
      .setContentIntent(pi)
      .addAction(0, "Disconnect", stop)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .build()
  }

  fun notify(t: String) {
    text = t
    try {
      getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build())
    } catch (_: Exception) {
    }
  }

  private fun goForeground() {
    var type = 0
    if (Build.VERSION.SDK_INT >= 34) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    if (projecting && Build.VERSION.SDK_INT >= 29) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    ServiceCompat.startForeground(this, NOTIF_ID, build(), type)
  }

  /** Must be called before MediaProjection is created (Android 14+ requires the mediaProjection FGS type). */
  fun enableProjectionType() {
    projecting = true
    goForeground()
  }
}
