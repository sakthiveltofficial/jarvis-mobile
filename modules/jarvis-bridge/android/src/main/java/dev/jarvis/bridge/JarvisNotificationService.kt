package dev.jarvis.bridge

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lets Jarvis read, open, dismiss and reply to notifications, and (optionally) hear about new ones. */
class JarvisNotificationService : NotificationListenerService() {
  companion object {
    @Volatile var instance: JarvisNotificationService? = null
  }

  override fun onListenerConnected() {
    instance = this
  }

  override fun onListenerDisconnected() {
    instance = null
  }

  override fun onDestroy() {
    instance = null
    super.onDestroy()
  }

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    sbn ?: return
    if (sbn.packageName == packageName || sbn.isOngoing) return
    if (!Prefs.bool(this, "forward_notifications", false)) return
    val n = sbn.notification
    if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
    val ex = n.extras
    BridgeService.instance?.client?.send(
      JSONObject().put("t", "notification")
        .put("app", appLabel(sbn.packageName))
        .put("key", sbn.key)
        .put("title", ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "")
        .put("text", (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: "")
        .put("can_reply", replyAction(n) != null)
    )
  }

  fun appLabel(pkg: String): String = try {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
  } catch (_: Exception) {
    pkg
  }

  fun list(limit: Int, app: String?): String {
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    val items = activeNotifications.orEmpty()
      .filter { it.packageName != packageName && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
      .filter { app.isNullOrBlank() || appLabel(it.packageName).contains(app, true) || it.packageName.contains(app, true) }
      .sortedByDescending { it.postTime }
      .take(limit)
    if (items.isEmpty()) return "No notifications."
    return items.joinToString("\n") { sbn ->
      val ex = sbn.notification.extras
      val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
      val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
      val reply = if (replyAction(sbn.notification) != null) " [can reply]" else ""
      "- ${fmt.format(Date(sbn.postTime))} ${appLabel(sbn.packageName)}: $title — ${text.take(300)}$reply (key=${sbn.key})"
    }
  }

  private fun find(key: String): StatusBarNotification? = activeNotifications.orEmpty().firstOrNull { it.key == key }
    ?: activeNotifications.orEmpty().firstOrNull { it.key.contains(key) }

  fun dismiss(key: String?): String {
    if (key.isNullOrBlank() || key == "all") {
      cancelAllNotifications(); return "Cleared all dismissable notifications."
    }
    val s = find(key) ?: return "No notification with key $key."
    cancelNotification(s.key)
    return "Dismissed."
  }

  fun open(key: String): String {
    val s = find(key) ?: return "No notification with key $key."
    val pi = s.notification.contentIntent ?: return "That notification can't be opened."
    pi.send()
    return "Opened ${appLabel(s.packageName)}."
  }

  private fun replyAction(n: Notification): Notification.Action? =
    n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

  fun reply(key: String, text: String): String {
    val s = find(key) ?: return "No notification with key $key."
    val action = replyAction(s.notification) ?: return "That notification has no reply button."
    val inputs = action.remoteInputs.orEmpty()
    val intent = Intent()
    val results = Bundle()
    for (ri in inputs) results.putCharSequence(ri.resultKey, text)
    RemoteInput.addResultsToIntent(inputs, intent, results)
    action.actionIntent.send(this, 0, intent)
    return "Replied to ${appLabel(s.packageName)}."
  }
}
