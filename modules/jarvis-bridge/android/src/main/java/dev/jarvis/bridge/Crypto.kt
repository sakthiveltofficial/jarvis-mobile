package dev.jarvis.bridge

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM framing shared with the desktop (backend/jarvis/phones.py).
 *
 * A sealed frame is {"n": base64(12-byte nonce), "c": base64(ciphertext || 16-byte tag)}.
 * The additional data binds every frame to its purpose and to this connection's random
 * challenge ("auth|", "welcome|", "c2s|", "s2c|" + challenge bytes), so frames can't be
 * replayed on another connection or reflected back in the other direction.
 */
object Crypto {
  private val rng = SecureRandom()

  fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

  fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
  fun unb64(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)
  fun unb64url(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

  fun aad(prefix: String, challenge: ByteArray): ByteArray = prefix.toByteArray(Charsets.US_ASCII) + challenge

  fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray): JSONObject {
    val nonce = random(12)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    c.updateAAD(aad)
    val ct = c.doFinal(plain)
    return JSONObject().put("n", b64(nonce)).put("c", b64(ct))
  }

  fun open(key: ByteArray, frame: JSONObject, aad: ByteArray): ByteArray {
    val nonce = unb64(frame.getString("n"))
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    c.updateAAD(aad)
    return c.doFinal(unb64(frame.getString("c")))
  }

  fun sealJson(key: ByteArray, obj: JSONObject, aad: ByteArray): JSONObject =
    seal(key, obj.toString().toByteArray(Charsets.UTF_8), aad)

  fun openJson(key: ByteArray, frame: JSONObject, aad: ByteArray): JSONObject =
    JSONObject(String(open(key, frame, aad), Charsets.UTF_8))
}
