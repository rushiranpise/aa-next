package io.github.rushiranpise.aanext.headunit

import io.github.rushiranpise.aanext.headunit.proto.Common
import io.github.rushiranpise.aanext.headunit.proto.Control
import io.github.rushiranpise.aanext.headunit.proto.Control.Service
import io.github.rushiranpise.aanext.headunit.proto.Control.Service.MediaSinkService.VideoConfiguration
import io.github.rushiranpise.aanext.headunit.proto.Media
import io.github.rushiranpise.aanext.headunit.proto.Sensors
import kotlin.math.roundToInt

// How the 1280x720 video maps onto the phone's landscape screen: Android Auto leaves margins inside it (cropped here)
// so the part it draws has the screen's shape, at three quarters of the phone's density since its UI is sized for a car
// seat. The video view fills the screen one way and overflows the other.
internal class Geometry(screenWidth: Int, screenHeight: Int, densityDpi: Int) {
    val videoWidth = 1280
    val videoHeight = 720
    val marginWidth: Int
    val marginHeight: Int
    val viewWidth: Int
    val viewHeight: Int
    val density: Int

    init {
        if (screenWidth * videoHeight >= screenHeight * videoWidth) {
            viewWidth = screenWidth
            viewHeight = screenWidth * videoHeight / videoWidth
            marginWidth = 0
            marginHeight = (videoHeight - videoWidth * screenHeight / screenWidth) and 1.inv()
        } else {
            viewHeight = screenHeight
            viewWidth = screenHeight * videoWidth / videoHeight
            marginHeight = 0
            marginWidth = (videoWidth - videoHeight * screenWidth / screenHeight) and 1.inv()
        }
        density = (densityDpi * UI_SCALE * videoWidth / viewWidth).roundToInt().coerceIn(120, 480)
    }

    // A point on the screen (view centred on it) in Android Auto's touch pixels. Its UI sits between the margins, but
    // touches count from the UI's own corner, not the video's (as Headunit Revived found: a centred mapping lands
    // half a margin low, a whole rail button off on a 2400x1080 phone).
    fun videoX(x: Float, screenWidth: Int) = ((x - (screenWidth - viewWidth) / 2f) * videoWidth / viewWidth - marginWidth / 2)
        .toInt().coerceIn(0, videoWidth - marginWidth - 1)

    fun videoY(y: Float, screenHeight: Int) = ((y - (screenHeight - viewHeight) / 2f) * videoHeight / viewHeight - marginHeight / 2)
        .toInt().coerceIn(0, videoHeight - marginHeight - 1)

    private companion object {
        const val UI_SCALE = 0.75f
    }
}

// What this head unit offers Android Auto. Ported from Headunit Revived (andreknieriem/open-headunit), AGPL-3.0.
// Like Headunit Revived's self mode it offers only the system sounds sink: media and speech then play on the phone
// itself as usual. A microphone is required, since the newer car service ends the session for a car without one.
internal object Discovery {
    fun response(geometry: Geometry): Control.ServiceDiscoveryResponse {
        val sensors = Service.newBuilder().setId(Aap.CH_SENSOR).setSensorSourceService(
            Service.SensorSourceService.newBuilder()
                .addSensors(sensor(Sensors.SensorType.DRIVING_STATUS))
                .addSensors(sensor(Sensors.SensorType.NIGHT))
        )
        val video = Service.newBuilder().setId(Aap.CH_VIDEO).setMediaSinkService(
            Service.MediaSinkService.newBuilder()
                .setAvailableType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                .setAudioType(Media.AudioStreamType.NONE)
                .setAvailableWhileInCall(true)
                .addVideoConfigs(
                    VideoConfiguration.newBuilder()
                        .setCodecResolution(VideoConfiguration.VideoCodecResolutionType._1280x720)
                        .setFrameRate(VideoConfiguration.VideoFrameRateType._30)
                        .setMarginWidth(geometry.marginWidth)
                        .setMarginHeight(geometry.marginHeight)
                        .setDensity(geometry.density)
                        .setVideoCodecType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                )
        )
        val input = Service.newBuilder().setId(Aap.CH_INPUT).setInputSourceService(
            Service.InputSourceService.newBuilder().setTouchscreen(
                Service.InputSourceService.TouchConfig.newBuilder().setWidth(geometry.videoWidth).setHeight(geometry.videoHeight)
            )
        )
        val systemAudio = Service.newBuilder().setId(Aap.CH_SYSTEM_AUDIO).setMediaSinkService(
            Service.MediaSinkService.newBuilder()
                .setAvailableType(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
                .setAudioType(Media.AudioStreamType.SYSTEM)
                .addAudioConfigs(AudioSink.SYSTEM_FORMAT)
        )
        val mic = Service.newBuilder().setId(Aap.CH_MIC).setMediaSourceService(
            Service.MediaSourceService.newBuilder()
                .setType(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
                .setAudioConfig(MicSource.FORMAT)
        )
        return Control.ServiceDiscoveryResponse.newBuilder()
            .addServices(sensors)
            .addServices(video)
            .addServices(input)
            .addServices(systemAudio)
            .addServices(mic)
            .setMake(MAKE)
            .setModel(MODEL)
            .setYear(YEAR)
            .setVehicleId(VEHICLE_ID)
            .setDriverPosition(Control.DriverPosition.DRIVER_POSITION_LEFT)
            .setHeadUnitMake(MAKE)
            .setHeadUnitModel(MODEL)
            .setHeadUnitSoftwareBuild("1")
            .setHeadUnitSoftwareVersion("1.0")
            .setCanPlayNativeMediaDuringVr(false)
            .setDisplayName(MODEL)
            .setHeadunitInfo(
                Common.HeadUnitInfo.newBuilder()
                    .setMake(MAKE)
                    .setModel(MODEL)
                    .setYear(YEAR)
                    .setVehicleId(VEHICLE_ID)
                    .setHeadUnitMake(MAKE)
                    .setHeadUnitModel(MODEL)
                    .setHeadUnitSoftwareBuild("1")
                    .setHeadUnitSoftwareVersion("1.0")
                    .setVehicleType(VEHICLE_TYPE_CAR)
            )
            .build()
    }

    private fun sensor(type: Sensors.SensorType) = Service.SensorSourceService.Sensor.newBuilder().setType(type)

    private const val MAKE = "AA Next"
    private const val MODEL = "Phone screen"
    private const val YEAR = "2026"
    private const val VEHICLE_ID = "aanext-phone-screen"
    private const val VEHICLE_TYPE_CAR = 1
}
