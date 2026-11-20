package uk.noammm.aamods

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

// Shared between the settings screen (writes) and the hooks inside Android Auto (reads). The hooks read
// the settings either from the module's own file through XSharedPreferences, which only works where the
// framework injects the module into its own app (Vector does, and then keeps that file readable), or
// through the module's bridge provider, which every framework can serve because the module app is a
// plain app no framework has to touch (LSPosed injects only scoped apps, so its shared file stays empty
// and the bridge is what carries the settings there)
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
    const val NOTIFICATION_TIMEOUT = "notification_timeout"
    // Android Auto's own heads-up timeout, in milliseconds
    const val NOTIFICATION_TIMEOUT_DEFAULT = 8000
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

    // World-readable is what lets the hooks inside Android Auto read these, and Vector only allows it once
    // AA Mods is enabled there, so null means it isn't. LSPosed never shares the file, so callers also take
    // the report from inside Android Auto as a sign the module is on (AppState.reported)
    @SuppressLint("WorldReadableFiles")
    fun open(context: Context): SharedPreferences? = try {
        @Suppress("DEPRECATION")
        context.getSharedPreferences(FILE, Context.MODE_WORLD_READABLE)
    } catch (_: SecurityException) {
        null
    }

    // The settings as a Bundle, for the bridge to hand the hooks. Each value keeps its stored type.
    fun bundle(prefs: SharedPreferences): Bundle {
        val out = Bundle()
        for ((key, value) in prefs.all) when (value) {
            is String -> out.putString(key, value)
            is Int -> out.putInt(key, value)
            is Long -> out.putLong(key, value)
            is Boolean -> out.putBoolean(key, value)
            is Float -> out.putFloat(key, value)
            is Set<*> -> out.putStringArrayList(key, ArrayList(value.filterIsInstance<String>()))
        }
        return out
    }

    // The hooks' read-only view of what the bridge served, typed like SharedPreferences so the hooks don't
    // care which way the settings came in
    class FromBridge(bundle: Bundle) : SharedPreferences {
        private val stored = HashMap<String, Any?>().apply {
            for (key in bundle.keySet()) @Suppress("DEPRECATION") { put(key, bundle.get(key)) }
        }

        override fun getAll(): Map<String, *> = stored
        override fun getString(key: String, defValue: String?): String? = stored[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            (stored[key] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = (stored[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (stored[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (stored[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = stored[key] as? Boolean ?: defValue
        override fun contains(key: String) = stored.containsKey(key)

        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException("read-only")
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    // Nothing stored yet: every switch answers its default, exactly like a missing file did
    val empty: SharedPreferences = FromBridge(Bundle())
}

// AA Mods' own state, which the hooks never read
object AppState {
    const val FILE = "state"
    const val WELCOME_DONE = "welcome_done"
    // The AA Mods version the module last reported running inside Android Auto with, and when
    const val LOADED_VERSION = "loaded_version"
    const val LOADED_AT = "loaded_at"
    // One hook report per Android Auto process, keyed by its process name
    const val STATUS_PREFIX = "status_"

    // The newest hook report: the projection process's when it has one, otherwise the newest. Android
    // Auto loads the module into several processes, and the projection one is the screen's. Hooks that
    // did work in another process keep that count, and a failure in any process outranks a ready state.
    fun status(context: Context): JSONObject? {
        val state = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        // A report from an older module or Android Auto build says nothing about this one, so the screen
        // waits for the fresh report instead of showing stale green rows after an update
        val androidAuto = runCatching {
            context.packageManager.getPackageInfo(Entry.GEARHEAD, 0).versionName.orEmpty()
        }.getOrDefault("")
        val reports = state.all.entries.mapNotNull { entry ->
            if (!entry.key.startsWith(STATUS_PREFIX)) return@mapNotNull null
            val report = (entry.value as? String)
                ?.let { text -> runCatching { JSONObject(text) }.getOrNull() } ?: return@mapNotNull null
            if (report.optLong("at") < installed) return@mapNotNull null
            if (androidAuto.isNotEmpty() && report.optString("androidAuto").let { it.isNotEmpty() && it != androidAuto }) {
                return@mapNotNull null
            }
            report
        }
        if (reports.isEmpty()) return null
        val base = reports.firstOrNull { it.optString("process").endsWith(":projection") }
            ?: reports.firstOrNull { it.optString("process") == Entry.GEARHEAD }
            ?: reports.maxByOrNull { it.optLong("at") } ?: return null
        val merged = JSONArray()
        val byName = LinkedHashMap<String, JSONObject>()
        for (report in reports) {
            val hooks = report.optJSONArray("hooks") ?: continue
            for (i in 0 until hooks.length()) {
                val hook = runCatching { hooks.getJSONObject(i) }.getOrNull() ?: continue
                val name = hook.optString("name")
                val known = byName[name]
                if (known == null) {
                    byName[name] = JSONObject(hook.toString())
                } else {
                    known.put("used", maxOf(known.optInt("used"), hook.optInt("used")))
                    if (hook.optString("state") == Status.READY && known.optString("detail").isEmpty()) {
                        known.put("detail", hook.optString("detail"))
                    }
                    if (hook.optString("state") == Status.FAILED) {
                        known.put("state", Status.FAILED)
                        known.put("detail", hook.optString("detail"))
                    }
                }
            }
        }
        for (hook in byName.values) merged.put(hook)
        base.put("hooks", merged)
        return base
    }

    // Whether the hooks reported running inside Android Auto with this version since it was installed.
    // Reaching Android Auto means the module is on in the framework and in Android Auto's scope, which
    // is the one enabled-sign that works on every framework
    fun reported(context: Context): Boolean {
        val state = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        return state.getString(LOADED_VERSION, null) == BuildConfig.VERSION_NAME &&
            state.getLong(LOADED_AT, 0) > installed
    }
}
