package io.github.rushiranpise.aanext

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.FileNotFoundException

// What the module asks of AA Next from inside Android Auto, and only Android Auto may: the phone screen's link
// (see PhoneScreen), the settings to run with (Prefs, the one settings channel that needs nothing from the
// framework), stopping an app closed from the recents screen, and reporting it runs there (WelcomeActivity) and
// how its hooks installed (Status)
class AndroidAutoBridge : ContentProvider() {
    // Returned as a file, the link is closed here once sent, so either side closing its end ends the other's reads
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        checkCaller()
        return PhoneScreen.take() ?: throw FileNotFoundException("No phone screen waiting")
    }

    // Android's stop-app, as root: it ends the app and its services like a swipe in recents, but keeps its alarms and
    // jobs, so a messenger still gets its notifications (force-stop would cancel those). Without stop-app (before
    // Android 14) only the app's background processes are ended.
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        checkCaller()
        // Written before returning, since AA Next is often started just for this call
        if (method == LOADED) {
            context!!.getSharedPreferences(AppState.FILE, Context.MODE_PRIVATE).edit()
                .putString(AppState.LOADED_VERSION, arg)
                .putLong(AppState.LOADED_AT, System.currentTimeMillis())
                .commit()
        }
        if (method == PREFS) return Prefs.bundle(context!!.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE))
        if (method == STATUS && arg != null) {
            val report = runCatching { JSONObject(arg) }.getOrNull()
            val process = report?.optString("process").orEmpty()
            if (process.isNotEmpty()) {
                context!!.getSharedPreferences(AppState.FILE, Context.MODE_PRIVATE).edit()
                    .putString(AppState.STATUS_PREFIX + process, arg)
                    .commit()
            }
        }
        if (method == STOP_APP && arg != null && arg.matches(PACKAGE_NAME) && arg != Entry.GEARHEAD && arg != BuildConfig.APPLICATION_ID) {
            Thread { runAsRoot("am stop-app $arg || am kill $arg") }.start()
        }
        return null
    }

    // callingPackage is what the system derives for the caller. The hooks usually call through Android
    // Auto's application context, but early ones call through the system context they make before any
    // application exists, which names the caller "android" while the uid is still Android Auto's. The
    // uid answers for that case, and no other app's uid does
    private fun checkCaller() {
        if (callingPackage == Entry.GEARHEAD ||
            context?.packageManager?.getPackagesForUid(Binder.getCallingUid())?.contains(Entry.GEARHEAD) == true
        ) return
        throw SecurityException("Only Android Auto may use AA Next's bridge")
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: Uri) = null
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        // Built from the installed package so the provider's authority in the manifest and the hooks can
        // never drift apart (the hooks load this same BuildConfig from the module APK)
        val URI: Uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.bridge")
        const val STOP_APP = "stop_app"
        const val LOADED = "loaded"
        const val PREFS = "prefs"
        const val STATUS = "status"
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_.]+")
    }
}
