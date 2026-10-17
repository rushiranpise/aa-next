package uk.noammm.aamods

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

// Shared between the settings screen (writes) and the hooks inside Android Auto (reads through XSharedPreferences)
object Prefs {
    const val FILE = "settings"

    const val NO_LOCKOUTS = "no_lockouts"
    const val NO_FORCED_APPS = "no_forced_apps"
    const val ANY_CAR_APP = "any_car_app"
    const val ANY_APP_LAUNCHER = "any_app_launcher"
    const val PHONE_APP_SCALE = "phone_app_scale"
    const val PINNED_APPS = "pinned_apps"
    const val START_ON_LAUNCHER = "start_on_launcher"
    const val RECENTS = "recents"
    const val HIDE_NEWS_WEATHER = "hide_news_weather"
    const val MUTE_NOTIFICATIONS = "mute_notifications"
    const val NO_TELEMETRY = "no_telemetry"
    const val LIGHTER = "lighter"
    const val VIDEO_RESOLUTION = "video_resolution"
    const val DEFAULT_NAV = "default_nav"
    const val DEFAULT_MEDIA = "default_media"
    const val CUSTOM_TILE = "custom_tile"
    const val CUSTOM_TILE_URL = "custom_tile_url"
    const val FLAGS = "flags"
    const val MICROG = "microg"

    // Every switch starts on except these, which change more than most people want or need setting up
    private val OFF_BY_DEFAULT = setOf(MICROG, CUSTOM_TILE, LIGHTER, MUTE_NOTIFICATIONS)

    fun on(prefs: SharedPreferences, key: String) = prefs.getBoolean(key, key !in OFF_BY_DEFAULT)

    // World-readable is what lets the hooks inside Android Auto read these, and Vector only allows it once AA Mods is
    // enabled there, so null means it isn't
    @SuppressLint("WorldReadableFiles")
    fun open(context: Context): SharedPreferences? = try {
        @Suppress("DEPRECATION")
        context.getSharedPreferences(FILE, Context.MODE_WORLD_READABLE)
    } catch (_: SecurityException) {
        null
    }
}

// AA Mods' own state, which the hooks never read
object AppState {
    const val FILE = "state"
    const val WELCOME_DONE = "welcome_done"
    // The AA Mods version the module last reported running inside Android Auto with, and when
    const val LOADED_VERSION = "loaded_version"
    const val LOADED_AT = "loaded_at"
}
