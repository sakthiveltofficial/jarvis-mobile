package dev.jarvis.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.sqrt

/** Push-to-talk recorder: 16 kHz mono PCM16, returned as a WAV for the desktop's speech-to-text. */
object VoiceRecorder {
  private const val SR = 16000
  @Volatile private var rec: AudioRecord? = null
  @Volatile private var thread: Thread? = null
  private val buf = ByteArrayOutputStream()

  val recording get() = rec != null

  @SuppressLint("MissingPermission")
  fun start() {
    if (rec != null) return
    val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR, AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SR))
    if (r.state != AudioRecord.STATE_INITIALIZED) {
      r.release(); throw IllegalStateException("Microphone unavailable")
    }
    synchronized(buf) { buf.reset() }
    rec = r
    AudioOut.stop()
    r.startRecording()
    thread = Thread {
      val chunk = ByteArray(3200)
      while (rec === r) {
        val n = r.read(chunk, 0, chunk.size)
        if (n <= 0) continue
        synchronized(buf) { buf.write(chunk, 0, n) }
        val bb = ByteBuffer.wrap(chunk, 0, n).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        var cnt = 0
        while (bb.remaining() >= 2) {
          val s = bb.short / 32768.0; sum += s * s; cnt++
        }
        Bus.level(if (cnt > 0) minOf(1.0, sqrt(sum / cnt) * 6) else 0.0)
      }
    }.apply { name = "jarvis-mic"; start() }
  }

  /** Stops and returns WAV bytes (or null when nothing useful was recorded). */
  fun stop(): ByteArray? {
    val r = rec ?: return null
    rec = null
    try {
      r.stop()
    } catch (_: Exception) {
    }
    thread?.join(500)
    r.release()
    Bus.level(0.0)
    val pcm = synchronized(buf) { buf.toByteArray() }
    if (pcm.size < SR / 2) return null // < 0.25 s
    return wav(pcm)
  }

  private fun wav(pcm: ByteArray): ByteArray {
    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
    h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(SR); h.putInt(SR * 2)
    h.putShort(2); h.putShort(16); h.put("data".toByteArray()); h.putInt(pcm.size)
    return h.array() + pcm
  }
}

/** Plays Jarvis's spoken replies (WAV from the desktop's TTS) one after another. */
object AudioOut {
  private val queue = ConcurrentLinkedQueue<File>()
  @Volatile private var player: MediaPlayer? = null
  private var n = 0

  @Synchronized
  fun enqueue(ctx: Context, wavB64: String) {
    val f = File(ctx.cacheDir, "jarvis-tts-${n++ % 16}.wav")
    f.writeBytes(Crypto.unb64(wavB64))
    queue.add(f)
    if (player == null) next()
  }

  @Synchronized
  private fun next() {
    val f = queue.poll() ?: run { player = null; Bus.desktopEvent(org.json.JSONObject().put("type", "phone_audio").put("playing", false)); return }
    val mp = MediaPlayer()
    player = mp
    try {
      mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
      mp.setDataSource(f.absolutePath)
      mp.setOnCompletionListener { it.release(); next() }
      mp.setOnErrorListener { p, _, _ -> p.release(); next(); true }
      mp.prepare()
      mp.start()
      Bus.desktopEvent(org.json.JSONObject().put("type", "phone_audio").put("playing", true))
    } catch (e: Exception) {
      mp.release()
      next()
    }
  }

  @Synchronized
  fun stop() {
    queue.clear()
    player?.let {
      try {
        it.stop()
      } catch (_: Exception) {
      }
      it.release()
    }
    player = null
  }
}
