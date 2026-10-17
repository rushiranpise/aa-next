package uk.noammm.aamods.headunit

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import uk.noammm.aamods.headunit.proto.Media
import java.util.concurrent.atomic.AtomicInteger

// Records the phone's microphone for Android Auto's voice requests (16 kHz mono PCM), in chunks timestamped in
// microseconds. The phone acknowledges every chunk; at most maxUnacked may be in flight, newer ones are dropped.
// Ported from Headunit Revived (andreknieriem/open-headunit), AGPL-3.0.
internal class MicSource(private val context: Context, private val send: (ByteArray, Int, Long) -> Unit) {
    @Volatile private var thread: Thread? = null
    private val unacked = AtomicInteger()
    private var maxUnacked = 2

    // False without the microphone permission or when the microphone can't be opened
    @SuppressLint("MissingPermission")
    fun start(maxUnacked: Int): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        stop()
        this.maxUnacked = maxUnacked.coerceAtLeast(1)
        unacked.set(0)
        val minimum = AudioRecord.getMinBufferSize(FORMAT.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, FORMAT.sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, CHUNK * 4))
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        record.startRecording()
        thread = Thread({
            val chunk = ByteArray(CHUNK)
            try {
                while (thread === Thread.currentThread()) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read <= 0) break
                    if (unacked.get() >= this.maxUnacked) continue
                    unacked.incrementAndGet()
                    send(chunk, read, SystemClock.elapsedRealtimeNanos() / 1000)
                }
            } finally {
                record.stop()
                record.release()
            }
        }, "AA Mods mic").also { it.start() }
        return true
    }

    fun acked() {
        unacked.updateAndGet { maxOf(0, it - 1) }
    }

    fun stop() {
        thread = null
    }

    companion object {
        const val CHUNK = 2048 // 64 ms
        val FORMAT: Media.AudioConfiguration = Media.AudioConfiguration.newBuilder()
            .setSampleRate(16000)
            .setNumberOfBits(16)
            .setNumberOfChannels(1)
            .build()
    }
}
