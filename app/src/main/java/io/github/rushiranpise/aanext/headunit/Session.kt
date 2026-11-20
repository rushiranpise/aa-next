package io.github.rushiranpise.aanext.headunit

import android.content.Context
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import com.google.protobuf.MessageLite
import io.github.rushiranpise.aanext.PhoneScreen
import io.github.rushiranpise.aanext.headunit.proto.Common
import io.github.rushiranpise.aanext.headunit.proto.Control
import io.github.rushiranpise.aanext.headunit.proto.Input
import io.github.rushiranpise.aanext.headunit.proto.Media
import io.github.rushiranpise.aanext.headunit.proto.Sensors
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.ConnectException

// One Android Auto session with the phone's own head unit server: connect, version exchange, TLS, then answer
// Android Auto until either side says goodbye. Runs on its own thread; onEnd is called once, with an error or null.
// Ported from Headunit Revived (andreknieriem/open-headunit), AGPL-3.0.
internal class Session(
    private val context: Context,
    private val geometry: Geometry,
    surface: Surface,
    private val onEnd: (Throwable?) -> kotlin.Unit,
) {
    private val tls = Tls(context)
    private val video = VideoSink(surface, geometry.videoWidth, geometry.videoHeight)
    private val audio = AudioSink()
    private val mic = MicSource(context) { pcm, length, timestamp -> sendMic(pcm, length, timestamp) }
    private val writeLock = Any()
    private val mediaSessions = HashMap<Int, Int>() // channel -> session id from MEDIA START, which ACKs return
    private val fragments = HashMap<Int, ByteArrayOutputStream>()
    private var micSession = 0
    private var link: ParcelFileDescriptor? = null
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    @Volatile private var running = true
    @Volatile private var connected = false
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    // Touch and focus come from the UI thread, which may not do network I/O (StrictMode throws after the record was
    // already encrypted, and the skipped TLS record breaks the whole stream), so they go out on this thread, in order
    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun start() = Thread(::run, "AA Next head unit").start()

    // Says goodbye if connected, then closes; the read loop ends on the closed link
    fun stop() {
        if (!running) return
        running = false
        Thread {
            if (connected) {
                runCatching {
                    send(Aap.CH_CONTROL, BYEBYE_REQUEST, Control.ByeByeRequest.newBuilder().setReason(Control.ByeByeReason.USER_SELECTION).build())
                    Thread.sleep(300)
                }
            }
            close()
        }.start()
    }

    fun gainVideoFocus() {
        if (connected) sender.execute { runCatching { sendVideoFocus(unsolicited = true) } }
    }

    fun touch(event: MotionEvent, screenWidth: Int, screenHeight: Int) {
        if (!connected) return
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
            MotionEvent.ACTION_POINTER_DOWN -> Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_DOWN
            MotionEvent.ACTION_MOVE -> Input.TouchEvent.PointerAction.TOUCH_ACTION_MOVE
            MotionEvent.ACTION_UP -> Input.TouchEvent.PointerAction.TOUCH_ACTION_UP
            MotionEvent.ACTION_POINTER_UP -> Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_UP
            MotionEvent.ACTION_CANCEL -> Input.TouchEvent.PointerAction.TOUCH_ACTION_CANCEL
            else -> return
        }
        val touch = Input.TouchEvent.newBuilder().setAction(action).setActionIndex(event.actionIndex)
        for (i in 0 until event.pointerCount) {
            touch.addPointerData(
                Input.TouchEvent.Pointer.newBuilder()
                    .setPointerId(event.getPointerId(i))
                    .setX(geometry.videoX(event.getX(i), screenWidth))
                    .setY(geometry.videoY(event.getY(i), screenHeight))
            )
        }
        val report = Input.InputReport.newBuilder().setTimestamp(SystemClock.elapsedRealtimeNanos()).setTouchEvent(touch).build()
        sender.execute { runCatching { send(Aap.CH_INPUT, Input.InputMsgType.EVENT_VALUE, report) } }
    }

    private fun run() {
        var error: Throwable? = null
        try {
            connect()
            handshake()
            video.start()
            connected = true
            while (running) readFrame()
        } catch (e: Throwable) {
            if (running) error = if (connected) e else ConnectException("Android Auto didn't answer").apply { initCause(e) }
            Log.w(TAG, "session ended", e)
        } finally {
            running = false
            connected = false
            close()
            onEnd(error)
        }
    }

    // A Unix socket the module connects to the server from inside Android Auto (see PhoneScreen)
    private fun connect() {
        val link = PhoneScreen.connect().also { link = it }
        input = DataInputStream(FileInputStream(link.fileDescriptor).buffered(1 shl 16))
        output = FileOutputStream(link.fileDescriptor)
    }

    // With nothing answering (root refused, or the module not active in Android Auto) the first read would block
    // for good, so the link is closed if the handshake isn't done in time
    private fun handshake() {
        val watchdog = Thread {
            try {
                Thread.sleep(HANDSHAKE_TIMEOUT_MS)
                close()
            } catch (_: InterruptedException) {
            }
        }.apply { start() }
        try {
            writeRaw(Aap.plainFrame(Aap.CH_CONTROL, Aap.VERSION_REQUEST, byteArrayOf(0, 1, 0, 2)))
            while (readPlain().first != Aap.VERSION_RESPONSE) {
            }
            tls.handshake(
                read = {
                    var message = readPlain()
                    while (message.first != Aap.ENCAPSULATED_SSL) message = readPlain()
                    message.second
                },
                write = { writeRaw(Aap.plainFrame(Aap.CH_CONTROL, Aap.ENCAPSULATED_SSL, it)) },
            )
            writeRaw(Aap.plainFrame(Aap.CH_CONTROL, Aap.AUTH_COMPLETE, byteArrayOf(8, 0))) // status: success
        } finally {
            watchdog.interrupt()
        }
    }

    // An unencrypted message from before and during the handshake: its type and payload
    private fun readPlain(): Pair<Int, ByteArray> {
        val header = ByteArray(4)
        input.readFully(header)
        val body = ByteArray(Aap.short(header, 2))
        input.readFully(body)
        return Aap.short(body, 0) to body.copyOfRange(2, body.size)
    }

    private val frame = ByteArray(0x10000)
    private val header = ByteArray(4)

    private fun readFrame() {
        input.readFully(header)
        val channel = header[0].toInt() and 0xFF
        val flags = header[1].toInt() and 0xFF
        val length = Aap.short(header, 2)
        val first = flags and Aap.FLAG_FIRST != 0
        val last = flags and Aap.FLAG_LAST != 0
        if (first && !last) input.readInt() // the whole message's length; reassembly doesn't need it
        input.readFully(frame, 0, length)
        val plain = if (flags and Aap.FLAG_ENCRYPTED != 0) tls.decrypt(frame, length) else frame.copyOf(length)
        if (plain.isEmpty()) return
        when {
            first && last -> handle(channel, flags, plain)
            first -> fragments[channel] = ByteArrayOutputStream().apply { write(plain) }
            else -> {
                val message = fragments[channel] ?: return
                message.write(plain)
                if (last) handle(channel, flags, fragments.remove(channel)!!.toByteArray())
            }
        }
    }

    // message is [type][payload]
    private fun handle(channel: Int, flags: Int, message: ByteArray) {
        if (message.size < 2) return
        val type = Aap.short(message, 0)
        when {
            type == CHANNEL_OPEN_REQUEST -> openChannel(channel)
            flags and Aap.FLAG_CONTROL != 0 || channel == Aap.CH_CONTROL -> control(channel, type, message)
            channel == Aap.CH_VIDEO -> video(type, message)
            channel == Aap.CH_SYSTEM_AUDIO -> audio(channel, type, message)
            channel == Aap.CH_MIC -> mic(type, message)
            channel == Aap.CH_SENSOR && type == Sensors.SensorsMsgType.SENSOR_STARTREQUEST_VALUE -> startSensor(channel, message)
            channel == Aap.CH_INPUT && type == Input.InputMsgType.BINDINGREQUEST_VALUE ->
                send(channel, Input.InputMsgType.BINDINGRESPONSE_VALUE, Input.BindingResponse.newBuilder().setStatus(Common.MessageStatus.STATUS_SUCCESS).build())
        }
    }

    private fun openChannel(channel: Int) {
        send(channel, CHANNEL_OPEN_RESPONSE, Control.ChannelOpenResponse.newBuilder().setStatus(Common.MessageStatus.STATUS_SUCCESS).build())
        if (channel == Aap.CH_SENSOR) sendDrivingStatus(channel)
    }

    private fun control(channel: Int, type: Int, message: ByteArray) {
        when (type) {
            SERVICE_DISCOVERY_REQUEST -> send(Aap.CH_CONTROL, SERVICE_DISCOVERY_RESPONSE, Discovery.response(geometry))
            PING_REQUEST -> {
                val ping = Control.PingRequest.parseFrom(payload(message))
                send(channel, PING_RESPONSE, Control.PingResponse.newBuilder().setTimestamp(ping.timestamp).build())
            }
            NAV_FOCUS_REQUEST ->
                send(channel, NAV_FOCUS_NOTIFICATION, Control.NavFocusNotification.newBuilder().setFocusType(Control.NavFocusType.NAV_FOCUS_PROJECTED).build())
            AUDIO_FOCUS_REQUEST -> {
                val request = Control.AudioFocusRequestNotification.parseFrom(payload(message)).request
                val state = when (request) {
                    Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE -> Control.AudioFocusNotification.AudioFocusStateType.STATE_LOSS
                    Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN -> Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN
                    else -> Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN_TRANSIENT
                }
                send(channel, AUDIO_FOCUS_NOTIFICATION, Control.AudioFocusNotification.newBuilder().setFocusState(state).build())
            }
            BYEBYE_REQUEST -> {
                Log.i(TAG, "Android Auto said goodbye: ${Control.ByeByeRequest.parseFrom(payload(message)).reason}")
                send(channel, BYEBYE_RESPONSE, Control.ByeByeResponse.newBuilder().build())
                running = false
            }
            BYEBYE_RESPONSE -> running = false
        }
    }

    private fun video(type: Int, message: ByteArray) {
        when (type) {
            Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE -> {
                sendConfig(Aap.CH_VIDEO, VIDEO_MAX_UNACKED)
                sendVideoFocus(unsolicited = true)
            }
            Media.MsgType.MEDIA_MESSAGE_START_VALUE -> mediaSessions[Aap.CH_VIDEO] = Media.Start.parseFrom(payload(message)).sessionId
            Aap.MEDIA_CODEC_CONFIG -> video.configure(message.copyOfRange(2, message.size))
            Aap.MEDIA_DATA -> {
                // [type][timestamp][H.264]; Headunit Revived also meets frames without the timestamp
                val offset = if (!startCodeAt(message, MEDIA_DATA_OFFSET) && startCodeAt(message, 2)) 2 else MEDIA_DATA_OFFSET
                if (message.size > offset) video.frame(message.copyOfRange(offset, message.size))
                ack(Aap.CH_VIDEO)
            }
            Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST_VALUE -> {
                // Android Auto's own Exit asks for native focus: the phone screen then simply ends
                val request = Media.VideoFocusRequestNotification.parseFrom(payload(message))
                Log.i(TAG, "video focus request: ${request.mode}")
                if (request.mode == Media.VideoFocusMode.VIDEO_FOCUS_NATIVE) running = false else sendVideoFocus(unsolicited = false)
            }
        }
    }

    private fun audio(channel: Int, type: Int, message: ByteArray) {
        when (type) {
            Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE -> {
                sendConfig(channel, AUDIO_MAX_UNACKED)
                send(
                    Aap.CH_CONTROL, AUDIO_FOCUS_NOTIFICATION,
                    Control.AudioFocusNotification.newBuilder()
                        .setFocusState(Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN)
                        .setUnsolicited(true)
                        .build(),
                )
            }
            Media.MsgType.MEDIA_MESSAGE_START_VALUE -> {
                mediaSessions[channel] = Media.Start.parseFrom(payload(message)).sessionId
                audio.start()
            }
            Media.MsgType.MEDIA_MESSAGE_STOP_VALUE -> audio.stop()
            Aap.MEDIA_DATA -> {
                if (message.size > MEDIA_DATA_OFFSET) audio.write(message, MEDIA_DATA_OFFSET, message.size - MEDIA_DATA_OFFSET)
                ack(channel)
            }
        }
    }

    private fun mic(type: Int, message: ByteArray) {
        when (type) {
            Media.MsgType.MEDIA_MESSAGE_MICROPHONE_REQUEST_VALUE -> {
                val request = Media.MicrophoneRequest.parseFrom(payload(message))
                val ok = if (request.open) {
                    micSession++
                    mic.start(if (request.hasMaxUnacked()) request.maxUnacked else 2)
                } else {
                    mic.stop()
                    true
                }
                val status = if (ok) Common.MessageStatus.STATUS_SUCCESS_VALUE else Common.MessageStatus.STATUS_INTERNAL_ERROR_VALUE
                send(Aap.CH_MIC, Media.MsgType.MEDIA_MESSAGE_MICROPHONE_RESPONSE_VALUE,
                    Media.MicrophoneResponse.newBuilder().setStatus(status).setSessionId(micSession).build())
            }
            Media.MsgType.MEDIA_MESSAGE_ACK_VALUE -> mic.acked()
        }
    }

    private fun startSensor(channel: Int, message: ByteArray) {
        val request = Sensors.SensorRequest.parseFrom(payload(message))
        send(channel, Sensors.SensorsMsgType.SENSOR_STARTRESPONSE_VALUE,
            Sensors.SensorResponse.newBuilder().setStatus(Common.MessageStatus.STATUS_SUCCESS).build())
        when (request.type) {
            Sensors.SensorType.DRIVING_STATUS -> sendDrivingStatus(channel)
            Sensors.SensorType.NIGHT -> {
                val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                send(channel, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, Sensors.SensorBatch.newBuilder()
                    .addNightMode(Sensors.SensorBatch.NightData.newBuilder().setIsNightMode(night)).build())
            }
            else -> {}
        }
    }

    // Unrestricted: the phone screen is meant to be used fully, as when parked
    private fun sendDrivingStatus(channel: Int) = send(channel, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, Sensors.SensorBatch.newBuilder()
        .addDrivingStatus(Sensors.SensorBatch.DrivingStatusData.newBuilder().setStatus(Sensors.SensorBatch.DrivingStatusData.Status.UNRESTRICTED_VALUE))
        .build())

    private fun sendConfig(channel: Int, maxUnacked: Int) = send(channel, Media.MsgType.MEDIA_MESSAGE_CONFIG_VALUE, Media.Config.newBuilder()
        .setStatus(Media.Config.ConfigStatus.STATUS_READY)
        .setMaxUnacked(maxUnacked)
        .addConfigurationIndices(0)
        .build())

    private fun sendVideoFocus(unsolicited: Boolean) = send(Aap.CH_VIDEO, Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE,
        Media.VideoFocusNotification.newBuilder().setMode(Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED).setUnsolicited(unsolicited).build())

    // One credit back per received DATA message, in the session MEDIA START named
    private fun ack(channel: Int) = send(channel, Media.MsgType.MEDIA_MESSAGE_ACK_VALUE,
        Media.Ack.newBuilder().setSessionId(mediaSessions[channel] ?: 0).setAck(1).build())

    // [DATA type][8-byte timestamp][PCM]
    private fun sendMic(pcm: ByteArray, length: Int, timestampUs: Long) {
        if (!connected) return
        val body = ByteArray(MEDIA_DATA_OFFSET + length)
        var t = timestampUs
        for (i in 9 downTo 2) {
            body[i] = t.toByte()
            t = t shr 8
        }
        pcm.copyInto(body, MEDIA_DATA_OFFSET, 0, length)
        runCatching { sendBody(Aap.CH_MIC, body, control = false) }
    }

    private fun send(channel: Int, type: Int, message: MessageLite) =
        sendBody(channel, Aap.body(type, message), control = channel != Aap.CH_CONTROL && Aap.isControlType(type))

    // An encrypted record that doesn't make it out leaves a gap in TLS's record sequence, so a failed write ends the
    // session instead of going on with a stream Android Auto can no longer decrypt
    private fun sendBody(channel: Int, body: ByteArray, control: Boolean) = synchronized(writeLock) {
        val cipher = tls.encrypt(body)
        val out = ByteArray(4 + cipher.size)
        out[0] = channel.toByte()
        out[1] = (Aap.FLAG_FIRST or Aap.FLAG_LAST or Aap.FLAG_ENCRYPTED or if (control) Aap.FLAG_CONTROL else 0).toByte()
        Aap.putShort(out, 2, cipher.size)
        cipher.copyInto(out, 4)
        try {
            output.write(out)
            output.flush()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun writeRaw(bytes: ByteArray) = synchronized(writeLock) {
        output.write(bytes)
        output.flush()
    }

    private fun payload(message: ByteArray) = message.copyOfRange(2, message.size)

    private fun startCodeAt(message: ByteArray, offset: Int) = message.size > offset + 3 &&
        message[offset].toInt() == 0 && message[offset + 1].toInt() == 0 &&
        (message[offset + 2].toInt() == 1 || message[offset + 2].toInt() == 0 && message[offset + 3].toInt() == 1)

    private fun close() {
        if (!closed.compareAndSet(false, true)) return
        sender.shutdown()
        mic.stop()
        video.stop()
        audio.release()
        // Shut down first: closing alone doesn't wake a read blocked on it
        link?.let {
            runCatching { Os.shutdown(it.fileDescriptor, OsConstants.SHUT_RDWR) }
            runCatching { it.close() }
        }
    }

    private companion object {
        const val TAG = "AANextPhoneScreen"
        const val HANDSHAKE_TIMEOUT_MS = 25_000L // the server's start included
        const val MEDIA_DATA_OFFSET = 10 // type + 8-byte timestamp
        const val VIDEO_MAX_UNACKED = 16
        const val AUDIO_MAX_UNACKED = 16

        // Control.ControlMsgType
        const val SERVICE_DISCOVERY_REQUEST = 5
        const val SERVICE_DISCOVERY_RESPONSE = 6
        const val CHANNEL_OPEN_REQUEST = 7
        const val CHANNEL_OPEN_RESPONSE = 8
        const val PING_REQUEST = 11
        const val PING_RESPONSE = 12
        const val NAV_FOCUS_REQUEST = 13
        const val NAV_FOCUS_NOTIFICATION = 14
        const val BYEBYE_REQUEST = 15
        const val BYEBYE_RESPONSE = 16
        const val AUDIO_FOCUS_REQUEST = 18
        const val AUDIO_FOCUS_NOTIFICATION = 19
    }
}
