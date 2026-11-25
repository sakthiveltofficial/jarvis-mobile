package dev.jarvis.bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Pairing info. The device key is wrapped with a non-exportable Android Keystore key. */
data class Pairing(
  val deviceId: String,
  val deviceKey: ByteArray,
  val pcName: String,
  val hosts: List<String>,
  val port: Int,
)

object Prefs {
  private const val FILE = "jarvis_bridge"
  private const val ALIAS = "jarvis_bridge_wrap"

  private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  private fun wrapKey(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    gen.init(
      KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )
    return gen.generateKey()
  }

  private fun wrap(plain: ByteArray): String {
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, wrapKey())
    return Crypto.b64(c.iv) + ":" + Crypto.b64(c.doFinal(plain))
  }

  private fun unwrap(s: String): ByteArray {
    val (iv, ct) = s.split(":", limit = 2)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, Crypto.unb64(iv)))
    return c.doFinal(Crypto.unb64(ct))
  }

  fun deviceId(ctx: Context): String {
    val p = sp(ctx)
    return p.getString("device_id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("device_id", it).apply() }
  }

  fun pairing(ctx: Context): Pairing? {
    val p = sp(ctx)
    val wrapped = p.getString("device_key", null) ?: return null
    return try {
      val hosts = JSONArray(p.getString("hosts", "[]"))
      Pairing(
        deviceId(ctx), unwrap(wrapped), p.getString("pc_name", "Jarvis") ?: "Jarvis",
        (0 until hosts.length()).map { hosts.getString(it) }, p.getInt("port", 8767),
      )
    } catch (e: Exception) {
      null
    }
  }

  fun savePairing(ctx: Context, key: ByteArray, pcName: String, hosts: List<String>, port: Int) {
    sp(ctx).edit()
      .putString("device_key", wrap(key))
      .putString("pc_name", pcName)
      .putString("hosts", JSONArray(hosts).toString())
      .putInt("port", port)
      .apply()
  }

  fun updateHosts(ctx: Context, hosts: List<String>, port: Int? = null) {
    val e = sp(ctx).edit().putString("hosts", JSONArray(hosts.distinct()).toString())
    if (port != null) e.putInt("port", port)
    e.apply()
  }

  fun lastHost(ctx: Context): String? = sp(ctx).getString("last_host", null)
  fun setLastHost(ctx: Context, h: String) = sp(ctx).edit().putString("last_host", h).apply()

  fun clearPairing(ctx: Context) {
    sp(ctx).edit().remove("device_key").remove("pc_name").remove("hosts").remove("port").remove("last_host").apply()
  }

  fun bool(ctx: Context, k: String, def: Boolean) = sp(ctx).getBoolean(k, def)
  fun setBool(ctx: Context, k: String, v: Boolean) = sp(ctx).edit().putBoolean(k, v).apply()

  fun settings(ctx: Context): JSONObject = JSONObject()
    .put("speakOnPhone", bool(ctx, "speak_on_phone", true))
    .put("forwardNotifications", bool(ctx, "forward_notifications", false))
    .put("autoStart", bool(ctx, "auto_start", true))
}
