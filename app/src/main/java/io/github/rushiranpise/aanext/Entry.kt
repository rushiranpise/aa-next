package io.github.rushiranpise.aanext

import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.os.PatternMatcher
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.os.UserHandle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import de.robv.android.xposed.callbacks.XCallback
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import java.lang.reflect.Modifier
import java.util.concurrent.Executor
import java.util.function.Consumer

// Class and member names are the obfuscated ones from Android Auto 18.2 and change between versions
class Entry : IXposedHookLoadPackage {
    private lateinit var prefs: SharedPreferences
    private lateinit var cl: ClassLoader
    private lateinit var dex: Dex

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == SYSTEM_FRAMEWORK) {
            if (Prefs.on(XSharedPreferences(BuildConfig.APPLICATION_ID, Prefs.FILE), Prefs.MICROG)) {
                safely("system access") { hookSystemAccess(lpparam.classLoader) }
            }
            return
        }
        if (lpparam.packageName != GEARHEAD) return
        cl = lpparam.classLoader
        dex = Dex(
            lpparam.appInfo?.sourceDir?.let { File(it) },
            lpparam.appInfo?.dataDir?.let { File(it, "cache") },
            cl,
        )
        prefs = loadPrefs()
        log("loaded into ${lpparam.processName}")

        safely("flags") { hookFlags() }
        safely("phone screen") { hookPhoneScreen() }
        if (lpparam.processName.endsWith(":provider")) safely("report", track = false) { reportLoaded() }
        safely("phone screen on USB changes") { keepPhoneScreenOnUsbChanges() }
        if (Prefs.on(prefs, Prefs.NO_LOCKOUTS)) safely("lockouts") { hookDrivingStatus() } else Status.off("lockouts")
        if (Prefs.on(prefs, Prefs.NO_FORCED_APPS)) safely("forced apps") { hookForcedApps() } else Status.off("forced apps")
        if (Prefs.on(prefs, Prefs.ANY_CAR_APP)) safely("app allowlist") { hookAppAllowlist() } else Status.off("app allowlist")
        if (Prefs.on(prefs, Prefs.NO_TELEMETRY)) safely("telemetry binds") { hookTelemetryBinds() } else Status.off("telemetry binds")
        if (Prefs.on(prefs, Prefs.MICROG)) safely("microG") { hookMicroG() } else Status.off("microG")
        if (Prefs.on(prefs, Prefs.ANY_APP_LAUNCHER)) {
            safely("launcher tabs") { hookLauncherTabs() }
            safely("phone app rail") { keepRailBesidePhoneApps() }
        } else {
            Status.off("launcher tabs")
            Status.off("phone app rail")
        }
        val scale = prefs.getInt(Prefs.PHONE_APP_SCALE, 100)
        if (scale != 100) safely("phone app size") { hookPhoneAppDensity(scale) } else Status.off("phone app size")
        val pinned = prefs.getString(Prefs.PINNED_APPS, "").orEmpty().split(',').filter { it.isNotEmpty() }
        if (pinned.isNotEmpty()) safely("pinned apps") { hookPinnedApps(pinned) } else Status.off("pinned apps")
        if (onlyOpenApps()) safely("open apps") {
            trackOpenApps()
            dashboardShowsOnlyOpenApps()
        } else Status.off("open apps")
        if (Prefs.on(prefs, Prefs.START_ON_LAUNCHER)) safely("start on launcher") { hookStartOnLauncher() } else Status.off("start on launcher")
        if (Prefs.on(prefs, Prefs.RECENTS)) safely("recents") { hookRecents() } else Status.off("recents")
        if (Prefs.on(prefs, Prefs.LIGHTER)) safely("frame rate") { hookFrameRate() } else Status.off("frame rate")
        val resolution = prefs.getString(Prefs.VIDEO_RESOLUTION, "").orEmpty()
        if (resolution.isNotEmpty()) safely("resolution") { hookResolution(resolution) } else Status.off("resolution")
        if (Prefs.on(prefs, Prefs.HIDE_NEWS_WEATHER)) safely("assistant tiles") { hookAssistantTiles() } else Status.off("assistant tiles")
        safely("default apps") { hookDefaultApps() }
        if (Prefs.on(prefs, Prefs.MUTE_NOTIFICATIONS)) safely("notifications") { hookNotifications() } else Status.off("notifications")
        // The dex index is only needed while hooks are being installed
        safely("dex", track = false) { dex.close() }
        reportStatus(lpparam.processName)
    }

    // Every Phenotype flag is read through one handle base class (xgr in 18.2), whose b field holds the
    // flag name: ew() reads it, ex(Context) reads it with a context, and the typed handles (boolean,
    // long, double) resolve through ez(), where their own getters end up too. The class name changes
    // with every build, but it is the one class that logs "Invalid Phenotype flag value for flag ".
    private fun hookFlags() {
        val overrides = flagOverrides()
        val allowAll = if (Prefs.on(prefs, Prefs.ANY_APP_LAUNCHER)) setOf(PHONE_APPS_FILTER) else emptySet()
        if (overrides.isEmpty() && allowAll.isEmpty()) {
            Status.off("flags", "No overrides set")
            return
        }
        flagNames = overrides.keys + allowAll
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val name = flagName(param.thisObject) ?: return
                if (name in allowAll) {
                    allowAllFilter(param.result)?.let {
                        param.result = it
                        logOnce("flag: $name = allow all apps")
                    }
                    return
                }
                val value = overrides[name] ?: return
                convert(param.result, value)?.let {
                    param.result = it
                    logOnce("flag: $name = $value")
                }
            }
        }
        // The accept check is what tells a reused class name from the real one: the handle must still have
        // an Object-returning getter with no arguments
        val holder = dex.clazz("flags", "xfj", accept = { c -> c.declaredMethods.any { Modifier.isPublic(it.modifiers) && it.returnType == Any::class.java && it.parameterCount == 0 } }) {
            usingEqStrings(PHENOTYPE_INVALID_FLAG)
        } ?: error("no flag handle class")
        var getters = 0
        for (method in holder.declaredMethods) {
            // Public: the getters implement the flag interfaces, so they are public, while the class's
            // own helper that also returns Object (e in 18.2) is protected and overridden per flag
            if (method.returnType != Any::class.java || !Modifier.isPublic(method.modifiers)) continue
            if (Modifier.isStatic(method.modifiers) || Modifier.isAbstract(method.modifiers)) continue
            if (method.parameterCount > 1) continue
            if (method.parameterCount == 1 && method.parameterTypes[0] != Context::class.java) continue
            XposedBridge.hookMethod(method, hook)
            getters++
        }
        if (getters == 0) log("flags: ${holder.name} has no getters")
        // The typed handles (boolean, long, double) read through their own m() straight into ez(), which the two
        // getters above never see. ez() is where every read ends up, so overriding its result covers both.
        val resolver = holder.declaredMethods.firstOrNull {
            it.returnType == Any::class.java && it.parameterCount == 2 &&
                it.parameterTypes[1] == Context::class.java && !Modifier.isStatic(it.modifiers)
        }
        resolver?.let { XposedBridge.hookMethod(it, hook) }
        // The typed handles cache their value in a volatile field and their l(Context) getter returns that
        // cache without calling ez(). A read before the hook was installed, or a later Phenotype refresh,
        // would keep the stock value, so override those getters too. They are found by signature, not name:
        // one Context argument and a boolean, long, double or string result, on a flag-handle subclass.
        var root: Class<*> = holder
        while (root.superclass != null && root.superclass != Any::class.java) root = root.superclass!!
        val typed = listOf<Class<*>>(
            Boolean::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            Double::class.javaPrimitiveType!!,
            String::class.java,
        ).flatMap { type ->
            dex.methods("flags.typed.${type.simpleName}") {
                returnType(type)
                paramTypes(Context::class.java)
            }
        }.filter { root.isAssignableFrom(it.declaringClass) }
        typed.forEach { XposedBridge.hookMethod(it, hook) }
        log("overriding ${overrides.size + allowAll.size} flags through $getters getters, " +
            (resolver?.let { "${it.name} " } ?: "") + "and ${typed.size} typed getters")
        Status.installed("flags", "${overrides.size + allowAll.size} flags overridden")
    }

    // The package list flag's value is Android Auto's own proto for it, so an allow-all answer is built from
    // the type of the value being replaced: the two int fields are the proto's has-bits and its mode (b and
    // c in 18.2), and the consumer checks the mode against the allow-all value. Reading the type off the
    // value keeps the class name out of it, which a rename changes (acrw is a different class in 18.2.664604)
    private var allowAllProto: Any? = null
    private fun allowAllFilter(value: Any?): Any? {
        allowAllProto?.let { return it }
        val cls = value?.javaClass ?: return null
        val ints = cls.declaredFields.filter { it.type == Int::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers) }
        if (ints.size < 2) {
            log("flags: ${cls.name} is not the package filter")
            return null
        }
        return try {
            XposedHelpers.newInstance(cls).also { filter ->
                XposedHelpers.setIntField(filter, ints[0].name, 1)
                XposedHelpers.setIntField(filter, ints[1].name, FILTER_ALLOW_ALL)
                allowAllProto = filter
            }
        } catch (t: Throwable) {
            log("flags: no allow-all filter: $t")
            null
        }
    }

    // The flag name is the handle's String field that reads Container__flag (b in 18.2); the other one is
    // the Phenotype package the flag comes from. The field keeps its own name across builds, so it is
    // resolved once, from the first handle whose name is one of the flags being overridden. The Field
    // itself is kept, not its name: the typed handles (xhd, xgx) shadow it with fields of other types, so
    // looking it up by name from the handle would find those instead
    private var flagNameField: Field? = null
    private var flagNames: Set<String> = emptySet()
    private fun flagName(flag: Any): String? {
        val field = flagNameField ?: run {
            val fields = ArrayList<Pair<Field, String>>()
            var at: Class<*>? = flag.javaClass
            while (at != null && at != Any::class.java) {
                for (f in at.declaredFields) {
                    if (f.type != String::class.java || Modifier.isStatic(f.modifiers)) continue
                    val value = try {
                        f.isAccessible = true
                        f.get(flag) as? String
                    } catch (t: Throwable) {
                        null
                    } ?: continue
                    fields.add(f to value)
                }
                at = at.superclass
            }
            (fields.firstOrNull { it.second in flagNames } ?: fields.firstOrNull { it.second.contains("__") })
                ?.first?.also {
                    flagNameField = it
                    logOnce("flags: reading names from ${it.declaringClass.simpleName}.${it.name}")
                }
        } ?: return null
        return try {
            field.get(flag) as? String
        } catch (t: Throwable) {
            null
        }
    }

    private fun flagOverrides(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        if (Prefs.on(prefs, Prefs.NO_TELEMETRY)) map.putAll(NO_TELEMETRY_FLAGS)
        if (Prefs.on(prefs, Prefs.LIGHTER)) map.putAll(LIGHTER_FLAGS)
        // Always parked also moves Android Auto's own speed test, so a speed that reaches a path the sensor and the
        // fusion hook don't cover still counts as parked (the speed hack from AndroidAutoX and AA Tweaker)
        if (Prefs.on(prefs, Prefs.NO_LOCKOUTS)) map.putAll(LOCKOUT_FLAGS)
        // Heads-up notifications stay Android Auto's 8 seconds unless the user picked longer
        val notificationTimeout = prefs.getInt(Prefs.NOTIFICATION_TIMEOUT, Prefs.NOTIFICATION_TIMEOUT_DEFAULT)
        if (notificationTimeout != Prefs.NOTIFICATION_TIMEOUT_DEFAULT) {
            map["SystemUi__hun_default_heads_up_timeout_ms"] = notificationTimeout.toString()
            map["SystemUi__media_hun_in_rail_widget_timeout_ms"] = notificationTimeout.toString()
        }
        // Google's hidden "Phone Apps" launcher tab (codename Cradle), which runs phone apps natively on the car
        // screen. Its package filter is replaced in hookFlags, since the default only lets through a few games
        if (Prefs.on(prefs, Prefs.ANY_APP_LAUNCHER)) map["CradleFeature__all_app_launcher_enabled"] = "true"
        // Showing only the open apps needs the canonical layout, where an app on its own fills the screen and the
        // dashboard (map + cards) only shows on demand. Wide screens otherwise get the widescreen layout, whose app area
        // always stops at the dashboard column. Android Auto picks widescreen from this width (1240dp by default) up.
        if (onlyOpenApps()) map["SystemUi__widescreen_breakpoint_dp"] = "1000000"
        // The user's own overrides come last so they win over the presets
        prefs.getString(Prefs.FLAGS, "").orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
            .forEach { map[it.substringBefore('=').trim()] = it.substringAfter('=').trim() }
        // Google's own switch for running without Maps and the Google app
        if (Prefs.on(prefs, Prefs.NO_FORCED_APPS)) map["SystemUi__allow_missing_core_apps"] = "true"
        if (Prefs.on(prefs, Prefs.CUSTOM_TILE)) {
            map["UxPrototype__enabled"] = "true"
            prefs.getString(Prefs.CUSTOM_TILE_URL, "").orEmpty().takeIf { it.isNotBlank() }
                ?.let { map["UxPrototype__url"] = it }
        }
        return map
    }

    private fun convert(original: Any?, value: String): Any? = when (original) {
        is Boolean -> value.toBooleanStrictOrNull()
        is Long -> value.toLongOrNull()
        is Double -> value.toDoubleOrNull()
        is String -> value
        else -> null
    }

    // Two separate checks must all say "not driving" to keep typing and lists unlocked: the car's driving status
    // sensor (a bitmask, 0 = unrestricted) and the parked/moving guess from real speed and gear. Each is installed
    // on its own: the parcel hook is the one that survives renames, so the name-based hooks must not be able to stop
    // it from being installed
    private fun hookDrivingStatus() {
        safely("driving status: parcels") { hookDrivingStatusParcels() }
        safely("driving status: sensor") { hookDrivingStatusSensor() }
        safely("driving status: parked fusion") { hookParkedFusion() }
    }

    // The car reports driving status as sensor 11, a bitmask where 0 means unrestricted.
    // It enters the :car process through jix (the sensor manager builds each event) and leaves it through jiu/rwc
    // (the client binders), and is rebuilt from its proto in :projection through rwf (upstream, 664604), while jik
    // serves the client calls. Zeroing the status byte on every path unlocks every process; each hook stands alone
    // because the classes live in different processes.
    private fun hookDrivingStatusSensor() {
        val sensorEvent = XposedHelpers.findClass("com.google.android.gms.car.CarSensorEvent", cl)
        fun zeroStatus(event: Any?) {
            val bytes = XposedHelpers.getObjectField(event ?: return, "e") as? ByteArray ?: return
            if (bytes.isNotEmpty()) bytes[0] = 0
        }
        val sent = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val event = param.args[0] ?: return
                if (XposedHelpers.getIntField(event, "b") == SENSOR_DRIVING_STATUS) zeroStatus(event)
            }
        }
        // Events handed to a registered client, the last point before they are marshalled to it (jiu.a and rwc.a
        // in 664604; the two classes log "sensor update while client is already released")
        var senders = 0
        for (name in arrayOf("jiu", "rwc")) {
            val sender = XposedHelpers.findClassIfExists(name, cl)
                ?.let { runCatching { XposedHelpers.findMethodExact(it, "a", sensorEvent) }.getOrNull() } ?: continue
            XposedBridge.hookMethod(sender, sent)
            senders++
        }
        if (senders == 0) {
            val found = dex.methods("sensor.senders") {
                declaredClass { usingEqStrings("sensor update while client is already released") }
                paramTypes("com.google.android.gms.car.CarSensorEvent")
                returnType("void")
            }
            if (found.isEmpty()) error("no driving status client senders")
            for (method in found) XposedBridge.hookMethod(method, sent)
            log("driving status: ${found.size} client senders by dex")
        } else {
            log("driving status: $senders client senders by name")
        }
        // Events built from a sensor type: jix builds them in :car and rwf rebuilds them from the car's protos in
        // :projection (664604). A query over every int -> CarSensorEvent method also catches a build that renames
        // one of them
        val built = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.args[0] == SENSOR_DRIVING_STATUS) zeroStatus(param.result)
            }
        }
        var builders = 0
        for ((name, methods) in arrayOf("jix" to arrayOf("h", "i"), "rwf" to arrayOf("d", "h"))) {
            val builder = XposedHelpers.findClassIfExists(name, cl) ?: continue
            for (method in methods) {
                val found = runCatching { XposedHelpers.findMethodExact(builder, method, Int::class.javaPrimitiveType) }.getOrNull() ?: continue
                if (found.returnType != sensorEvent) continue
                XposedBridge.hookMethod(found, built)
                builders++
            }
        }
        if (builders == 0) {
            val found = dex.methods("sensor.builders") {
                paramTypes("int")
                returnType("com.google.android.gms.car.CarSensorEvent")
            }
            if (found.isEmpty()) error("no driving status event builders")
            for (method in found) XposedBridge.hookMethod(method, built)
            log("driving status: ${found.size} event builders by dex")
        } else {
            log("driving status: $builders event builders by name")
        }
    }

    // Every event that leaves a process is written to a Parcel first, and CarSensorEvent is a kept GMS
    // class, so its writeToParcel survives the renames that break the three hooks above. The byte is
    // zeroed for the write and put back afterwards, so Android Auto's own copy stays as it was.
    private val savedStatus = ThreadLocal<Int>()
    private fun hookDrivingStatusParcels() {
        val event = XposedHelpers.findClass("com.google.android.gms.car.CarSensorEvent", cl)
        // The public int field is the sensor type, the public byte[] the payload; the other int is the
        // parcel format version, which stays private
        val ints = event.declaredFields.filter { it.type == Int::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers) }
        val type = ints.firstOrNull { Modifier.isPublic(it.modifiers) } ?: ints.getOrNull(1) ?: error("no sensor type field")
        val payloads = event.declaredFields.filter { it.type == ByteArray::class.java && !Modifier.isStatic(it.modifiers) }
        val payloadField = payloads.firstOrNull { Modifier.isPublic(it.modifiers) } ?: payloads.singleOrNull() ?: error("no sensor payload field")
        // The fields are picked by access flag rather than name, so they may be private
        type.isAccessible = true
        payloadField.isAccessible = true
        XposedBridge.hookMethod(event.getDeclaredMethod("writeToParcel", Parcel::class.java, Int::class.javaPrimitiveType), object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (type.getInt(param.thisObject) != SENSOR_DRIVING_STATUS) return
                val payload = payloadField.get(param.thisObject) as? ByteArray ?: return
                if (payload.isEmpty()) return
                savedStatus.set(payload[0].toInt() and 0xFF)
                payload[0] = 0
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val saved: Int? = savedStatus.get()
                if (saved == null) return
                savedStatus.remove()
                (payloadField.get(param.thisObject) as? ByteArray)?.let { if (it.isNotEmpty()) it[0] = saved.toByte() }
            }
        })
        log("driving status: sensor events zeroed on the way out")
    }

    // The fused parking LiveData (mkh in 18.2, mkx in 664604) keeps three public fields: h is the parking state
    // guessed from speed, i whether the keyboard is enabled, j the gear. a() recomputes "parked" from all three,
    // so forcing them right before every recompute keeps it parked. The state is an enum and its first constant
    // is the moving one (mlb.a in 664604); every other constant means parked.
    private fun hookParkedFusion() {
        val fusion = dex.clazz("parked", "mkx", accept = { c ->
            c.declaredFields.any { !Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && it.type == Boolean::class.javaPrimitiveType } &&
                c.declaredFields.any { !Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && it.type == Int::class.javaPrimitiveType } &&
                c.declaredMethods.any { it.name == "a" && it.parameterCount == 0 }
        }) {
            usingEqStrings(FUSED_PARKING_LOG)
        } ?: error("no parked fusion class")
        val recompute = fusion.declaredMethods.first { it.name == "a" && it.parameterCount == 0 && it.returnType == Void.TYPE }
        // h is the only public field that is neither the enabled boolean nor the gear int
        val state = fusion.declaredFields.first { !Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && !it.type.isPrimitive }
        val enabled = fusion.declaredFields.first { !Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && it.type == Boolean::class.javaPrimitiveType }
        val gear = fusion.declaredFields.first { !Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
        val constants = state.type.enumConstants ?: error("${state.type.name} is not an enum")
        val parked = constants.getOrNull(1) ?: error("${state.type.name} has one constant")
        log("driving status: parked fusion ${fusion.name}.${recompute.name}, state ${state.type.name}[1] = $parked")
        XposedBridge.hookMethod(recompute, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                state.set(param.thisObject, parked)
                enabled.setBoolean(param.thisObject, true)
                gear.setInt(param.thisObject, 101) // EnergyProfile.EVCONNECTOR_TYPE_OTHER, a valid "gear" value it treats as parked
            }
        })
    }

    // Setup refuses to project until the Google app, Maps and Speech Services are installed and up to date.
    // tiy checks one required package: 1 means OK (a is the package; the gearhead self-check subclasses it and
    // overrides a, so it is left alone). Without the Google app Android Auto also hides every messaging app: ksf.b
    // (installed-and-enabled check for the Google app, asked by the messaging gates) forces them kept. Maps can't be
    // hidden in the launcher customisation like other apps; that check is still unmapped.
    private fun hookForcedApps() {
        val ok = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (XposedHelpers.getObjectField(param.thisObject, "a") != GEARHEAD) param.result = 1
            }
        }
        XposedHelpers.findAndHookMethod("tiy", cl, "a", Context::class.java, ok)
        // Without the Google app Android Auto hides every messaging app; keep them
        XposedHelpers.findAndHookMethod("ksf", cl, "b", Context::class.java, XC_MethodReplacement.returnConstant(true))
        log("forced apps: tiy.a always ok, ksf.b always installed")
    }

    // jxa.v(pkg, category, checkUses, _) is where every car app check ends up (the verdict behind jxa.k/l's denied-
    // packages cache, via jwz in 664604): a blocklist, the manifest check (a <uses> for the category in the app's car
    // XML, which jws.a finds and jws.c applies), then Play Store and signature checks. Keep only the manifest check,
    // so sideloaded apps work and undeclared ones stay out. jxa.r is the manifest verifier and rtb.j the category the
    // hidden phone-app launcher uses. (17.6: iyh.z with iyh.A/iyh.M; the category enum is rtb, ten constants like
    // 18.2's rry, whose NATIVE_APP was j.)
    private fun hookAppAllowlist() {
        val validator = XposedHelpers.findClass("jxa", cl)
        val rtb = XposedHelpers.findClass("rtb", cl)
        val manifest = XposedHelpers.findClass("jws", cl)
        val carXml = XposedHelpers.findMethodExact(manifest, "a", String::class.java)
        val declaresUses = XposedHelpers.findMethodExact(
            manifest, "c",
            android.content.res.Resources::class.java, Integer::class.java, String::class.java, rtb,
        )
        val nativeApp = runCatching { XposedHelpers.getStaticObjectField(rtb, "j") }.getOrNull()
            ?: (rtb.enumConstants ?: error("rtb is not an enum")).last()
        val hook = object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam): Any {
                val pkg = param.args[0] as String
                if (param.args[2] != true || param.args[1] === nativeApp) return true
                val instance = XposedHelpers.getObjectField(param.thisObject, "r")
                return try {
                    val desc = carXml.invoke(instance, pkg) as Int
                    val res = if (desc == 0) null
                    else AndroidAppHelper.currentApplication().packageManager.getResourcesForApplication(pkg)
                    declaresUses.invoke(instance, res, desc.takeIf { it != 0 }, pkg, param.args[1]) as Boolean
                } catch (_: Throwable) {
                    false
                }
            }
        }
        // v needs the fourth parameter's type, which is only a logging callback; hooking by name keeps it out of
        // the signature and picks up the only four-argument v
        var hooked = 0
        for (method in validator.declaredMethods) {
            if (method.name != "v" || method.parameterCount != 4) continue
            if (method.parameterTypes[2] != Boolean::class.javaPrimitiveType) continue
            XposedBridge.hookMethod(method, hook)
            hooked++
        }
        if (hooked == 0) error("no app allowlist method on ${validator.name}")
        log("app allowlist: $hooked checks on ${validator.name}.v")
    }

    // With the microG switch Android Auto gets everything it would from Play Services from an installed microG app and
    // never reaches Play Services itself. It only gets there through framework calls, all hooked here: service binds,
    // content providers, code loaded with createPackageContext. What microG lacks is refused, as without Play Services.
    private fun hookMicroG() {
        val binds = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
                val context = param.thisObject as Context
                val target = microGPackage(context) ?: return
                if (!redirectGmsIntent(intent, target, context)) {
                    param.result = if (param.method.name == "bindService") false else null
                }
            }
        }
        val code = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args[0] != GMS_PACKAGE) return
                val target = microGPackage(param.thisObject as Context) ?: return
                param.args[0] = target
                logOnce("microG: Play Services code -> $target")
            }
        }
        // Calls go through ContextWrapper (Activity/Service/Application) down to ContextImpl; hooking both is
        // idempotent because a rewritten call no longer names Play Services, so the second hook is a no-op.
        for (klass in listOf("android.content.ContextWrapper", "android.app.ContextImpl")) {
            val c = XposedHelpers.findClass(klass, cl)
            XposedBridge.hookAllMethods(c, "bindService", binds)
            XposedBridge.hookAllMethods(c, "startService", binds)
            XposedBridge.hookAllMethods(c, "startForegroundService", binds)
            XposedBridge.hookAllMethods(c, "createPackageContext", code)
            XposedBridge.hookAllMethods(c, "createPackageContextAsUser", code)
        }

        // Every Play Services and Services Framework authority becomes microG's (com.google.android.gms.phenotype ->
        // app.revanced.android.gms.phenotype). Providers check a Uri's authority, so Uris are rewritten too, in every
        // method that takes one. An authority microG doesn't declare then doesn't exist, as without Play Services.
        val providers = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val resolver = param.thisObject as? ContentResolver
                    ?: XposedHelpers.getObjectField(param.thisObject, "mContentResolver") as ContentResolver
                val context = XposedHelpers.getObjectField(resolver, "mContext") as Context
                val target = microGPackage(context) ?: return
                val args = param.args
                for (i in args.indices) {
                    val uri = args[i] as? Uri ?: continue
                    val moved = microGAuthority(uri.authority ?: continue, target, context) ?: continue
                    args[i] = uri.buildUpon().authority(moved).build()
                }
                if (namesAuthority(param.method as Method)) {
                    microGAuthority(args[0] as String, target, context)?.let { args[0] = it }
                }
            }
        }
        for (c in listOf(ContentResolver::class.java, ContentProviderClient::class.java)) {
            c.declaredMethods
                .filter { !Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) }
                .filter { m -> m.parameterTypes.any { it == Uri::class.java } || namesAuthority(m) }
                .forEach { XposedBridge.hookMethod(it, providers) }
        }

        // sli.b (17.6) was isGooglePlayServicesAvailable; its 18.2 name is unmapped, so clients may see "missing"
        // from Play Services and never ask microG. Pending remap.

        // DynamiteModule.h loads Play Services modules only when com.google.android.gms.chimera belongs to
        // com.google.android.gms, which turns away a renamed microG and makes the car service refuse Android Auto's car
        // screens (CAR.AUTH). Accept microG on the V1 path (f); its loader comes from the createPackageContext hook.
        XposedHelpers.findAndHookMethod("com.google.android.gms.dynamite.DynamiteModule", cl, "h", Context::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (microGPackage(param.args[0] as Context) == null) return
                XposedHelpers.setStaticBooleanField(param.method.declaringClass, "f", true)
                param.result = true
            }
        })

        // Android Auto lets only Google-signed apps such as Maps use its privileged car APIs. The check is a
        // Context+uid method that asks the allowlist (tnx.a(context).b(pkg) in 18.2; toy.a(context).b(pkg) in
        // 664604). microG lacks some keys Google signs its car apps with, so Android Auto refuses Maps ("Wrong
        // signature - go/gearhead-retail-device", jic) and Maps crashes. Accept those keys too. The class moved
        // from sdy to sfc in 664604; try both so an older 18.2 keeps working. (17.6: slj.f returned a result object.)
        val signatureCheck = listOf("sfc", "sdy").firstNotNullOfOrNull { name ->
            XposedHelpers.findClassIfExists(name, cl)?.let { c ->
                runCatching {
                    XposedHelpers.findMethodExact(c, "l", Context::class.java, Int::class.javaPrimitiveType)
                }.getOrNull()
            }
        } ?: error("no Google signature check")
        XposedBridge.hookMethod(signatureCheck, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.result != false) return
                val context = param.args[0] as Context
                if (microGPackage(context) == null) return
                val packages = context.packageManager.getPackagesForUid(param.args[1] as Int) ?: return
                if (packages.any { signedWithKeyMicroGMisses(context, it) }) {
                    param.result = true
                    logOnce("microG: uid ${param.args[1]} (${packages.firstOrNull()}) is Google-signed")
                }
            }
        })
    }

    private fun signedWithKeyMicroGMisses(context: Context, pkg: String): Boolean = try {
        val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
        info.signingInfo?.apkContentsSigners.orEmpty().any {
            sha1.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } in GOOGLE_KEYS_MICROG_MISSES
        }
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    // ContentResolver.call(authority, ...), ContentProviderClient.call(authority, ...) and acquire*(authority)
    private fun namesAuthority(m: Method): Boolean {
        val p = m.parameterTypes
        return p.firstOrNull() == String::class.java &&
            (m.name == "call" && p.size == 4 || m.name.startsWith("acquire") && p.size == 1)
    }

    // Play Services' authority under microG's vendor prefix, or null when it isn't one of Play Services'. Logs each
    // authority once with whether microG serves it.
    private fun microGAuthority(authority: String, target: String, context: Context): String? {
        if (GMS_AUTHORITIES.none { authority == it || authority.startsWith("$it.") }) return null
        val moved = target.removeSuffix(".android.gms") + authority.removePrefix("com.google")
        if (logged.add("provider $authority")) {
            val served = context.packageManager.resolveContentProvider(moved, 0) != null
            log("microG: provider $authority -> ${if (served) moved else "refused (microG has no equivalent)"}")
        }
        return moved
    }

    // Points an intent aimed at Play Services at microG's equivalent service, or returns false when microG has none so
    // the caller refuses it. Every Play Services call is logged once either way, so the log lists everything Android
    // Auto asks Play Services for.
    private fun redirectGmsIntent(intent: Intent, target: String, context: Context): Boolean {
        val comp = intent.component
        if ((comp?.packageName ?: intent.`package`) != GMS_PACKAGE) return true
        val name = comp?.className ?: intent.action
        val pm = context.packageManager
        if (comp != null) {
            // Explicit component (e.g. GetToken): keep the class, swap the package, only if microG declares that class.
            val candidate = ComponentName(target, comp.className)
            if (pm.resolveService(Intent().setComponent(candidate), 0) != null) {
                intent.component = candidate
                logOnce("microG: $name -> $target")
                return true
            }
        } else if (name != null) {
            // Implicit action + setPackage(gms). microG keeps some actions under com.google.android.gms and renames
            // others to its own vendor prefix (com.google -> app.revanced for MicroG-RE), so try the action as-is
            // first, then vendor-renamed, taking whichever microG actually resolves.
            val renamed = target.removeSuffix(".android.gms") + name.removePrefix("com.google")
            for (action in listOf(name, renamed).distinct()) {
                if (pm.resolveService(Intent(intent).setAction(action).setPackage(target), 0) == null) continue
                intent.action = action
                intent.setPackage(target)
                logOnce("microG: $name -> ${if (action == name) "" else "$action @ "}$target")
                return true
            }
        }
        logOnce("microG: refused $name (microG has no equivalent)")
        return false
    }

    // Clients that look their service up dynamically (spa, ServiceBindIntentUtils) get Play Services' chimera proxy
    // service back with the real action in the data, chimera-action:<action>, so the class name doesn't say which API
    // it is. Any client whose minimum Play Services version is 211700000 or newer binds this way.
    private fun chimeraAction(intent: Intent): String? =
        intent.data?.takeIf { it.scheme == CHIMERA_SCHEME }?.schemeSpecificPart

    // Phenotype and friends rebind many times per session; one line per distinct event keeps the log readable
    private val logged = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private fun logOnce(msg: String) {
        // Count every use, not just the first one the log keeps for readability
        when {
            msg.startsWith("flag:") -> Status.used("flags")
            msg.startsWith("microG:") -> Status.used("microG")
            msg.startsWith("telemetry:") -> Status.used("telemetry binds")
            msg.startsWith("phone screen:") -> Status.used("phone screen")
        }
        if (logged.add(msg)) log(msg)
    }

    // The telemetry flags quiet Android Auto's loggers, but it still hands logs to Play Services' Clearcut, usage
    // reporting and client telemetry services. A refused bind reads as "service not found" and the client libraries
    // drop the logs. Hooked before the microG switch, so these never get redirected.
    private fun hookTelemetryBinds() {
        val block = object : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
                val name = listOfNotNull(chimeraAction(intent), intent.component?.className, intent.action)
                    .firstOrNull { n -> TELEMETRY_SERVICES.any { n.endsWith(it) } } ?: return
                param.result = if (param.method.name == "bindService") false else null
                logOnce("telemetry: blocked $name")
            }
        }
        for (klass in listOf("android.content.ContextWrapper", "android.app.ContextImpl")) {
            val c = XposedHelpers.findClass(klass, cl)
            XposedBridge.hookAllMethods(c, "bindService", block)
            XposedBridge.hookAllMethods(c, "startService", block)
            XposedBridge.hookAllMethods(c, "startForegroundService", block)
        }
    }

    @Volatile private var microGChecked = false
    private var microG: String? = null

    // The installed microG app, found once per process by a class every microG fork keeps (GetToken), since its package
    // name is up to whoever builds it. Takes the hooked call's context because some calls come before Android Auto's
    // Application exists.
    private fun microGPackage(context: Context): String? {
        if (microGChecked) return microG
        synchronized(this) {
            if (!microGChecked) {
                microG = try {
                    @Suppress("DEPRECATION")
                    context.packageManager.getInstalledPackages(PackageManager.GET_SERVICES)
                        .firstOrNull { it.packageName != GMS_PACKAGE && it.services?.any { s -> s.name == GMS_GET_TOKEN } == true }
                        ?.packageName
                } catch (t: Throwable) {
                    log("microG: detection failed: $t")
                    null
                }
                microGChecked = true
                log(
                    when {
                        microG != null -> "microG: Android Auto uses $microG instead of Play Services"
                        isMicroG(context, GMS_PACKAGE) -> "microG: microG is installed as Play Services itself, nothing to route"
                        else -> "microG: no microG app installed, nothing changed"
                    }
                )
            }
        }
        return microG
    }

    private fun isMicroG(context: Context, pkg: String) = try {
        context.packageManager.getPackageInfo(pkg, PackageManager.GET_SERVICES).services?.any { it.name.startsWith("org.microg.") } == true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    // nrs.b builds each display's startup layout (CarActivityLayoutConfig) and asks the provider (nsy in 664604) of
    // every app and map region for its startup intent (b(rvh)): nta, which 18.2's nsb/nsc extended as nsa for the map
    // regions, answers with the last navigation app, ntd (the app region) with nothing, and an empty app region opens
    // the dashboard. Swap the two. msf decides music autoplay once per drive; msb.e is its "no autoplay: user opted out".
    private fun hookStartOnLauncher() {
        val region = XposedHelpers.findClass("rvh", cl)
        XposedHelpers.findAndHookMethod("nta", cl, "b", region, object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam) = Intent()
        })
        XposedHelpers.findAndHookMethod("ntd", cl, "b", region, object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam) = Intent().setComponent(ComponentName(GEARHEAD, APP_LAUNCHER))
        })
        val decision = XposedHelpers.findClass("msb", cl)
        val result = XposedHelpers.findClass("msc", cl)
        XposedHelpers.findAndHookMethod("msf", cl, "get", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val app = XposedHelpers.getObjectField(param.result ?: return, "b")
                param.result = XposedHelpers.newInstance(
                    result, arrayOf(decision, ComponentName::class.java), XposedHelpers.getStaticObjectField(decision, "e"), app,
                )
            }
        })
        log("start on launcher: nta/ntd swapped, autoplay off")
    }

    // Android Auto closes the current car connection when a USB accessory detaches, even for a charger:
    // ruc registers rua once and for all, and jsm/rvf are per-connection receivers only registered for USB
    // connections (jju.ar/rvg.i). The developer server's connections (the phone screen's) aren't USB, and
    // there's one car connection at a time, so all of these are left alone while that's the latest one.
    //
    // Two renames can't break this. The connection type is the second int of the method every car
    // connection is registered through, which also takes a kept GMS CarInfoInternal, so its descriptor
    // survives. And rua, jsm and rvf all extend one abstract base that turns onReceive into a(Intent)
    // (sku in 18.2), so hooking its onReceive, a framework method name, silences the broadcast for every
    // one of them at once.
    private fun keepPhoneScreenOnUsbChanges() {
        val connection = dex.method(
            "usb.connection", "jju", "ar",
            Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            "com.google.android.gms.carsetup.CarInfoInternal", "kab", Boolean::class.javaPrimitiveType,
        ) {
            paramTypes("long", "int", "com.google.android.gms.carsetup.CarInfoInternal", null, "boolean")
            returnType("void")
        } ?: error("no car connection method")
        XposedBridge.hookMethod(connection, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                connectionType = param.args[1] as Int
            }
        })
        val base = usbReceiverBase() ?: error("no USB detach receiver base")
        val ignoreUsbDetach = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val intent = param.args[1] as? Intent ?: return
                if (connectionType == DEVELOPER_SERVER_CONNECTION && intent.action == USB_ACCESSORY_DETACHED) param.result = null
            }
        }
        for (method in base) XposedBridge.hookMethod(method, ignoreUsbDetach)
        log("USB detach ignored by ${base.joinToString { it.declaringClass.name }}")
    }

    // The receivers for USB detach are the classes that handle its action; they all share one base,
    // which is the class that declares onReceive (sku in 18.2, tried first). A class that only mentions
    // the action (xjg in 18.2.664604, a generic receiver base) is not a receiver itself, so the search
    // starts above every match
    private fun usbReceiverBase(): List<Method>? {
        val named = XposedHelpers.findClassIfExists("sku", cl)?.takeIf { isUsbReceiverBase(it) }
        if (named != null) return listOf(named.getDeclaredMethod("onReceive", Context::class.java, Intent::class.java))
        val receivers = dex.classes("usb.receivers") { usingEqStrings(USB_ACCESSORY_DETACHED) }
        val bases = receivers.mapNotNull { receiver ->
            var at: Class<*>? = receiver.superclass
            while (at != null && !isUsbReceiverBase(at)) at = at.superclass
            at
        }.distinct()
        if (bases.isEmpty()) log("USB detach receivers have no shared base: $receivers")
        // Without a base, the receivers that handle the broadcast themselves are still hookable
        val targets = bases.ifEmpty { receivers.filter { c -> c.declaredMethods.any { it.name == "onReceive" } } }
        if (targets.size > 1) log("USB detach receivers share ${targets.size} bases: $targets")
        return targets.takeIf { it.isNotEmpty() }
            ?.mapNotNull { runCatching { it.getDeclaredMethod("onReceive", Context::class.java, Intent::class.java) }.getOrNull() }
    }

    private fun isUsbReceiverBase(c: Class<*>): Boolean =
        Modifier.isAbstract(c.modifiers) && c.superclass?.name == "android.content.BroadcastReceiver" &&
            c.declaredMethods.any { it.name == "onReceive" } &&
            c.declaredMethods.any { Modifier.isAbstract(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(Intent::class.java)) }

    @Volatile private var connectionType = 0

    // The phone screen's side inside Android Auto: link it to the head unit server (linkPhoneScreen), keep that server
    // (it listens on port 5277 of every network) on the phone itself, and accept exactly AA Next's certificate besides
    // the ones chaining to Google's Automotive Link root (the trust manager next to syi in its car SSLContext).
    private fun hookPhoneScreen() {
        XposedHelpers.findAndHookMethod(PHONE_SCREEN_SERVER, cl, "onStartCommand", Intent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if ((param.args[0] as? Intent)?.getBooleanExtra(PhoneScreen.EXTRA_LINK, false) != true) return
                val service = param.thisObject as Context
                Thread({ linkPhoneScreen(service) }, "AA Next phone screen link").start()
            }
        })
        XposedHelpers.findAndHookMethod(ServerSocket::class.java, "bind", SocketAddress::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if ((param.args[0] as? InetSocketAddress)?.port == PhoneScreen.PORT) {
                    param.args[0] = InetSocketAddress(InetAddress.getByName(PhoneScreen.HOST), PhoneScreen.PORT)
                }
            }
        })
        XposedHelpers.findAndHookMethod(SSLContext::class.java, "init", Array<KeyManager>::class.java, Array<TrustManager>::class.java, SecureRandom::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isCarKeyManager((param.args[0] as? Array<*>)?.firstOrNull())) return
                val google = (param.args[1] as? Array<*>)?.firstOrNull() as? X509TrustManager ?: return
                val context = AndroidAppHelper.currentApplication() ?: return
                val certificate = moduleContext(context).assets.open("headunit/cert.pem").use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
                }
                param.args[1] = arrayOf<TrustManager>(PhoneScreenTrust(google, certificate))
                logOnce("phone screen: accepting AA Next's certificate next to Google's")
            }
        })
    }

    // The key manager Android Auto builds for a car connection (syi in 18.2, szl in 664604): it presents the
    // head unit's client certificate and holds the trust manager that verifies the car's. Its name changes
    // with every build, so it is recognised by its shape: an X509KeyManager with exactly one X509TrustManager.
    private fun isCarKeyManager(manager: Any?): Boolean {
        val cls = manager?.javaClass ?: return false
        if (!X509KeyManager::class.java.isAssignableFrom(cls)) return false
        return cls.declaredFields.count { it.type == X509TrustManager::class.java } == 1
    }

    // Every frame rate the managers pick (kju per mode, kjv static) is applied through jkv.B, which configures the
    // encoder (jtj.w, the codec's frame rate) and every rendered surface with it. Capping it there caps them all:
    // every frame is drawn, H.264-encoded and encrypted on the phone, so 30 halves that work (and the bitrate) on
    // 60 fps cars. (17.6: ivf.a with xqe.)
    //
    // The applied rate also reaches the encoder's MediaFormat, whose setInteger is a framework method, so that is
    // capped as well: if jkv.B is renamed the encoder still never sees more than 30, and the log shows whether the
    // named entry point was found.
    private fun hookFrameRate() {
        XposedHelpers.findAndHookMethod(MediaFormat::class.java, "setInteger", String::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args[0] == "frame-rate" && (param.args[1] as Int) > FRAME_RATE_CAP) param.args[1] = FRAME_RATE_CAP
            }
        })
        // jkv logs setFrameRateRestriction(%s), and B is its only (int) method: the class's name can change, that
        // log line and the method's shape can't
        val apply = dex.method("frame.rate", "jkv", "B", Int::class.javaPrimitiveType) {
            declaredClass { usingEqStrings(FRAME_RATE_LOG) }
            paramTypes("int")
            returnType("void")
        }
        if (apply == null) {
            log("frame rate: MediaFormat capped at $FRAME_RATE_CAP, no setFrameRateRestriction hook")
            Status.installed("frame rate", "MediaFormat only")
            return
        }
        XposedBridge.hookMethod(apply, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args[0] as Int > FRAME_RATE_CAP) param.args[0] = FRAME_RATE_CAP
            }
        })
        log("frame rate: MediaFormat and setFrameRateRestriction capped at $FRAME_RATE_CAP")
        Status.installed("frame rate", "MediaFormat and setFrameRateRestriction")
    }

    // Android Auto's own developer setting "Video resolution" (car_video_resolution in its carservice prefs) limits
    // which of the car's video sizes it accepts. Reading the preference through the framework's
    // SharedPreferencesImpl.getString answers it with the user's choice, which is what changing the setting in
    // Android Auto's own developer screen does; Android Auto still checks the car offers that size before using it
    // (jto.D, whose i holds the car's yqf configs with the size code in c). RESOLUTION_SIZES holds the car's
    // protocol codes, which yqe.b maps to sizes (1 = 480p, 2 = 720p landscape, 6 = portrait). (17.6: ivn.B with xqd.)
    private fun hookResolution(resolution: String) {
        if (resolution !in RESOLUTION_SIZES) {
            log("resolution: $resolution is not a size Android Auto knows")
            Status.failed("resolution", IllegalArgumentException("$resolution is not a size Android Auto knows"))
            return
        }
        XposedHelpers.findAndHookMethod("android.app.SharedPreferencesImpl", cl, "getString", String::class.java, String::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args[0] == "car_video_resolution") param.result = resolution
            }
        })
        log("resolution: answering $resolution")
        Status.installed("resolution", resolution)
    }

    // Start on launcher and recents both show only the apps the user opened
    private fun onlyOpenApps() = Prefs.on(prefs, Prefs.START_ON_LAUNCHER) || Prefs.on(prefs, Prefs.RECENTS)

    // The navigation app on the car screen, the music app the user opened, and every app opened this drive with its
    // category, newest last. An app stays open after you switch away (music keeps playing) until it's closed. All of
    // this lives in :projection, where the dashboard and the recents screen run.
    @Volatile private var openNav: ComponentName? = null
    @Volatile private var navRegion: Any? = null
    @Volatile private var openMedia: ComponentName? = null
    private val openApps = java.util.Collections.synchronizedMap(LinkedHashMap<ComponentName, String>())

    // nsj.k(region, app, time) records the app now showing in a region, or null when it went away; nti.a() is the app's
    // component and nti.b its category (ntf: NAVIGATION, MEDIA, ...). ntv.c is what a native app's launcher item runs,
    // and Android Auto doesn't put those through nsj.k, so record the tap as well. (17.6: mpj.k with mqj.)
    private fun trackOpenApps() {
        XposedHelpers.findAndHookMethod("nsj", cl, "k", CAR_REGION_ID, "nti", Long::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val region = param.args[0]
                val app = param.args[1]
                val component = app?.let { XposedHelpers.callMethod(it, "a") as ComponentName }
                val category = app?.let { appCategory(it) ?: "APP" }
                if (component != null && category != null && component.className !in NOT_APPS) {
                    recordOpenApp(component, category)
                }
                when (category) {
                    "NAVIGATION" -> {
                        openNav = component
                        navRegion = region
                    }
                    "MEDIA" -> openMedia = component
                    else -> if (region == navRegion) {
                        openNav = null
                        navRegion = null
                    }
                }
            }
        })
        XposedHelpers.findAndHookMethod("ntv", cl, "c", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val app = XposedHelpers.getObjectField(param.thisObject, "c")
                val component = XposedHelpers.callMethod(app, "a") as? ComponentName ?: return
                recordOpenApp(component, appCategory(app) ?: "APP")
            }
        })
        log("open apps: nsj.k and native app launches record each app")
    }

    private fun recordOpenApp(component: ComponentName, category: String) {
        if (component.className in NOT_APPS) return
        openApps.remove(component)
        openApps[component] = category
        reconnectMusicApp(component.packageName)
        refreshRail()
    }

    // The category is an enum whose constants are renamed with the build, but its public int field h is the id that
    // Android Auto compares against: 1 navigation, 3 media (ntf.h in 664604, nsf.h in 18.2). Every other app is a
    // plain app, which recents shows the same way; it is not a category Android Auto treats specially.
    private fun appCategory(app: Any): String? = try {
        val category = XposedHelpers.getObjectField(app, "b") ?: return null
        when (XposedHelpers.getIntField(category, "h")) {
            1 -> "NAVIGATION"
            3 -> "MEDIA"
            else -> "APP"
        }
    } catch (_: Throwable) {
        null
    }

    // nxu (DashboardWindowControllerImpl) and nxk (its shade variant) show the dashboard (map and cards) and first
    // start the default navigation app if none runs. Show it only when navigation and music are both open; otherwise
    // the rail button switches between the launcher and the one open app. nsj.f() is the app now showing. The marked
    // start for the dashboard goes through nsk.i with EXTRA_FOR_DASHBOARD (and nsk.k, which takes the car activity
    // options rue, on other paths), which only opening a navigation app may do here. (17.6: mun/muc.p and mpk.k.)
    private fun dashboardShowsOnlyOpenApps() {
        XposedHelpers.findAndHookMethod("nsk", cl, "i", Intent::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if ((param.args[0] as Intent).getBooleanExtra(EXTRA_FOR_DASHBOARD, false)) param.result = null
            }
        })
        XposedHelpers.findAndHookMethod("nsk", cl, "k", Intent::class.java, XposedHelpers.findClass("rue", cl), object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if ((param.args[0] as Intent).getBooleanExtra(EXTRA_FOR_DASHBOARD, false)) param.result = null
            }
        })
        val dashboard = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val nav = openNav
                val media = openMedia
                if (nav != null && media != null) return
                param.result = null
                val launcher = ComponentName(GEARHEAD, APP_LAUNCHER)
                val showing = XposedHelpers.callMethod(XposedHelpers.callStaticMethod(XposedHelpers.findClass("nsj", cl), "b"), "f")
                    ?.let { XposedHelpers.callMethod(it, "a") }
                // Starting the open app again is what its launcher icon does: it comes to the front on its own
                startCarApp(if (showing == launcher) nav ?: media ?: launcher else launcher)
            }
        }
        for (controller in listOf("nxu", "nxk")) {
            XposedHelpers.findAndHookMethod(controller, cl, "p", Boolean::class.javaPrimitiveType, ANIMATION_PARAMS, dashboard)
        }
        log("open apps: dashboard shows only open apps")
    }

    // Android Auto's own way to start an app on the car screen (nsk.i, which picks its region). (17.6: mpk.)
    private fun startCarApp(component: ComponentName, intent: Intent = Intent()) {
        val starter = XposedHelpers.callStaticMethod(XposedHelpers.findClass("nsk", cl), "a")
        XposedHelpers.callMethod(starter, "i", intent.setComponent(component))
    }

    private fun hookRecents() {
        recentsButton()
        recentsScreen()
        closeReceiver()
        keepClosedMusicAppsClosed()
        railShowsOpenApps()
        log("recents: button, screen, close receiver and rail hooked")
    }

    // The rail fragment (onn, 17.6 njv) keeps the apps button (launcher_and_dashboard_icon), or next to phone apps the
    // exit button (native_app_exit_icon), alone in a FocusInterceptor at the rail's end with the mic stacked on it.
    // The recents button goes beside that interceptor, not inside it: the interceptor draws the button's focus ring,
    // requires its button as its only child, and throws on any other shape. A plain row takes over the interceptor's
    // slot and, for the apps button, its id, so the mic is still constrained to the pair and moves along with it.
    private fun recentsButton() {
        XposedHelpers.findAndHookMethod("onn", cl, "onViewCreated", View::class.java, Bundle::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val root = param.args[0] as View
                fun id(name: String) = root.resources.getIdentifier(name, "id", GEARHEAD)
                val rows = listOf("launcher_and_dashboard_icon", "native_app_exit_icon")
                    .mapNotNull { root.findViewById<View>(id(it))?.let { button -> addRecentsBeside(button) } }
                if (rows.isEmpty()) return
                // Arranged after the layout pass that reveals the rail's shape, never inside it
                root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    root.post {
                        val vertical = root.height > root.width
                        rows.forEach { it.arrange(vertical, root.width) }
                        if (vertical) fitShortcuts(root, ::id)
                    }
                }
            }
        })
    }

    // The rail's app shortcuts show the open apps, newest first, instead of Android Auto's picks (its navigation, music
    // and phone apps and a recent one). onn.m binds each slot; ona.m builds the item for any app, phone apps included,
    // whose tap starts it the usual way, and an empty item (dro.s) hides the slot. (17.6: njv.m, nkf.k, kfn.h.)
    @Volatile private var rail: Any? = null

    private fun railShowsOpenApps() {
        XposedHelpers.findAndHookMethod("onn", cl, "onViewCreated", View::class.java, Bundle::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                rail = param.thisObject
            }
        })
        XposedHelpers.findAndHookMethod("onn", cl, "m", RAIL_SLOT, "oqk", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.args[0] as View
                val slot = if (view.id == View.NO_ID) -1 else RAIL_SLOTS.indexOf(view.resources.getResourceEntryName(view.id))
                if (slot >= 0) railItem(slot)?.let { param.args[1] = it }
            }
        })
    }

    private fun railItem(slot: Int): Any? {
        val model = rail?.let { XposedHelpers.getObjectField(it, "c") } ?: return null
        val app = synchronized(openApps) { openApps.keys.toList() }.asReversed().getOrNull(slot)
            ?: return XposedHelpers.callStaticMethod(XposedHelpers.findClass("dro", cl), "s")
        val recent = runCatching {
            XposedHelpers.findClass("omw", cl).enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "RECENT" }
        }.getOrNull() ?: XposedHelpers.getStaticObjectField(XposedHelpers.findClass("omw", cl), "f") // RECENT
        return XposedHelpers.callMethod(model, "m", app, recent, false, false, false)
    }

    // Binds all four slots again (fitShortcuts makes room for them, rather than Android Auto's two to four by height)
    private fun refreshRail() {
        val fragment = rail ?: return
        val slots = listOf("H", "I", "J", "K").map { XposedHelpers.getObjectField(fragment, it) as View? }
        val first = slots.firstOrNull() ?: return
        first.post {
            safely("rail apps") {
                slots.forEachIndexed { slot, view ->
                    if (view != null) XposedHelpers.callStaticMethod(fragment.javaClass, "m", view, railItem(slot) ?: return@forEachIndexed)
                }
            }
        }
    }

    // The recents button and the button it sits next to: recents first reads top to bottom on a vertical rail. On a
    // horizontal one the button keeps the rail's end and recents goes on its far side: after it when the button is on
    // the left, before it on right-hand drive rails. Called outside layout passes, so moving the views is safe.
    private class RecentsRow(val row: LinearLayout, val recents: View, val native: View) {
        fun arrange(vertical: Boolean, railWidth: Int) {
            val nativeOnLeft = IntArray(2).also { native.getLocationInWindow(it) }[0] < railWidth / 2
            val recentsFirst = vertical || !nativeOnLeft
            val orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            if (row.orientation == orientation && (row.getChildAt(0) === recents) == recentsFirst) return
            row.orientation = orientation
            row.removeView(recents)
            row.addView(recents, if (recentsFirst) 0 else 1)
        }
    }

    // The shortcut column (dock_container) is centred on the whole rail, so the recents button's taller stack would
    // push shortcuts into the mic. Fit them between the status bar and the mic, scaled down as needed but not below a
    // rail button's icon size; any that still don't fit are collapsed.
    private val shortcutSizes = java.util.WeakHashMap<View, Int>()

    private fun fitShortcuts(root: View, id: (String) -> Int) {
        val dock = root.findViewById<View>(id("dock_container")) ?: return
        val column = root.findViewById<ViewGroup>(id("rail_hotseat_container")) ?: return
        fun top(view: View) = IntArray(2).also { view.getLocationInWindow(it) }[1]
        fun shown(name: String) = root.findViewById<View>(id(name))?.takeIf { it.visibility == View.VISIBLE && it.height > 0 }
        val above = listOfNotNull(shown("status_bar"), shown("etc_icon")).maxOfOrNull { top(it) + it.height } ?: 0
        val below = listOfNotNull(shown("ongoing_widget_container"), shown("assistant_icon_container")).minOfOrNull { top(it) } ?: return
        val shortcuts = (0 until column.childCount).map { column.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
        if (shortcuts.isEmpty()) return
        val full = shortcuts.maxOf { shortcutSizes.getOrPut(it) { it.layoutParams.height } }
        val smallest = root.resources.getDimensionPixelSize(root.resources.getIdentifier("coolwalk_rail_button_icon_size", "dimen", GEARHEAD))
        val space = below - above
        val size = (space / shortcuts.size).coerceIn(minOf(smallest, full), full)
        shortcuts.forEachIndexed { i, shortcut ->
            val height = if (i < space / size) size else 0
            if (shortcut.layoutParams.height != height) shortcut.layoutParams = shortcut.layoutParams.apply { this.height = height }
            shortcut.scaleX = size.toFloat() / full
            shortcut.scaleY = size.toFloat() / full
        }
        val centre = top(dock) - dock.translationY + dock.height / 2f
        val shift = (above + below) / 2f - centre
        if (Math.abs(dock.translationY - shift) > 1) dock.translationY = shift
    }

    // The button stays in the FocusInterceptor it came in: that view holds its focus ring, needs the button as its
    // only child, and is what the rail's focus navigation lands on. The recents button goes beside the interceptor in
    // a plain row taking over its slot and id, so the apps button keeps working and the mic keeps its constraint.
    private fun addRecentsBeside(next: View): RecentsRow? {
        val interceptor = next.parent as? ViewGroup ?: return null
        if (interceptor.childCount != 1) return null
        val host = interceptor.parent as? ViewGroup ?: return null
        val context = next.context
        val size = next.layoutParams.width
        val height = next.layoutParams.height
        val button = ImageView(context).apply {
            val module = moduleContext(context)
            setImageDrawable(module.getDrawable(R.drawable.ic_recents)?.mutate()?.apply { setTint(RAIL_ICON) })
            val padding = size * 11 / 40
            setPadding(padding, padding, padding, padding)
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
            contentDescription = module.getString(R.string.recents_title)
            setOnClickListener { startCarApp(ComponentName(GEARHEAD, RECENTS_HOST), Intent().putExtra(EXTRA_RECENTS, true)) }
        }
        val index = host.indexOfChild(interceptor)
        val params = interceptor.layoutParams
        val container = interceptor.id
        host.removeView(interceptor)
        interceptor.id = View.NO_ID
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            id = container
            addView(button, LinearLayout.LayoutParams(size, height))
            addView(interceptor, LinearLayout.LayoutParams(size, height))
        }
        host.addView(row, index, params)
        return RecentsRow(row, button, next)
    }

    // The recents screen borrows the climate control screen (HvacCarActivityService, car activity miu in 664604, which
    // only cars with climate controls use). rua.s reads a car activity's start intent (what the host launched it
    // with, held by its :car client ssg), rvk.e is its onNewIntent (the host re-delivers intents through ssg.J.e),
    // and rua.di finishes it. The view is set with pyn.x, the View overload; pyn.w takes a layout resource instead.
    // (17.6: lgu, qrd.q, qsk.e, qrd.ew.)
    private fun recentsScreen() {
        XposedHelpers.findAndHookMethod("miu", cl, "a", Bundle::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val screen = param.thisObject
                if ((XposedHelpers.callMethod(screen, "s") as? Intent)?.getBooleanExtra(EXTRA_RECENTS, false) == true) showRecents(screen)
            }
        })
        XposedHelpers.findAndHookMethod("rvk", cl, "e", Intent::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.thisObject.javaClass.name == "miu" && (param.args[0] as Intent).getBooleanExtra(EXTRA_RECENTS, false)) {
                    showRecents(param.thisObject)
                }
            }
        })
    }

    private fun showRecents(screen: Any) {
        val context = screen as Context
        val view = RecentsScreen(
            context, moduleContext(context), openApps.keys.toList(),
            onOpen = { leaveRecents(screen, it) },
            onClose = {
                closeApp(context, it)
                afterClosing(screen)
            },
            onCloseAll = {
                openApps.keys.toList().forEach { closeApp(context, it) }
                afterClosing(screen)
            },
        ).build()
        // A refusal here leaves Android Auto's climate placeholder on screen, so report it instead of only throwing
        safely("recents") { XposedHelpers.callMethod(screen, "x", view) }
    }

    // One app left: it gets the screen to itself. None left: the launcher. Otherwise the updated list.
    private fun afterClosing(screen: Any) {
        val left = openApps.keys.toList()
        when (left.size) {
            0 -> leaveRecents(screen, ComponentName(GEARHEAD, APP_LAUNCHER))
            1 -> leaveRecents(screen, left.first())
            else -> showRecents(screen)
        }
    }

    // Opens the app, then finishes the recents screen (rua.di, what ssg.a logs as finish) so the next time it opens fresh
    private fun leaveRecents(screen: Any, app: ComponentName) {
        startCarApp(app)
        XposedHelpers.callMethod(screen, "di")
    }

    // Asks :car to stop the app's car screen, then AA Next to stop the app itself (not Android Auto's own apps). Music
    // apps show through Android Auto's media screen and Car App Library apps through its template hosts (navigation
    // apps their own), so those are stopped for them; music is also paused.
    private fun closeApp(context: Context, app: ComponentName) {
        val category = openApps.remove(app)
        refreshRail()
        if (app == openNav) {
            openNav = null
            navRegion = null
        }
        val carScreen = when {
            category == "MEDIA" -> {
                if (app == openMedia) openMedia = null
                endMusicConnections(app.packageName)
                val audio = context.getSystemService(AudioManager::class.java)
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
                ComponentName(GEARHEAD, MEDIA_HOST)
            }
            app.packageName == GEARHEAD || isCarAppLibraryApp(context, app) ->
                ComponentName(GEARHEAD, if (category == "NAVIGATION") TEMPLATE_NAV_HOST else TEMPLATE_HOST)
            else -> app
        }
        context.sendBroadcast(Intent(ACTION_CLOSE).setPackage(GEARHEAD).putExtra(EXTRA_COMPONENT, carScreen))
        if (app.packageName != GEARHEAD) Thread {
            safely("stop app", track = false) { context.contentResolver.call(AndroidAutoBridge.URI, AndroidAutoBridge.STOP_APP, app.packageName, null) }
        }.start()
    }

    // Android Auto keeps a media connection (mpl, a LiveData, 17.6 lns) to the music app it shows and reconnects
    // when it dies (s, up to five attempts, each calling a), which would start a closed app again. A closed app's
    // connections are ended (b), shown disconnected (m with mpi.a(mql.f), what AA shows for a connection it ended
    // itself at the end of a car session) and kept from connecting (a) until it's opened again.
    private val musicConnections = java.util.Collections.synchronizedList(ArrayList<java.lang.ref.WeakReference<Any>>())
    private val closedMusicApps: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun keepClosedMusicAppsClosed() {
        XposedHelpers.findAndHookConstructor("mpl", cl, Context::class.java, ComponentName::class.java, Bundle::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                musicConnections += java.lang.ref.WeakReference(param.thisObject)
            }
        })
        XposedHelpers.findAndHookMethod("mpl", cl, "a", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (musicApp(param.thisObject) in closedMusicApps) param.result = null
            }
        })
        XposedHelpers.findAndHookMethod("nsk", cl, "i", Intent::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                (param.args[0] as Intent).component?.let { reconnectMusicApp(it.packageName) }
            }
        })
    }

    private fun musicApp(connection: Any) = (XposedHelpers.getObjectField(connection, "l") as ComponentName).packageName

    private fun musicConnectionsOf(app: String) = synchronized(musicConnections) {
        musicConnections.removeAll { it.get() == null }
        musicConnections.mapNotNull { it.get() }.filter { musicApp(it) == app }
    }

    // mpi.a builds the non-connected state (an mql status with no media or playback): 17.6 lnp.a with lor
    private fun connectionState(statusField: String) = XposedHelpers.callStaticMethod(XposedHelpers.findClass("mpi", cl), "a",
        XposedHelpers.getStaticObjectField(XposedHelpers.findClass("mql", cl), statusField))

    // DISCONNECTED_OUT_OF_CONNECTION_LIFECYCLE (mql.f, 17.6 lor.f)
    private fun endMusicConnections(app: String) {
        closedMusicApps += app
        for (connection in musicConnectionsOf(app)) {
            XposedHelpers.callMethod(connection, "b")
            XposedHelpers.callMethod(connection, "m", connectionState("f"))
        }
    }

    // CONNECTING (mql.a, 17.6 lor.a): mpi.a refuses CONNECTED only, but refuses isn't right here either, so the
    // state is built by hand. For the connections still watched (n, hasActiveObservers, 17.6 lns.n).
    private fun reconnectMusicApp(app: String) {
        if (!closedMusicApps.remove(app)) return
        val empty = XposedHelpers.callStaticMethod(XposedHelpers.findClass("j$.util.Optional", cl), "empty")
        val connecting = XposedHelpers.newInstance(XposedHelpers.findClass("mpi", cl),
            XposedHelpers.getStaticObjectField(XposedHelpers.findClass("mql", cl), "a"), empty, empty)
        for (connection in musicConnectionsOf(app)) {
            if (XposedHelpers.callMethod(connection, "n") != true) continue
            XposedHelpers.callMethod(connection, "m", connecting)
            XposedHelpers.callMethod(connection, "a")
        }
    }

    private fun isCarAppLibraryApp(context: Context, app: ComponentName) = context.packageManager
        .queryIntentServices(Intent("androidx.car.app.CarAppService").setPackage(app.packageName), 0)
        .any { it.serviceInfo.name == app.className }

    @Volatile private var carActivities: Any? = null

    // jme, the car activity manager of each car connection (17.6 ioh), lives in :car. The recents screen asks it to
    // close an app with a broadcast only Android Auto itself can send: a not-exported receiver, or before Android 13
    // Android Auto's own signature permission for exactly that.
    private fun closeReceiver() {
        XposedBridge.hookAllConstructors(XposedHelpers.findClass("jme", cl), object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val first = carActivities == null
                carActivities = param.thisObject
                if (!first) return
                val context = AndroidAppHelper.currentApplication() ?: return
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        @Suppress("DEPRECATION")
                        val app = intent.getParcelableExtra<ComponentName>(EXTRA_COMPONENT) ?: return
                        safely("close app", track = false) { finishCarScreens(carActivities ?: return@safely, app) }
                    }
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, IntentFilter(ACTION_CLOSE), Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(receiver, IntentFilter(ACTION_CLOSE), NOT_EXPORTED_PERMISSION, null)
                }
            }
        })
    }

    // What jme.av ("stopCarActivityManager") does, for hidden screens too: jme.u holds a screen set per display
    // (jlq), whose f is a multimap giving the screens (jlk) of a component. A started one (H) is stopped the way av
    // stops it (y with a null argument, an instance call in 664604); one an app in front of it covers isn't started,
    // so av skips it, and is finished instead (l, finishManager: out of its task and unbound).
    // (17.6: ioh.au with inu and static inn.y.)
    private fun finishCarScreens(carActivities: Any, app: ComponentName) = synchronized(carActivities) {
        val screen = XposedHelpers.findClass("jlk", cl)
        val stop = screen.declaredMethods.first { it.name == "y" && it.parameterCount == 1 && !it.parameterTypes[0].isPrimitive }
        for (display in (XposedHelpers.getObjectField(carActivities, "u") as Map<*, *>).values) {
            val screens = XposedHelpers.callMethod(XposedHelpers.getObjectField(display, "f"), "c", app) as Collection<*>
            for (manager in screens.toList()) {
                if (XposedHelpers.callMethod(manager, "H") as Boolean) stop.invoke(manager, null) else XposedHelpers.callMethod(manager, "l")
            }
        }
    }

    private var module: Context? = null

    // AA Next's own package, for its strings and icons inside Android Auto
    private fun moduleContext(context: Context) =
        module ?: context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY).also { module = it }

    // How the settings reach the hooks. XSharedPreferences reads the module's own file, which works only
    // where the framework injects the module into its own app and shares that file (Vector does). LSPosed
    // injects only the apps in a module's scope and never the module itself, so its shared file stays
    // empty; there the bridge answers, asking AA Next's own app, which is a plain app every framework can
    // serve. It's read once per process, like the file was: settings changes ask for an Android Auto
    // restart either way
    private fun loadPrefs(): SharedPreferences {
        val shared = XSharedPreferences(BuildConfig.APPLICATION_ID, Prefs.FILE)
        if (shared.file.canRead()) return shared
        val context = bridgeContext()
        return try {
            context?.contentResolver?.call(AndroidAutoBridge.URI, AndroidAutoBridge.PREFS, null, null)
                ?.let { Prefs.FromBridge(it) } ?: Prefs.empty
        } catch (t: Throwable) {
            log("bridge prefs failed: $t (${context?.opPackageName})")
            Prefs.empty
        }
    }

    // A context for the bridge, which the hooks may need before any application exists. The system context
    // is made early in every process, but it calls as "android", and the provider call is refused before it
    // arrives because "android" is not one of Android Auto's packages. A package context for Android Auto
    // inherits that attribution, so it is replaced with Android Auto's package and this process's uid
    private fun bridgeContext(): Context? {
        AndroidAppHelper.currentApplication()?.let { return it }
        return try {
            val thread = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.app.ActivityThread", cl), "currentActivityThread")
            val system = XposedHelpers.callMethod(thread, "getSystemContext") as Context
            val context = system.createPackageContext(GEARHEAD, Context.CONTEXT_IGNORE_SECURITY)
            try {
                // XposedHelpers.newInstance infers the parameter types from the arguments, and the
                // attribution tag is null, so the constructor is picked by its signature instead
                val constructor = XposedHelpers.findConstructorExact(
                    XposedHelpers.findClass("android.content.AttributionSource", cl),
                    Int::class.javaPrimitiveType, String::class.java, String::class.java,
                )
                XposedHelpers.setObjectField(
                    context, "mAttributionSource",
                    constructor.newInstance(android.os.Process.myUid(), GEARHEAD, null),
                )
            } catch (t: Throwable) {
                log("bridge prefs: could not name the caller: $t")
            }
            log("bridge prefs: calling as ${context.opPackageName}")
            context
        } catch (t: Throwable) {
            log("bridge prefs: no context: $t")
            null
        }
    }

    // Tells AA Next the module runs in Android Auto, and which version, for the welcome screen's check. Only from
    // :provider, the process that check starts, once its Application exists.
    private fun reportLoaded() = Thread {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var app = AndroidAppHelper.currentApplication()
        while (app == null && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
            app = AndroidAppHelper.currentApplication()
        }
        app?.let { safely("report", track = false) { it.contentResolver.call(AndroidAutoBridge.URI, AndroidAutoBridge.LOADED, BuildConfig.VERSION_NAME, null) } }
    }.start()

    // Sends the hook results to AA Next's status screen: once when they're installed, then again after a
    // start-up wait so the hooks that have already done something show their counters too.
    private fun reportStatus(processName: String) = Thread {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var app = AndroidAppHelper.currentApplication()
        while (app == null && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
            app = AndroidAppHelper.currentApplication()
        }
        val context = app ?: return@Thread
        val version = try {
            context.packageManager.getPackageInfo(GEARHEAD, 0).versionName.orEmpty()
        } catch (_: Throwable) {
            ""
        }
        fun send() = safely("status", track = false) {
            context.contentResolver.call(AndroidAutoBridge.URI, AndroidAutoBridge.STATUS, Status.report(processName, version), null)
        }
        send()
        Thread.sleep(20_000)
        send()
    }.apply { isDaemon = true }.start()

    // Takes the phone screen's end of its link from AA Next and pipes it to the head unit server. The server starts a
    // moment after it's asked to, so a refused connection is retried. Nothing else is: Android Auto parks every
    // connection it accepted with no timeout, so a second one would strand.
    private fun linkPhoneScreen(context: Context) {
        val link = try {
            context.contentResolver.openFileDescriptor(PhoneScreen.LINK, "rw") ?: return
        } catch (e: Exception) {
            log("phone screen link: $e")
            return
        }
        val deadline = SystemClock.elapsedRealtime() + PHONE_SCREEN_START_MS
        var socket: Socket
        while (true) {
            socket = Socket()
            try {
                socket.connect(InetSocketAddress(PhoneScreen.HOST, PhoneScreen.PORT), 2000)
                break
            } catch (e: ConnectException) {
                socket.close()
                if (SystemClock.elapsedRealtime() > deadline) {
                    log("phone screen: the head unit server didn't start")
                    link.close()
                    return
                }
                Thread.sleep(300)
            }
        }
        socket.tcpNoDelay = true
        // Either side ending ends both; a shutdown wakes the read still blocked on the link
        val end = {
            runCatching { socket.close() }
            runCatching { Os.shutdown(link.fileDescriptor, OsConstants.SHUT_RDWR) }
            runCatching { link.close() }
        }
        Thread {
            runCatching { FileInputStream(link.fileDescriptor).copyTo(socket.getOutputStream(), 1 shl 16) }
            end()
        }.start()
        runCatching { socket.getInputStream().copyTo(FileOutputStream(link.fileDescriptor), 1 shl 16) }
        end()
    }

    // Android Auto marks every phone app immersive (nsi: nti with isImmersiveApp), which runs it in the full-screen
    // IMMERSIVE area (launch rule nrw) and hides the rail until a swipe (nrm). Not immersive, a phone app runs in the
    // normal app area beside the rail, like any Android Auto app. The app entry's constructor takes
    // (appKey, category, parkedOnly, immersive, native), so clear immersive whenever native is set.
    private fun keepRailBesidePhoneApps() {
        val bool = Boolean::class.javaPrimitiveType
        val appInfo = XposedHelpers.findClass("nti", cl)
        val constructors = appInfo.declaredConstructors.filter {
            it.parameterCount == 5 && it.parameterTypes[2] == bool && it.parameterTypes[3] == bool && it.parameterTypes[4] == bool
        }
        if (constructors.isEmpty()) error("no projection app entry constructor on ${appInfo.name}")
        for (constructor in constructors) {
            XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[4] == true) param.args[3] = false // isNativeApp -> not isImmersiveApp
                }
            })
        }
        log("phone apps: ${constructors.size} entry constructors on ${appInfo.name}")
    }

    // The Cradle launcher has three tabs: Car Optimized Apps (nts.f, Android Auto's own car-app list),
    // Entertainment Apps (nts.h, Google's Play Games tab, empty without a Play Games library) and Phone Apps
    // (nts.g, every launcher app). The last one asks the phone screen for native focus when opened, which ends
    // projection, so it is given the Entertainment content id in the tab list instead. nts then builds that tab
    // from the grid hookPhoneAppsTab fills, and the car tab keeps only Android Auto's own apps.
    private fun hookLauncherTabs() {
        XposedHelpers.findAndHookConstructor(
            "com.android.car.libraries.apphost.internal.model.TabTemplateInternal", cl, XposedHelpers.findClass("gwf", cl),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val tabs = XposedHelpers.getObjectField(param.args[0], "d") as List<*>
                    val kept: List<Any?> = runCatching {
                        val tabInternal = XposedHelpers.findClass(
                            "com.android.car.libraries.apphost.internal.model.TabInternal", cl,
                        )
                        val constructor = XposedHelpers.findConstructorExact(
                            tabInternal,
                            XposedHelpers.findClass("androidx.car.app.model.CarText", cl),
                            XposedHelpers.findClass("androidx.car.app.model.CarIcon", cl),
                            String::class.java,
                        )
                        val list = ArrayList<Any?>(tabs.size)
                        for (tab in tabs) {
                            when (XposedHelpers.callMethod(tab, "getContentId")) {
                                // Google's games tab, empty without a Play Games library
                                "ENTERTAINMENT_APPS_ID" -> {}
                                // Keep the Phone Apps tab on the content path nts builds from h, not g
                                "ALL_PHONE_APPS_ID" -> list.add(constructor.newInstance(
                                    XposedHelpers.callMethod(tab, "getTitle"),
                                    XposedHelpers.callMethod(tab, "getIcon"),
                                    "ENTERTAINMENT_APPS_ID",
                                ))
                                else -> list.add(tab)
                            }
                        }
                        list
                    }.onFailure {
                        log("launcher tabs: could not keep the Phone Apps tab, dropping it: $it")
                    }.getOrNull() ?: tabs.filter {
                        XposedHelpers.callMethod(it, "getContentId") !in setOf("ENTERTAINMENT_APPS_ID", "ALL_PHONE_APPS_ID")
                    }
                    XposedHelpers.setObjectField(param.args[0], "d", kept)
                    logOnce("launcher tabs: " + tabs.joinToString { XposedHelpers.callMethod(it, "getContentId").toString() } +
                        " -> " + kept.joinToString { XposedHelpers.callMethod(it, "getContentId").toString() })
                }
            })
        // ntq.b picks the tabbed launcher (nts) only when a car reports a touchscreen. The phone's own head unit
        // reports none, so it gets the flat launcher (ntp). Both use the same app model (ntk/ntm), so swapping them
        // keeps the app list and adds the tab host that nts.a and the pinned-app ordering work on.
        XposedHelpers.findAndHookMethod("ntq", cl, "b", Intent::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val flat = param.result ?: return
                if (!XposedHelpers.findClass("ntp", cl).isInstance(flat)) return
                runCatching {
                    val context = XposedHelpers.getObjectField(flat, "a")
                    val model = XposedHelpers.newInstance(XposedHelpers.findClass("ntm", cl))
                    val tabbed = XposedHelpers.newInstance(XposedHelpers.findClass("nts", cl), context, model)
                    XposedHelpers.setObjectField(param.thisObject, "c", tabbed)
                    param.result = tabbed
                    log("launcher tabs: flat launcher replaced with the tabbed one")
                }.onFailure { log("launcher tabs: could not switch to the tabbed launcher: $it") }
            }
        })
        hookPhoneAppsTab()
        hookTabSelection()
        log("launcher tabs: Entertainment dropped, Phone Apps kept, car tab left alone")
    }

    // umz.a is the tab-selected callback. The Phone Apps tab is given the Entertainment content id in the template,
    // but the host can still report the id it cached for that tab; translate it back so nts builds the injected h
    // list instead of the native phone-apps request, which ends projection. (17.6: different lambda class.)
    private fun hookTabSelection() {
        runCatching {
            XposedHelpers.findAndHookMethod("umz", cl, "a", String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (XposedHelpers.getIntField(param.thisObject, "\$cid") != 1) return
                    if (param.args[0] == "ALL_PHONE_APPS_ID") param.args[0] = "ENTERTAINMENT_APPS_ID"
                }
            })
            log("launcher tabs: Phone Apps selections build the launcher grid")
        }.onFailure { log("launcher tabs: could not hook tab selection: $it") }
    }

    // Every launcher app fills the Phone Apps tab. nfl.d lists the launcher activities, ntv.b turns one into an icon
    // and label, and nuf adds the notification badge, the same item type Android Auto's own launcher uses. The tab
    // carries the Entertainment content id, so nts.h reads the h list after subscribing to the entertainment source;
    // that can clear it, so the launcher grid is replaced at nts.a(List), the one place it is built. Only the Phone
    // Apps tab is touched: the Car Optimized Apps tab keeps Android Auto's own car-optimized list.
    private fun hookPhoneAppsTab() {
        XposedHelpers.findAndHookMethod("nts", cl, "a", List::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val car = XposedHelpers.getObjectField(param.thisObject, "f")
                if (param.args[0] === car) return
                if (XposedHelpers.getObjectField(param.thisObject, "j") != "ENTERTAINMENT_APPS_ID") return
                val items = phoneGridItems() ?: return
                param.args[0] = items
                Status.used("all phone apps")
                logOnce("phone apps: ${items.size} apps on the Phone Apps tab")
            }
        })
        Status.installed("all phone apps", "phone apps tab")
        log("launcher tabs: Phone Apps tab shows every launcher app")
    }

    // nfl.d(rvh) returns Android Auto's own "all phone apps" list. ljo.b().e() is the connected car token it wants,
    // the same one the native Phone Apps tab reads. That query runs a Cradle allowlist over the launcher apps, so any
    // package it skips is added from PackageManager: "all apps" means all of them. Item construction is cached per
    // process; it costs one PackageManager lookup per app, so it must not run on every template build.
    @Volatile private var phoneGridItems: List<Any>? = null

    private fun phoneGridItems(): List<Any>? {
        phoneGridItems?.let { return it }
        val items = runCatching {
            val context = (runCatching {
                XposedHelpers.getObjectField(
                    XposedHelpers.getStaticObjectField(XposedHelpers.findClass("noa", cl), "a"), "c",
                )
            }.getOrNull() ?: AndroidAppHelper.currentApplication()) as? Context ?: return null
            val rvh = XposedHelpers.callMethod(XposedHelpers.callStaticMethod(XposedHelpers.findClass("ljo", cl), "b"), "e")
            val entries = XposedHelpers.callStaticMethod(XposedHelpers.findClass("nfl", cl), "d", rvh) as Collection<*>? ?: return null
            val nti = XposedHelpers.findClass("nti", cl)
            val ntv = XposedHelpers.findClass("ntv", cl)
            val nuf = XposedHelpers.findClass("nuf", cl)
            val iconClass = XposedHelpers.findClass("com.google.android.apps.auto.components.graphics.icon.GhIcon", cl)
            val native = entries.mapNotNull { entry ->
                val component = XposedHelpers.callMethod(entry, "a") as? ComponentName ?: return@mapNotNull null
                component to entry
            }.distinctBy { (component, _) -> component.packageName }
            val seen = native.mapTo(HashSet()) { (component, _) -> component.packageName }
            val nativeType = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("ntf", cl), "a")
            val extra = context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
            ).mapNotNull { info ->
                val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                if (component.packageName in seen) return@mapNotNull null
                runCatching { XposedHelpers.newInstance(nti, component, nativeType, true, true, true) }.getOrNull()
            }.distinctBy { entry -> (XposedHelpers.callMethod(entry, "a") as ComponentName).packageName }
            var fallbacks = 0
            val built = (native.map { (_, entry) -> entry } + extra).mapNotNull { entry ->
                val component = XposedHelpers.callMethod(entry, "a") as ComponentName
                val app = runCatching { XposedHelpers.callStaticMethod(ntv, "b", entry) }.getOrNull()
                    ?: runCatching {
                        // Some labels are SpannedStrings, which AA's own factory casts to String and crashes on
                        val icon = XposedHelpers.callStaticMethod(iconClass, "j", component)
                        val label = context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(component.packageName, 0),
                        ).toString()
                        XposedHelpers.newInstance(ntv, entry, icon, label, -1)
                    }.onFailure { fallbacks++ }.getOrNull()
                app?.let { runCatching { XposedHelpers.newInstance(nuf, it, false) }.getOrNull() }
            }
            log("phone apps: ${built.size} apps from ${native.size} native and ${extra.size} extra entries" +
                if (fallbacks == 0) "" else " ($fallbacks without a version name)")
            built
        }.onFailure {
            Status.failed("all phone apps", it)
            log("phone apps: could not build the phone apps grid: $it")
        }.getOrNull()
        if (items != null) {
            phoneGridItems = items
            Status.installed("all phone apps", "${items.size} apps")
        }
        return items
    }

    // Phone apps get their own density from the car's screen size and dpi, computed in the AppFunctions schema
    // factory ($AdjustNumericDeviceStateItemByPercentageParamsFactory.v in 664604; jadx writes the leading '$' as
    // "C$"; 18.2 inlined the same math in otu.A). Scale it there rather than replace it, so one setting suits every car.
    private fun hookPhoneAppDensity(percent: Int) {
        val factory = XposedHelpers.findClass(
            "com.android.appfunctions.schema.devicestate.v1.\$AdjustNumericDeviceStateItemByPercentageParamsFactory", cl,
        )
        val drawingSpec = XposedHelpers.findClass("com.google.android.gms.car.DrawingSpec", cl)
        val density = factory.declaredMethods.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.returnType == Int::class.javaPrimitiveType &&
                it.parameterCount == 2 && it.parameterTypes[0] == drawingSpec
        } ?: error("no phone app density method on ${factory.name}")
        XposedBridge.hookMethod(density, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = (param.result as Int) * percent / 100
            }
        })
        log("phone app size: ${density.name} scaled to $percent%")
        Status.installed("phone app size", "$percent%")
    }

    // nts.a builds every launcher tab's grid from its app list (nuf, whose a.c.a() is the component: nuf.a is the app
    // entry nue, nue.c the app nti, nti.a() its component). Pinned apps go first, in the order they were pinned;
    // sortedBy is stable, so the rest keep Android Auto's order. (17.6: mqs.a with mre.)
    private fun hookPinnedApps(pinned: List<String>) {
        XposedHelpers.findAndHookMethod("nts", cl, "a", List::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val apps = param.args[0] as? List<*> ?: return
                param.args[0] = apps.sortedBy {
                    val app = XposedHelpers.getObjectField(XposedHelpers.getObjectField(it, "a"), "c")
                    val pkg = (XposedHelpers.callMethod(app, "a") as ComponentName).packageName
                    pinned.indexOf(pkg).let { i -> if (i < 0) pinned.size else i }
                }
            }
        })
        log("pinned apps: nts.a orders ${pinned.size} pinned packages first")
        Status.installed("pinned apps", "${pinned.size} package${if (pinned.size == 1) "" else "s"} pinned")
    }

    // ntw.e builds the launcher's Google Assistant shortcut tiles; each is keyed by a fake component package.
    // Tiles are ntw (extends nue, whose c is the app entry, nti, whose a() is the component); zip.o copies the list.
    // (17.6: mqv.e with a ygw copy helper.)
    private fun hookAssistantTiles() {
        val hidden = setOf("NewsAssistantAction", "WeatherAssistantAction")
        val zip = XposedHelpers.findClass("zip", cl)
        XposedHelpers.findAndHookMethod(
            "ntw", cl, "e", XposedHelpers.findClass("rvh", cl), Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val tiles = (param.result as? List<*>) ?: return
                    param.result = XposedHelpers.callStaticMethod(zip, "o", tiles.filter {
                        val app = XposedHelpers.getObjectField(it, "c")
                        (XposedHelpers.callMethod(app, "a") as ComponentName).packageName !in hidden
                    })
                }
            })
        log("assistant tiles: News and Weather dropped")
    }

    // rmh is the app default manager (implementing mji); a(ntf) and b(ntf) are what everything asks for the default
    // per category. ntf.h is the category id: 1 navigation, 3 media. (17.6: qji with mqg.)
    private fun hookDefaultApps() {
        val chosen = mapOf(1 to Prefs.DEFAULT_NAV, 3 to Prefs.DEFAULT_MEDIA)
            .mapValues { ComponentName.unflattenFromString(prefs.getString(it.value, "").orEmpty()) }
            .filterValues { it != null }
        if (chosen.isEmpty()) {
            Status.off("default apps", "No default apps set")
            return
        }
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                chosen[XposedHelpers.getIntField(param.args[0], "h")]?.let { param.result = it }
            }
        }
        val ntf = XposedHelpers.findClass("ntf", cl)
        XposedHelpers.findAndHookMethod("rmh", cl, "a", ntf, hook)
        XposedHelpers.findAndHookMethod("rmh", cl, "b", ntf, hook)
        log("default apps: ${chosen.size} overrides on rmh")
    }

    // Every notification the car shows enters as a stream item: ncv (the NotificationListenerService side) converts
    // each one through an ndn converter (nqa for navigation, mzh subclasses for SMS/IM) into an nrf, and rnn (the
    // "GH.StreamItemManager") posts it into the centre's list through m. That's 17.6's nuz.j, and
    // it covers the centre and the heads-up stream. Calls and media are separate views in 18.2 that never post here,
    // so what reaches it is navigation (kept) plus third-party notifications and SMS/IM (muted). Messages can also
    // bypass the stream: mxp.e converts a conversation into the notification projected to the car screen, so they are
    // dropped there too. aacb is the type proto enum and its constants keep their names at runtime; 17.6's kept
    // PENDING_CALL/ONGOING_CALL/MEDIA/NAVIGATION/NAVIGATION_ALERT map to CALL/RECENT_CALL/MEDIA and the two nav ones.
    private fun hookNotifications() {
        val keep = setOf("CALL", "RECENT_CALL", "MEDIA", "NAV_NOTIFICATION_NORMAL", "NAV_NOTIFICATION_HERO")
        XposedHelpers.findAndHookMethod("rnn", cl, "m", "nrf", Boolean::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val item = param.args[0] ?: return
                val type = XposedHelpers.callMethod(item, "J") as? Enum<*> ?: return
                if (type.name !in keep) param.result = null
            }
        })
        XposedHelpers.findAndHookMethod("mxp", cl, "e", "mxh", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = null
            }
        })
        log("notifications: only calls and navigation reach the stream")
    }

    // Runs in system_server. A preinstalled Android Auto has Google's privileged allowlist and the car projection role,
    // but microG ROMs ship none, and a Play Store copy's projection dies at its car setup's blocked background start.
    // Every permission check ends in one of these two methods, so they answer for what a preinstalled copy gets.
    private fun hookSystemAccess(cl: ClassLoader) {
        val localServices = XposedHelpers.findClass("com.android.server.LocalServices", cl)
        val pmiClass = XposedHelpers.findClass("android.content.pm.PackageManagerInternal", cl)
        val packageForUid = XposedHelpers.findMethodExact(pmiClass, "getPackage", Int::class.javaPrimitiveType)
        fun isGearhead(uid: Int): Boolean {
            val pmi = XposedHelpers.callStaticMethod(localServices, "getService", pmiClass) ?: return false
            val pkg = packageForUid.invoke(pmi, uid) ?: return false
            return XposedHelpers.callMethod(pkg, "getPackageName") == GEARHEAD
        }
        val grant = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.result == PackageManager.PERMISSION_GRANTED) return
                if (param.args.none { it is String && it in SYSTEM_GRANTS }) return
                val uid = param.args[0] as? Int
                if (if (uid != null) isGearhead(uid) else GEARHEAD in param.args) {
                    param.result = PackageManager.PERMISSION_GRANTED
                }
            }
        }
        val pms = XposedHelpers.findClass("com.android.server.pm.permission.PermissionManagerService", cl)
        XposedBridge.hookAllMethods(pms, "checkPermission", grant)
        XposedBridge.hookAllMethods(pms, "checkUidPermission", grant)

        // The car connection registers the car with CompanionDeviceManager, which skips its confirmation dialog only
        // for a holder of the car projection role and then adds the app to that role. The role controller refuses
        // any app that isn't a system app, so make system_server see Android Auto as holder and the add succeed.
        val roleManager = XposedHelpers.findClass("android.app.role.RoleManager", cl)
        XposedBridge.hookAllMethods(roleManager, "getRoleHoldersAsUser", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val holders = param.result as? List<*> ?: return
                if (param.args[0] == PROJECTION_ROLE && GEARHEAD !in holders) param.result = holders + GEARHEAD
            }
        })
        XposedBridge.hookAllMethods(roleManager, "addRoleHolderAsUser", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args[0] != PROJECTION_ROLE || param.args[1] != GEARHEAD) return
                @Suppress("UNCHECKED_CAST")
                val callback = param.args[5] as Consumer<Boolean>
                (param.args[4] as Executor).execute { callback.accept(true) }
                param.result = null
            }
        })

        // The role also grants its runtime permissions (phone, SMS, contacts, mic, location...) by default, so Android
        // Auto never asks for them and runs half broken without them. Grant them the same way after boot and whenever
        // it's installed or updated, leaving alone any the user already allowed or denied.
        XposedBridge.hookAllMethods(
            XposedHelpers.findClass("com.android.server.am.ActivityManagerService", cl), "systemReady",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                    grantRolePermissions(context, Process.myUserHandle())
                    val installed = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
                        addDataScheme("package")
                        addDataSchemeSpecificPart(GEARHEAD, PatternMatcher.PATTERN_LITERAL)
                    }
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(c: Context, intent: Intent) = grantRolePermissions(
                            context, UserHandle.getUserHandleForUid(intent.getIntExtra(Intent.EXTRA_UID, 0))
                        )
                    }
                    XposedHelpers.callMethod(context, "registerReceiverForAllUsers", receiver, installed, null, null)
                }
            })
        log("granting Android Auto its preinstalled system access")
    }

    private fun grantRolePermissions(context: Context, user: UserHandle) {
        val pm = context.packageManager
        for (permission in ROLE_RUNTIME_GRANTS) {
            try {
                val flags = XposedHelpers.callMethod(pm, "getPermissionFlags", permission, GEARHEAD, user) as Int
                if (flags and USER_CHOICE_FLAGS != 0) continue
                XposedHelpers.callMethod(pm, "grantRuntimePermission", GEARHEAD, permission, user)
            } catch (_: Throwable) {
                // Not installed for this user, or a permission this version doesn't request
            }
        }
    }

    private fun safely(what: String, track: Boolean = true, block: () -> Unit) {
        try {
            block()
            if (track) Status.installed(what)
        } catch (t: Throwable) {
            if (track) Status.failed(what, t)
            log("$what hook failed: $t\n${Log.getStackTraceString(t)}")
        }
    }

    private fun log(msg: String) = XposedBridge.log("AANext: $msg")

    companion object {
        const val GEARHEAD = "com.google.android.projection.gearhead"
        const val PHONE_SCREEN_SERVER = "com.google.android.projection.gearhead.companion.DeveloperHeadUnitNetworkService"
        const val PHONE_SCREEN_START_MS = 15_000L
        const val DEVELOPER_SERVER_CONNECTION = 3
        const val USB_ACCESSORY_DETACHED = "android.hardware.usb.action.USB_ACCESSORY_DETACHED"
        // Logged by the class every Phenotype flag is read through (xfj in 18.2)
        const val PHENOTYPE_INVALID_FLAG = "Invalid Phenotype flag value for flag "
        // Logged by the class that applies the frame rate (jkv in 18.2), and the cap AA Next puts on it
        const val FRAME_RATE_LOG = "setFrameRateRestriction(%s)"
        const val FRAME_RATE_CAP = 30
        const val RAIL_SLOT = "com.google.android.apps.auto.components.system.facetbar.hotseat.RailHotseatItemView"
        val RAIL_SLOTS = listOf("hotseat_one", "hotseat_two", "hotseat_three", "hotseat_four")
        const val EXTRA_FOR_DASHBOARD = "com.google.android.apps.auto.components.system.dashboard.EXTRA_FOR_DASHBOARD"
        const val APP_LAUNCHER = "com.google.android.projection.gearhead.system.applauncher.GhAppLauncherService"
        const val CAR_REGION_ID = "com.google.android.gms.car.display.CarRegionId"
        const val RECENTS_HOST = "com.google.android.apps.auto.components.hvac.HvacCarActivityService"
        const val MEDIA_HOST = "com.google.android.projection.gearhead.media.MediaService"
        const val TEMPLATE_HOST = "com.google.android.projection.gearhead.apphost.TemplateService"
        const val TEMPLATE_NAV_HOST = "com.google.android.projection.gearhead.apphost.TemplateNavigationService"
        // Android Auto's own screens, which aren't apps the user opened
        val NOT_APPS = setOf(
            APP_LAUNCHER, RECENTS_HOST,
            "com.google.android.apps.auto.components.system.navigation.fallback.NavigationFallbackCarActivityService",
            "com.google.android.projection.gearhead.system.ProjectionTrampolineFallbackService",
        )
        const val EXTRA_RECENTS = "io.github.rushiranpise.aanext.RECENTS"
        const val ACTION_CLOSE = "io.github.rushiranpise.aanext.CLOSE_CAR_APP"
        const val EXTRA_COMPONENT = "component"
        const val NOT_EXPORTED_PERMISSION = "com.google.android.projection.gearhead.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        val RAIL_ICON = android.graphics.Color.rgb(0xE8, 0xEA, 0xED)
        const val ANIMATION_PARAMS = "com.google.android.gms.car.animation.ProjectionWindowAnimationParams"
        const val SYSTEM_FRAMEWORK = "android"
        const val PROJECTION_ROLE = "android.app.role.SYSTEM_AUTOMOTIVE_PROJECTION"
        const val GMS_PACKAGE = "com.google.android.gms"
        // Play Services' log upload services: Clearcut, usage reporting, the older PlayLog, and client telemetry
        // (IClientTelemetryService and its notification and throttling variants). Matched by suffix so the
        // vendor-renamed actions of a renamed microG match too.
        val TELEMETRY_SERVICES = listOf(
            ".clearcut.service.START", ".usagereporting.service.START", ".playlog.service.START",
            ".common.telemetry.service.START", ".common.telemetry.notification.service.START",
            ".common.telemetry.throttling.service.START",
        )
        const val CHIMERA_SCHEME = "chimera-action"
        // Content provider authorities of Play Services, its Services Framework, and the Services Framework's settings
        val GMS_AUTHORITIES = listOf("com.google.android.gms", "com.google.android.gsf", "com.google.settings")
        // SHA-1s of Google signing keys missing from microG's list: Google Maps' second key (APKMirror lists 3891... and
        // 58e1... as Maps signatures; microG has only the first)
        val GOOGLE_KEYS_MICROG_MISSES = setOf("58e1c4133f7441ec3d2c270270a14802da47ba0e")
        const val GMS_GET_TOKEN = "com.google.android.gms.auth.GetToken"
        const val SENSOR_DRIVING_STATUS = 11
        const val FUSED_PARKING_LOG = "Fused parking state changed to %b"
        const val PHONE_APPS_FILTER = "CradleFeature__app_launcher_package_list"
        const val FILTER_ALLOW_ALL = 4

        // Periodic timers get a one-day interval rather than 0, which their schedulers would reject.
        // The audio stats collector is only created when its period is above 0 (irk).
        private const val DAY_MS = "86400000"
        // The speeds below which Android Auto's own speed test calls the car parked (1.5 and 0.5 by default). Always
        // parked raises them so no speed is above them; the sensor parcel and the parked fusion hooks stay the main
        // path, this one covers the checks that read the raw speed instead
        val LOCKOUT_FLAGS = mapOf(
            "CarSensorParameters__max_parked_speed_gps_sensor" to "999.0",
            "CarSensorParameters__max_parked_speed_wheel_sensor" to "999.0",
        )
        // Lighter on the phone: Android Auto's own frame rate limits (FrameRateRestrictions), which it otherwise only
        // uses when the phone is severely hot (thermal status 3) or at 15% battery. Below 20 fps the map moves visibly.
        val LIGHTER_FLAGS = mapOf(
            "FrameRateRestrictions__fps_limit_for_power_normal_display_idle" to "20",
            "FrameRateRestrictions__min_thermal_status_for_frame_rate_restrictions" to "2",
            "FrameRateRestrictions__fps_limit_for_power_low_display_idle" to "15",
            "FrameRateRestrictions__fps_limit_for_power_low_display_interactive" to "30",
            "FrameRateRestrictions__use_power_save_mode_for_battery_level" to "true",
        )
        // The car video sizes (xqd codes) each resolution choice allows, as in Android Auto's own table (ivh)
        private val RESOLUTION_SIZES = mapOf("720p" to setOf(1, 2, 6), "480p" to setOf(1))

        val NO_TELEMETRY_FLAGS = mapOf(
            "Performance__log_to_telemetry" to "false",
            "Performance__reduce_car_activity_logging_enabled" to "true",
            "Telemetry__high_volume_events_sampling_rate" to "0.0",
            "ConnectivityLogging__heartbeat_interval_ms" to DAY_MS,
            "ProjectionWindowManager__composition_to_transport_direct_telemetry_percent" to "0.0",
            "AudioDiagnosticsFeature__audio_stats_logging_period_milliseconds" to "0",
            "VideoDiagnosticsFeature__video_stats_logging_period_ms" to DAY_MS,
            "CarAppLibrary__use_no_op_latency_logger" to "true",
            "ClientAnrs__log_client_anrs" to "false",
        )

        // Google's privapp-permissions allowlist for Android Auto plus the SYSTEM_AUTOMOTIVE_PROJECTION role's
        // signature/internal permissions (Android 15 Pixel image), limited to what Android Auto 17.6 requests.
        // Runtime permissions aren't here: the user grants those, as on any phone.
        val SYSTEM_GRANTS = setOf(
            "ACTIVITY_EMBEDDING", "BLUETOOTH_PRIVILEGED", "CALL_PRIVILEGED", "CHANGE_COMPONENT_ENABLED_STATE",
            "COMPANION_APPROVE_WIFI_CONNECTIONS", "CONTROL_INCALL_EXPERIENCE", "DUMP", "ENTER_CAR_MODE_PRIORITIZED",
            "LOCAL_MAC_ADDRESS", "LOCATION_HARDWARE", "MANAGE_USB", "MODIFY_AUDIO_ROUTING", "MODIFY_DAY_NIGHT_MODE",
            "READ_PRIVILEGED_PHONE_STATE", "REQUEST_COMPANION_SELF_MANAGED", "START_ACTIVITIES_FROM_BACKGROUND",
            "UPDATE_APP_OPS_STATS",
            "ADD_ALWAYS_UNLOCKED_DISPLAY", "ADD_TRUSTED_DISPLAY", "ASSOCIATE_COMPANION_DEVICES", "CAPTURE_SECURE_VIDEO_OUTPUT",
            "CREATE_VIRTUAL_DEVICE", "RECEIVE_SENSITIVE_NOTIFICATIONS", "REQUEST_COMPANION_PROFILE_AUTOMOTIVE_PROJECTION",
            "TOGGLE_AUTOMOTIVE_PROJECTION",
        ).mapTo(HashSet()) { "android.permission.$it" }

        // The same role's runtime permissions: its microphone, location, nearby_devices and notifications sets plus
        // the single ones
        val ROLE_RUNTIME_GRANTS = listOf(
            "RECORD_AUDIO", "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION", "BLUETOOTH_ADVERTISE", "BLUETOOTH_CONNECT",
            "BLUETOOTH_SCAN", "NEARBY_WIFI_DEVICES", "POST_NOTIFICATIONS", "CALL_PHONE", "READ_CALENDAR",
            "READ_CALL_LOG", "READ_CONTACTS", "READ_PHONE_STATE", "RECEIVE_SMS", "SEND_SMS",
        ).map { "android.permission.$it" }

        // PackageManager.FLAG_PERMISSION_USER_SET, USER_FIXED and POLICY_FIXED (system API, not in the public SDK)
        const val USER_CHOICE_FLAGS = 1 or 2 or 4
    }
}
