package uk.noammm.aamods.headunit

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// Decodes Android Auto's H.264 stream onto the screen with the phone's hardware decoder, on its own thread so the
// connection keeps reading. Ported from Headunit Revived (andreknieriem/open-headunit), AGPL-3.0.
internal class VideoSink(private val surface: Surface, private val width: Int, private val height: Int) {
    private class Chunk(val data: ByteArray, val flags: Int)

    private val queue = LinkedBlockingQueue<Chunk>()
    @Volatile private var running = true
    private val thread = Thread(::decode, "AA Mods video")

    fun start() = thread.start()

    // SPS/PPS: what the decoder needs before the first frame, and again after every new stream
    fun configure(data: ByteArray) = queue.offer(Chunk(data, MediaCodec.BUFFER_FLAG_CODEC_CONFIG))

    fun frame(data: ByteArray) = queue.offer(Chunk(data, 0))

    fun stop() {
        running = false
        thread.interrupt()
    }

    private fun decode() {
        val codec = MediaCodec.createDecoderByType(MIME)
        try {
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            while (running) {
                val unit = queue.poll(10, TimeUnit.MILLISECONDS)
                if (unit != null) {
                    var index = -1
                    while (running && index < 0) {
                        index = codec.dequeueInputBuffer(10_000)
                        render(codec, info)
                    }
                    if (index < 0) break
                    val buffer = codec.getInputBuffer(index)!!
                    buffer.clear()
                    val size = minOf(unit.data.size, buffer.capacity())
                    buffer.put(unit.data, 0, size)
                    codec.queueInputBuffer(index, 0, size, System.nanoTime() / 1000, unit.flags)
                }
                render(codec, info)
            }
        } catch (_: InterruptedException) {
        } catch (_: IllegalStateException) {
            // the surface went away while decoding
        } finally {
            try {
                codec.stop()
            } catch (_: IllegalStateException) {
            }
            codec.release()
        }
    }

    private fun render(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index >= 0) codec.releaseOutputBuffer(index, true) else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
        }
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }
}
