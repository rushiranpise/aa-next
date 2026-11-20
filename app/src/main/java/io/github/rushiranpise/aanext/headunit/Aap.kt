package io.github.rushiranpise.aanext.headunit

import com.google.protobuf.MessageLite

// Android Auto protocol framing. Ported from Headunit Revived (andreknieriem/open-headunit), AGPL-3.0.
// A frame is [channel][flags][16-bit body length][body] and a body is [16-bit type][payload]; the first of several
// frames for one message adds its 32-bit total length after the header. Bodies are encrypted after the TLS handshake.
internal object Aap {
    const val CH_CONTROL = 0
    const val CH_SENSOR = 1
    const val CH_VIDEO = 2
    const val CH_INPUT = 3
    const val CH_SYSTEM_AUDIO = 5
    const val CH_MIC = 7

    const val FLAG_FIRST = 0x01
    const val FLAG_LAST = 0x02
    const val FLAG_CONTROL = 0x04
    const val FLAG_ENCRYPTED = 0x08

    // Control messages (Control.ControlMsgType)
    const val VERSION_REQUEST = 1
    const val VERSION_RESPONSE = 2
    const val ENCAPSULATED_SSL = 3
    const val AUTH_COMPLETE = 4

    // Media messages (Media.MsgType): DATA and CODEC_CONFIG carry the stream itself
    const val MEDIA_DATA = 0
    const val MEDIA_CODEC_CONFIG = 1

    // Control-range types sent on a service channel need the control flag
    fun isControlType(type: Int) = type in 1..26

    // A whole unencrypted message, as sent before and during the TLS handshake
    fun plainFrame(channel: Int, type: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(6 + payload.size)
        frame[0] = channel.toByte()
        frame[1] = (FLAG_FIRST or FLAG_LAST).toByte()
        putShort(frame, 2, payload.size + 2)
        putShort(frame, 4, type)
        payload.copyInto(frame, 6)
        return frame
    }

    fun body(type: Int, message: MessageLite): ByteArray {
        val proto = message.toByteArray()
        val body = ByteArray(2 + proto.size)
        putShort(body, 0, type)
        proto.copyInto(body, 2)
        return body
    }

    fun putShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value shr 8).toByte()
        buffer[offset + 1] = value.toByte()
    }

    fun short(buffer: ByteArray, offset: Int) =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
}
