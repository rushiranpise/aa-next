package uk.noammm.aamods

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
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import java.io.FileInputStream
import java.io.FileOutputStream
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
import javax.net.ssl.X509TrustManager
import java.lang.reflect.Modifier
import java.util.concurrent.Executor
import java.util.function.Consumer

// Class and member names are the obfuscated ones from Android Auto 17.6 and change between versions
class Entry : IXposedHookLoadPackage {
    private lateinit var prefs: SharedPreferences
    private lateinit var cl: ClassLoader

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == SYSTEM_FRAMEWORK) {
            if (Prefs.on(XSharedPreferences(BuildConfig.APPLICATION_ID, Prefs.FILE), Prefs.MICROG)) {
                safely("system access") { hookSystemAccess(lpparam.classLoader) }
            }
            return
        }
        if (lpparam.packageName != GEARHEAD) return
        cl = lpparam.classLoader
        prefs = loadPrefs()
        log("loaded into ${lpparam.processName}")

        safely("flags") { hookFlags() }
        safely("phone screen") { hookPhoneScreen() }
        if (lpparam.processName.endsWith(":provider")) safely("report") { reportLoaded() }
        safely("phone screen on USB changes") { keepPhoneScreenOnUsbChanges() }
        if (Prefs.on(prefs, Prefs.NO_LOCKOUTS)) safely("lockouts") { hookDrivingStatus() }
        if (Prefs.on(prefs, Prefs.NO_FORCED_APPS)) safely("forced apps") { hookForcedApps() }
        if (Prefs.on(prefs, Prefs.ANY_CAR_APP)) safely("app allowlist") { hookAppAllowlist() }
        if (Prefs.on(prefs, Prefs.NO_TELEMETRY)) safely("telemetry binds") { hookTelemetryBinds() }
        if (Prefs.on(prefs, Prefs.MICROG)) safely("microG") { hookMicroG() }
        if (Prefs.on(prefs, Prefs.ANY_APP_LAUNCHER)) {
            safely("launcher tabs") { hookLauncherTabs() }
            safely("phone app rail") { keepRailBesidePhoneApps() }
        }
        val scale = prefs.getInt(Prefs.PHONE_APP_SCALE, 100)
        if (scale != 100) safely("phone app size") { hookPhoneAppDensity(scale) }
        val pinned = prefs.getString(Prefs.PINNED_APPS, "").orEmpty().split(',').filter { it.isNotEmpty() }
        if (pinned.isNotEmpty()) safely("pinned apps") { hookPinnedApps(pinned) }
        if (onlyOpenApps()) safely("open apps") {
            trackOpenApps()
            dashboardShowsOnlyOpenApps()
        }
        if (Prefs.on(prefs, Prefs.START_ON_LAUNCHER)) safely("start on launcher") { hookStartOnLauncher() }
        if (Prefs.on(prefs, Prefs.RECENTS)) safely("recents") { hookRecents() }
        if (Prefs.on(prefs, Prefs.LIGHTER)) safely("frame rate") { hookFrameRate() }
        val resolution = prefs.getString(Prefs.VIDEO_RESOLUTION, "").orEmpty()
        if (resolution.isNotEmpty()) safely("resolution") { hookResolution(resolution) }
        if (Prefs.on(prefs, Prefs.HIDE_NEWS_WEATHER)) safely("assistant tiles") { hookAssistantTiles() }
        safely("default apps") { hookDefaultApps() }
        if (Prefs.on(prefs, Prefs.MUTE_NOTIFICATIONS)) safely("notifications") { hookNotifications() }
    }

    // Every Phenotype flag is read through wck.fE() / wck.fF(Context); field b holds the flag name
    private fun hookFlags() {
        val overrides = flagOverrides()
        val allowAll = if (Prefs.on(prefs, Prefs.ANY_APP_LAUNCHER)) setOf(PHONE_APPS_FILTER) else emptySet()
        if (overrides.isEmpty() && allowAll.isEmpty()) return
        // Package filter proto: b is the has-bits, c the mode
        val allowAllFilter = if (allowAll.isEmpty()) null else XposedHelpers.newInstance(XposedHelpers.findClass("absk", cl)).also {
            XposedHelpers.setIntField(it, "b", 1)
            XposedHelpers.setIntField(it, "c", FILTER_ALLOW_ALL)
        }
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val name = XposedHelpers.getObjectField(param.thisObject, "b") as? String ?: return
                if (allowAllFilter != null && name in allowAll) {
                    param.result = allowAllFilter
                    return
                }
                val value = overrides[name] ?: return
                convert(param.result, value)?.let { param.result = it }
            }
        }
        val wck = XposedHelpers.findClass("wck", cl)
        XposedBridge.hookAllMethods(wck, "fE", hook)
        XposedBridge.hookAllMethods(wck, "fF", hook)
        log("overriding ${overrides.size + allowAll.size} flags")
    }

    private fun flagOverrides(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        if (Prefs.on(prefs, Prefs.NO_TELEMETRY)) map.putAll(NO_TELEMETRY_FLAGS)
        if (Prefs.on(prefs, Prefs.LIGHTER)) map.putAll(LIGHTER_FLAGS)
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

    // Three separate checks must all say "not driving" to keep typing and lists unlocked: the car's driving status
    // sensor (a bitmask, 0 = unrestricted), liu's parked/moving guess from real speed and gear, and the speed-bump rate
    // limiter.
    private fun hookDrivingStatus() {
        hookDrivingStatusSensor()
        hookParkedFusion()
        hookSpeedBump()
    }

    // The car reports driving status as sensor 11, a bitmask where 0 means unrestricted.
    // It enters in the :car process through these three paths, and every other process gets it from there.
    private fun hookDrivingStatusSensor() {
        val toEvent = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.args[0] != SENSOR_DRIVING_STATUS) return
                val bytes = XposedHelpers.getObjectField(param.result ?: return, "e") as? ByteArray ?: return
                if (bytes.isNotEmpty()) bytes[0] = 0
            }
        }
        val abpy = XposedHelpers.findClass("abpy", cl)
        XposedHelpers.findAndHookMethod("qtd", cl, "n", Int::class.javaPrimitiveType, abpy, toEvent)
        XposedHelpers.findAndHookMethod("fyq", cl, "f", Int::class.javaPrimitiveType, abpy, toEvent)

        val xkg = XposedHelpers.findClass("xkg", cl)
        XposedHelpers.findAndHookMethod(
            "abox", cl, "B",
            XposedHelpers.findClass("abox", cl), ByteArray::class.java, XposedHelpers.findClass("abol", cl),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val msg = param.result
                    if (xkg.isInstance(msg)) XposedHelpers.setIntField(msg, "b", 0)
                }
            })
    }

    // liu fuses real speed (GPS/wheel sensors, unrelated to sensor 11) and gear into a parked/moving
    // LiveData that gates the keyboard and the "Safety pause" overlay. Force its three inputs to
    // "parked" right before every recompute, so a car that's actually moving still reads as parked.
    private fun hookParkedFusion() {
        val liy = XposedHelpers.findClass("liy", cl)
        val carParked = XposedHelpers.getStaticObjectField(liy, "b")
        XposedHelpers.findAndHookMethod("liu", cl, "a", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                XposedHelpers.setObjectField(param.thisObject, "h", carParked)
                XposedHelpers.setBooleanField(param.thisObject, "i", true)
                XposedHelpers.setIntField(param.thisObject, "j", 101) // EnergyProfile.EVCONNECTOR_TYPE_OTHER, a valid "gear" value liu treats as parked
            }
        })
    }

    // Belt-and-braces: force the speed-bump rate limiter itself to UNLIMITED whenever it's set,
    // in case anything reaches it without going through the parked fusion above.
    private fun hookSpeedBump() {
        val lzh = XposedHelpers.findClass("lzh", cl)
        val unlimited = XposedHelpers.getStaticObjectField(lzh, "d")
        XposedHelpers.findAndHookMethod("lzi", cl, "j", lzh, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.args[0] = unlimited
            }
        })
    }

    // Setup refuses to project until the Google app, Maps and Speech Services are installed and up to date.
    // lfp (Android Auto side) and sfo (car setup side) each check one required package: 1 means OK.
    private fun hookForcedApps() {
        val ok = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (XposedHelpers.getObjectField(param.thisObject, "a") != GEARHEAD) param.result = 1
            }
        }
        XposedHelpers.findAndHookMethod("lfp", cl, "b", Context::class.java, ok)
        XposedHelpers.findAndHookMethod("sfo", cl, "a", Context::class.java, ok)
        // Without the Google app Android Auto hides every messaging app; keep them
        XposedHelpers.findAndHookMethod("jrl", cl, "b", Context::class.java, XC_MethodReplacement.returnConstant(true))
        // Lets Maps be hidden in the launcher customisation like any other app
        XposedHelpers.findAndHookMethod("nsn", cl, "k", ComponentName::class.java, XC_MethodReplacement.returnConstant(true))
    }

    // iyh.z(pkg, category, _, checkUses, _, _) is where every car app check ends up: a blocklist, the manifest check M
    // (a <uses> for the category in the app's car XML, which A finds), then Play Store and signature checks. Keep only
    // M, as z applies it (not to native apps), so sideloaded apps work and undeclared ones stay out.
    private fun hookAppAllowlist() {
        val iyh = XposedHelpers.findClass("iyh", cl)
        val qqe = XposedHelpers.findClass("qqe", cl)
        val optional = XposedHelpers.findClass("j\$.util.Optional", cl) // AA's desugared Optional, not java.util's
        val ofNullable = XposedHelpers.findMethodExact(optional, "ofNullable", Any::class.java)
        val usesXml = XposedHelpers.findMethodExact(iyh, "A", String::class.java)
        val declaresUses = XposedHelpers.findMethodExact(iyh, "M", optional, optional, String::class.java, qqe)
        val nativeApp = XposedHelpers.getStaticObjectField(qqe, "j")
        XposedHelpers.findAndHookMethod(
            iyh, "z", String::class.java, qqe,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            XposedHelpers.findClass("yhx", cl),
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any {
                    val pkg = param.args[0] as String
                    if (param.args[3] != true || param.args[1] === nativeApp) return true
                    return try {
                        val desc = usesXml.invoke(param.thisObject, pkg) as Int
                        val res = if (desc == 0) null
                        else AndroidAppHelper.currentApplication().packageManager.getResourcesForApplication(pkg)
                        declaresUses.invoke(
                            param.thisObject, ofNullable.invoke(null, res), ofNullable.invoke(null, desc.takeIf { it != 0 }),
                            pkg, param.args[1]
                        ) as Boolean
                    } catch (_: Throwable) {
                        false
                    }
                }
            })
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

        // sli.b is isGooglePlayServicesAvailable, which sks (GoogleApiAvailability) also goes through. Every client
        // asks it before connecting, and without Play Services it answers "missing", so microG would never be asked.
        XposedHelpers.findAndHookMethod("sli", cl, "b", Context::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (microGPackage(param.args[0] as Context) != null) param.result = 0
            }
        })

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

        // Android Auto lets only Google-signed apps such as Maps use its privileged car APIs (slj.f), which asks microG
        // with this switch on. microG lacks some keys Google signs its car apps with, so Android Auto refuses Maps
        // ("Wrong signature") and Maps crashes. Accept those keys too; slf.a is the verifier's own "allowed".
        XposedHelpers.findAndHookMethod("slj", cl, "f", String::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (XposedHelpers.getBooleanField(param.result ?: return, "b")) return
                val context = XposedHelpers.getObjectField(param.thisObject, "d") as Context
                if (microGPackage(context) == null || !signedWithKeyMicroGMisses(context, param.args[0] as String)) return
                param.result = XposedHelpers.getStaticObjectField(param.result.javaClass, "a")
                logOnce("microG: ${param.args[0]} is Google-signed")
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

    // mps.b asks each screen region's provider (mpz) for its startup intent: mqb (map regions) answers with the last
    // navigation app, mqe (the app region) with nothing, and an empty app region makes oyp open the dashboard. Swap the
    // two. lqn decides music autoplay once per drive; lqj.e is its "user turned autoplay off".
    private fun hookStartOnLauncher() {
        XposedHelpers.findAndHookMethod("mqb", cl, "b", "qsh", object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam) = Intent()
        })
        XposedHelpers.findAndHookMethod("mqe", cl, "b", "qsh", object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam) = Intent().setComponent(ComponentName(GEARHEAD, APP_LAUNCHER))
        })
        val decision = XposedHelpers.findClass("lqj", cl)
        val result = XposedHelpers.findClass("lqk", cl)
        XposedHelpers.findAndHookMethod("lqn", cl, "get", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val app = XposedHelpers.getObjectField(param.result ?: return, "b")
                param.result = XposedHelpers.newInstance(
                    result, arrayOf(decision, ComponentName::class.java), XposedHelpers.getStaticObjectField(decision, "e"), app,
                )
            }
        })
    }

    // Android Auto counts every wired connection as USB and closes them all when the USB state changes (shi.a), even
    // for a charger. The developer server's connections (the phone screen's) aren't USB, and there's one car connection
    // at a time, so USB changes are left alone while that's the latest one (jqk.d starts each).
    private fun keepPhoneScreenOnUsbChanges() {
        XposedHelpers.findAndHookMethod("jqk", cl, "d", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Bundle::class.java, "rxr", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                connectionType = param.args[1] as Int
            }
        })
        XposedHelpers.findAndHookMethod("shi", cl, "a", Intent::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (connectionType == DEVELOPER_SERVER_CONNECTION) param.result = null
            }
        })
    }

    @Volatile private var connectionType = 0

    // The phone screen's side inside Android Auto: link it to the head unit server (linkPhoneScreen), keep that server
    // (it listens on port 5277 of every network) on the phone itself, and accept exactly AA Mods' certificate besides
    // the ones chaining to Google's Automotive Link root (the trust manager next to rvx in its car SSLContext).
    private fun hookPhoneScreen() {
        XposedHelpers.findAndHookMethod(PHONE_SCREEN_SERVER, cl, "onStartCommand", Intent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if ((param.args[0] as? Intent)?.getBooleanExtra(PhoneScreen.EXTRA_LINK, false) != true) return
                val service = param.thisObject as Context
                Thread({ linkPhoneScreen(service) }, "AA Mods phone screen link").start()
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
                if ((param.args[0] as? Array<*>)?.firstOrNull()?.javaClass?.name != "rvx") return
                val google = (param.args[1] as? Array<*>)?.firstOrNull() as? X509TrustManager ?: return
                val context = AndroidAppHelper.currentApplication() ?: return
                val certificate = moduleContext(context).assets.open("headunit/cert.pem").use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
                }
                param.args[1] = arrayOf<TrustManager>(PhoneScreenTrust(google, certificate))
            }
        })
    }

    // ivf.a picks the frame rate for each video mode the car offers: 60 where the car allows it, 30 otherwise. Every
    // frame is drawn, H.264-encoded and encrypted on the phone, so 30 halves that work (and the bitrate) on 60 fps cars.
    private fun hookFrameRate() {
        XposedHelpers.findAndHookMethod("ivf", cl, "a", "xqe", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = minOf(param.result as Int, 30)
            }
        })
    }

    // Android Auto's own developer setting "Video resolution" (car_video_resolution in its carservice prefs) limits
    // which of the car's video sizes it accepts. It's answered with the user's choice while Android Auto checks the
    // car's sizes (ivn.B), and only when the car offers one within it: otherwise the car's own size is kept.
    private fun hookResolution(resolution: String) {
        val allowed = RESOLUTION_SIZES[resolution] ?: return
        val limiting = ThreadLocal<Boolean>()
        XposedHelpers.findAndHookMethod("ivn", cl, "B", Int::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val configs = XposedHelpers.getObjectField(param.thisObject, "i") as List<*>
                limiting.set(configs.any { XposedHelpers.getIntField(it, "c") in allowed })
            }

            override fun afterHookedMethod(param: MethodHookParam) = limiting.remove()
        })
        XposedHelpers.findAndHookMethod("android.app.SharedPreferencesImpl", cl, "getString", String::class.java, String::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args[0] == "car_video_resolution" && limiting.get() == true) param.result = resolution
            }
        })
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

    // mpj.k(region, app, time) records the app now showing in a region, or null when it went away; mqj.b is the app's
    // category (mqg: NAVIGATION, MEDIA, ...)
    private fun trackOpenApps() {
        XposedHelpers.findAndHookMethod("mpj", cl, "k", CAR_REGION_ID, "mqj", Long::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val region = param.args[0]
                val app = param.args[1]
                val component = app?.let { XposedHelpers.callMethod(it, "a") as ComponentName }
                val category = app?.let { XposedHelpers.getObjectField(it, "b").toString() }
                if (component != null && category != null && component.className !in NOT_APPS) {
                    openApps.remove(component)
                    openApps[component] = category
                    reconnectMusicApp(component.packageName)
                    refreshRail()
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
    }

    // mun.p shows the dashboard (map and cards) and first starts the default navigation app if none runs. Show it only
    // when navigation and music are both open; otherwise the rail button switches between the launcher and the one open
    // app. mpj.f() is the app now showing.
    private fun dashboardShowsOnlyOpenApps() {
        // Android Auto has two dashboard controllers (mun, muc) and uses one per layout; both start the default
        // navigation app to fill the dashboard's map, marking that start for the dashboard, which only opening a
        // navigation app may do here
        XposedHelpers.findAndHookMethod("mpk", cl, "k", Intent::class.java, "qrh", object : XC_MethodHook() {
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
                val showing = XposedHelpers.callMethod(XposedHelpers.callStaticMethod(XposedHelpers.findClass("mpj", cl), "b"), "f")
                    ?.let { XposedHelpers.callMethod(it, "a") }
                // Starting the open app again is what its launcher icon does: it comes to the front on its own
                startCarApp(if (showing == launcher) nav ?: media ?: launcher else launcher)
            }
        }
        for (controller in listOf("mun", "muc")) {
            XposedHelpers.findAndHookMethod(controller, cl, "p", Boolean::class.javaPrimitiveType, ANIMATION_PARAMS, dashboard)
        }
    }

    // Android Auto's own way to start an app on the car screen (mpk.i, which picks its region)
    private fun startCarApp(component: ComponentName, intent: Intent = Intent()) {
        val starter = XposedHelpers.callStaticMethod(XposedHelpers.findClass("mpk", cl), "a")
        XposedHelpers.callMethod(starter, "i", intent.setComponent(component))
    }

    private fun hookRecents() {
        recentsButton()
        recentsScreen()
        closeReceiver()
        keepClosedMusicAppsClosed()
        railShowsOpenApps()
    }

    // The rail fragment (njv) keeps the apps button (launcher_and_dashboard_icon), or next to phone apps the exit
    // button (native_app_exit_icon), alone in a container at the rail's end with the mic stacked on it. The recents
    // button goes in that container, so everything stacked on it moves along.
    private fun recentsButton() {
        XposedHelpers.findAndHookMethod("njv", cl, "onViewCreated", View::class.java, Bundle::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val root = param.args[0] as View
                fun id(name: String) = root.resources.getIdentifier(name, "id", GEARHEAD)
                val rows = listOf("launcher_and_dashboard_icon", "native_app_exit_icon")
                    .mapNotNull { root.findViewById<View>(id(it))?.let { button -> addRecentsNextTo(button) } }
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
    // and phone apps and a recent one). njv.m binds each slot; nkj.k builds the item for any app, phone apps included,
    // whose tap starts it the usual way (mpk.i), and an empty item (kfn.h) hides the slot.
    @Volatile private var rail: Any? = null

    private fun railShowsOpenApps() {
        XposedHelpers.findAndHookMethod("njv", cl, "onViewCreated", View::class.java, Bundle::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                rail = param.thisObject
            }
        })
        XposedHelpers.findAndHookMethod("njv", cl, "m", RAIL_SLOT, "nne", object : XC_MethodHook() {
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
            ?: return XposedHelpers.callStaticMethod(XposedHelpers.findClass("kfn", cl), "h")
        val recent = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("nkf", cl), "g")
        return XposedHelpers.callMethod(model, "k", app, recent, false, false, false)
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
    private class RecentsRow(val row: LinearLayout, val recents: View, val next: View) {
        fun arrange(vertical: Boolean, railWidth: Int) {
            val nextOnLeft = IntArray(2).also { next.getLocationInWindow(it) }[0] < railWidth / 2
            val recentsFirst = vertical || !nextOnLeft
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

    private fun addRecentsNextTo(next: View): RecentsRow? {
        val container = next.parent as? ViewGroup ?: return null
        val context = next.context
        val size = next.layoutParams.width
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
        val height = next.layoutParams.height
        container.removeView(next)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(button, LinearLayout.LayoutParams(size, height))
            addView(next)
        }
        container.addView(row)
        return RecentsRow(row, button, next)
    }

    // The recents screen borrows the climate control screen (HvacCarActivityService, car activity lgu), which only cars
    // with climate controls use. qrd.q is a car activity's start intent and ovs.w sets its view; a host still open in
    // the background comes back through qsk.e (onNewIntent), so the list is rebuilt there too.
    private fun recentsScreen() {
        XposedHelpers.findAndHookMethod("lgu", cl, "a", Bundle::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val screen = param.thisObject
                if ((XposedHelpers.callMethod(screen, "q") as? Intent)?.getBooleanExtra(EXTRA_RECENTS, false) == true) showRecents(screen)
            }
        })
        XposedHelpers.findAndHookMethod("qsk", cl, "e", Intent::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.thisObject.javaClass.name == "lgu" && (param.args[0] as Intent).getBooleanExtra(EXTRA_RECENTS, false)) {
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
        XposedHelpers.callMethod(screen, "w", view)
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

    // Opens the app, then finishes the recents screen (qrd.ew) so the next time it opens fresh
    private fun leaveRecents(screen: Any, app: ComponentName) {
        startCarApp(app)
        XposedHelpers.callMethod(screen, "ew")
    }

    // Asks :car to stop the app's car screen, then AA Mods to stop the app itself (not Android Auto's own apps). Music
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
            safely("stop app") { context.contentResolver.call(AndroidAutoBridge.URI, AndroidAutoBridge.STOP_APP, app.packageName, null) }
        }.start()
    }

    // Android Auto keeps a media connection (lns, a LiveData) to the music app it shows and reconnects when it dies
    // (lns.s, up to five times), which would start a closed app again. A closed app's connections are ended (lns.b),
    // shown disconnected and kept from connecting (lns.a) until it's opened again.
    private val musicConnections = java.util.Collections.synchronizedList(ArrayList<java.lang.ref.WeakReference<Any>>())
    private val closedMusicApps: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun keepClosedMusicAppsClosed() {
        XposedHelpers.findAndHookConstructor("lns", cl, Context::class.java, ComponentName::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                musicConnections += java.lang.ref.WeakReference(param.thisObject)
            }
        })
        XposedHelpers.findAndHookMethod("lns", cl, "a", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (musicApp(param.thisObject) in closedMusicApps) param.result = null
            }
        })
        XposedHelpers.findAndHookMethod("mpk", cl, "i", Intent::class.java, object : XC_MethodHook() {
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

    private fun connectionState(name: String) = XposedHelpers.callStaticMethod(XposedHelpers.findClass("lnp", cl), "a",
        XposedHelpers.getStaticObjectField(XposedHelpers.findClass("lor", cl), name))

    // Shown as Android Auto shows a connection it ended itself at the end of a car session (lor.f)
    private fun endMusicConnections(app: String) {
        closedMusicApps += app
        for (connection in musicConnectionsOf(app)) {
            XposedHelpers.callMethod(connection, "b")
            XposedHelpers.callMethod(connection, "m", connectionState("f"))
        }
    }

    // What lns does when it becomes active (lor.a, connecting), for the connections still watched (eqp.n)
    private fun reconnectMusicApp(app: String) {
        if (!closedMusicApps.remove(app)) return
        for (connection in musicConnectionsOf(app)) {
            if (XposedHelpers.callMethod(connection, "n") != true) continue
            XposedHelpers.callMethod(connection, "m", connectionState("a"))
            XposedHelpers.callMethod(connection, "a")
        }
    }

    private fun isCarAppLibraryApp(context: Context, app: ComponentName) = context.packageManager
        .queryIntentServices(Intent("androidx.car.app.CarAppService").setPackage(app.packageName), 0)
        .any { it.serviceInfo.name == app.className }

    @Volatile private var carActivities: Any? = null

    // ioh, the car activity manager of each car connection, lives in :car. The recents screen asks it to close an app
    // with a broadcast only Android Auto itself can send: a not-exported receiver, or before Android 13 Android Auto's
    // own signature permission for exactly that.
    private fun closeReceiver() {
        XposedBridge.hookAllConstructors(XposedHelpers.findClass("ioh", cl), object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val first = carActivities == null
                carActivities = param.thisObject
                if (!first) return
                val context = AndroidAppHelper.currentApplication() ?: return
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        @Suppress("DEPRECATION")
                        val app = intent.getParcelableExtra<ComponentName>(EXTRA_COMPONENT) ?: return
                        safely("close app") { finishCarScreens(carActivities ?: return@safely, app) }
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

    // What ioh.au does, for hidden screens too: ioh.u holds a manager set per display (inu), whose multimap f gives the
    // screens (inn) of a component. A started one (H) is stopped the way au stops it (y); one an app in front of it
    // covers isn't started, so au skips it, and is finished instead (l, finishManager: out of its task and unbound).
    private fun finishCarScreens(carActivities: Any, app: ComponentName) = synchronized(carActivities) {
        val screen = XposedHelpers.findClass("inn", cl)
        val stop = XposedHelpers.findMethodExact(screen, "y", screen)
        for (display in (XposedHelpers.getObjectField(carActivities, "u") as Map<*, *>).values) {
            val screens = XposedHelpers.callMethod(XposedHelpers.getObjectField(display, "f"), "c", app) as Collection<*>
            for (manager in screens.toList()) {
                if (XposedHelpers.callMethod(manager, "H") as Boolean) stop.invoke(manager, null) else XposedHelpers.callMethod(manager, "l")
            }
        }
    }

    private var module: Context? = null

    // AA Mods' own package, for its strings and icons inside Android Auto
    private fun moduleContext(context: Context) =
        module ?: context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY).also { module = it }

    // How the settings reach the hooks. XSharedPreferences reads the module's own file, which works only
    // where the framework injects the module into its own app and shares that file (Vector does). LSPosed
    // injects only the apps in a module's scope and never the module itself, so its shared file stays
    // empty; there the bridge answers, asking AA Mods' own app, which is a plain app every framework can
    // serve. It's read once per process, like the file was: settings changes ask for an Android Auto
    // restart either way
    private fun loadPrefs(): SharedPreferences {
        val shared = XSharedPreferences(BuildConfig.APPLICATION_ID, Prefs.FILE)
        if (shared.file.canRead()) return shared
        return try {
            bridgeContext()?.contentResolver?.call(AndroidAutoBridge.URI, AndroidAutoBridge.PREFS, null, null)
                ?.let { Prefs.FromBridge(it) } ?: Prefs.empty
        } catch (t: Throwable) {
            log("bridge prefs failed: $t")
            Prefs.empty
        }
    }

    // A context for the bridge, which the hooks may need before any application exists. The system context
    // is made early in every process, and a provider call made with it still arrives as Android Auto's
    // (the system derives the caller from the process, not the context)
    private fun bridgeContext(): Context? = AndroidAppHelper.currentApplication()
        ?: runCatching {
            val thread = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.app.ActivityThread", cl), "currentActivityThread")
            XposedHelpers.callMethod(thread, "getSystemContext") as? Context
        }.getOrNull()

    // Tells AA Mods the module runs in Android Auto, and which version, for the welcome screen's check. Only from
    // :provider, the process that check starts, once its Application exists.
    private fun reportLoaded() = Thread {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var app = AndroidAppHelper.currentApplication()
        while (app == null && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
            app = AndroidAppHelper.currentApplication()
        }
        app?.let { safely("report") { it.contentResolver.call(AndroidAutoBridge.URI, AndroidAutoBridge.LOADED, BuildConfig.VERSION_NAME, null) } }
    }.start()

    // Takes the phone screen's end of its link from AA Mods and pipes it to the head unit server. The server starts a
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

    // Android Auto marks every phone app immersive (lvg: mqj with isImmersiveApp), which runs it in the full-screen
    // IMMERSIVE area (launch rule nrw) and hides the rail until a swipe (nrm). Not immersive, a phone app runs in the
    // normal app area beside the rail, like any Android Auto app.
    private fun keepRailBesidePhoneApps() {
        val bool = Boolean::class.javaPrimitiveType
        XposedHelpers.findAndHookConstructor("mqj", cl, "mqi", "mqg", bool, bool, bool, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args[4] == true) param.args[3] = false // isNativeApp -> not isImmersiveApp
            }
        })
    }

    // The Cradle launcher (mqs) splits apps with mcz: case 9 keeps parked-only ones for the Entertainment tab
    // (Google's games), case 10 the rest for Car Optimized Apps. Put everything in Car Optimized Apps and drop the
    // then empty Entertainment tab from the template (TabTemplateInternal copies gnc.d, the tab list).
    private fun hookLauncherTabs() {
        XposedHelpers.findAndHookMethod("mcz", cl, "invoke", Any::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                when (XposedHelpers.getIntField(param.thisObject, "a")) {
                    9 -> param.result = ArrayList<Any>()
                    10 -> param.result = ArrayList(param.args[0] as List<*>)
                }
            }
        })
        XposedHelpers.findAndHookConstructor(
            "com.android.car.libraries.apphost.internal.model.TabTemplateInternal", cl, XposedHelpers.findClass("gnc", cl),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val tabs = XposedHelpers.getObjectField(param.args[0], "d") as List<*>
                    XposedHelpers.setObjectField(param.args[0], "d", ArrayList(tabs.filter {
                        XposedHelpers.callMethod(it, "getContentId") != "ENTERTAINMENT_APPS_ID"
                    }))
                }
            })
    }

    // fyq.e picks the density of phone apps on the car screen from that car's screen size and dpi. Scale it rather than
    // replace it, so one setting suits every car.
    private fun hookPhoneAppDensity(percent: Int) {
        XposedHelpers.findAndHookMethod(
            "fyq", cl, "e", XposedHelpers.findClass("com.google.android.gms.car.DrawingSpec", cl), XposedHelpers.findClass("jkr", cl),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = (param.result as Int) * percent / 100
                }
            })
    }

    // mqs.a builds every launcher tab's grid from its app list (mre, whose a.c.a() is the component). Pinned apps go
    // first, in the order they were pinned; sortedBy is stable, so the rest keep Android Auto's order.
    private fun hookPinnedApps(pinned: List<String>) {
        XposedHelpers.findAndHookMethod("mqs", cl, "a", List::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val apps = param.args[0] as? List<*> ?: return
                param.args[0] = apps.sortedBy {
                    val app = XposedHelpers.getObjectField(XposedHelpers.getObjectField(it, "a"), "c")
                    val pkg = (XposedHelpers.callMethod(app, "a") as ComponentName).packageName
                    pinned.indexOf(pkg).let { i -> if (i < 0) pinned.size else i }
                }
            }
        })
    }

    // mqv.e builds the launcher's Google Assistant shortcut tiles; each is keyed by a fake component package
    private fun hookAssistantTiles() {
        val hidden = setOf("NewsAssistantAction", "WeatherAssistantAction")
        val ygw = XposedHelpers.findClass("ygw", cl)
        XposedHelpers.findAndHookMethod(
            "mqv", cl, "e", XposedHelpers.findClass("qsh", cl), Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val tiles = (param.result as? List<*>) ?: return
                    param.result = XposedHelpers.callStaticMethod(ygw, "o", tiles.filter {
                        val app = XposedHelpers.getObjectField(it, "c")
                        (XposedHelpers.callMethod(app, "a") as ComponentName).packageName !in hidden
                    })
                }
            })
    }

    // qji is the default app manager; a(mqg) and b(mqg) are what everything asks for the default per category.
    // mqg.h is the category id: 1 navigation, 3 media.
    private fun hookDefaultApps() {
        val chosen = mapOf(1 to Prefs.DEFAULT_NAV, 3 to Prefs.DEFAULT_MEDIA)
            .mapValues { ComponentName.unflattenFromString(prefs.getString(it.value, "").orEmpty()) }
            .filterValues { it != null }
        if (chosen.isEmpty()) return
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                chosen[XposedHelpers.getIntField(param.args[0], "h")]?.let { param.result = it }
            }
        }
        val mqg = XposedHelpers.findClass("mqg", cl)
        XposedHelpers.findAndHookMethod("qji", cl, "a", mqg, hook)
        XposedHelpers.findAndHookMethod("qji", cl, "b", mqg, hook)
    }

    // nuz.j is where every car notification enters (centre and heads-up). Only calls, media and navigation get through.
    private fun hookNotifications() {
        val keep = setOf("PENDING_CALL", "ONGOING_CALL", "MEDIA", "NAVIGATION", "NAVIGATION_ALERT")
        XposedHelpers.findAndHookMethod(
            "nuz", cl, "j", String::class.java, Long::class.javaPrimitiveType, XposedHelpers.findClass("nvh", cl),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val type = XposedHelpers.getObjectField(param.args[2], "x") as Enum<*>
                    if (type.name !in keep) param.result = null
                }
            })
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

    private fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log("$what hook failed: $t")
        }
    }

    private fun log(msg: String) = XposedBridge.log("AAMods: $msg")

    companion object {
        const val GEARHEAD = "com.google.android.projection.gearhead"
        const val PHONE_SCREEN_SERVER = "com.google.android.projection.gearhead.companion.DeveloperHeadUnitNetworkService"
        const val PHONE_SCREEN_START_MS = 15_000L
        const val DEVELOPER_SERVER_CONNECTION = 3
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
        const val EXTRA_RECENTS = "uk.noammm.aamods.RECENTS"
        const val ACTION_CLOSE = "uk.noammm.aamods.CLOSE_CAR_APP"
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
        const val PHONE_APPS_FILTER = "CradleFeature__app_launcher_package_list"
        const val FILTER_ALLOW_ALL = 4

        // Periodic timers get a one-day interval rather than 0, which their schedulers would reject.
        // The audio stats collector is only created when its period is above 0 (irk).
        private const val DAY_MS = "86400000"
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
            "MapCapabilitiesGating__metadata_logging_enabled" to "false",
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
