package io.github.rushiranpise.aanext.headunit

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.rushiranpise.aanext.headunit.proto.Media
import java.util.concurrent.Executors

// Plays Android Auto's system sounds (16 kHz mono PCM), written on its own thread so the connection never waits on
// the audio buffer. Ported from Headunit Revived (andreknieriem/open-headunit), AGPL-3.0.
internal class AudioSink {
    private var track: AudioTrack? = null
    private val writer = Executors.newSingleThreadExecutor()

    fun start() = writer.execute {
        if (track != null) return@execute
        val encoding = AudioFormat.Builder()
            .setSampleRate(SYSTEM_FORMAT.sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val bufferSize = AudioTrack.getMinBufferSize(SYSTEM_FORMAT.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(encoding)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    fun write(pcm: ByteArray, offset: Int, length: Int) {
        val copy = pcm.copyOfRange(offset, offset + length)
        writer.execute { track?.write(copy, 0, copy.size) }
    }

    fun stop() = writer.execute {
        track?.run {
            stop()
            release()
        }
        track = null
    }

    fun release() {
        stop()
        writer.shutdown()
    }

    companion object {
        val SYSTEM_FORMAT: Media.AudioConfiguration = Media.AudioConfiguration.newBuilder()
            .setSampleRate(16000)
            .setNumberOfBits(16)
            .setNumberOfChannels(1)
            .build()
    }
}
