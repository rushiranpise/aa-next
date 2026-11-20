package io.github.rushiranpise.aanext

import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.DataOutputStream

// The phone screen talks to Android Auto's own head unit server (port 5277), which isn't exported, so it's started and
// stopped as root; "shutdown" closes its socket first. The phone screen opens no network socket in AA Next, so a
// firewall can't block it: the module takes one end of a Unix socket pair (the link) and connects it in Android Auto.
object PhoneScreen {
    const val HOST = "127.0.0.1"
    const val PORT = 5277
    const val EXTRA_LINK = "io.github.rushiranpise.aanext.PHONE_SCREEN_LINK"
    val LINK: Uri = AndroidAutoBridge.URI.buildUpon().appendPath("phone_screen_link").build()
    private const val SERVER = "com.google.android.projection.gearhead/.companion.DeveloperHeadUnitNetworkService"
    private var waiting: ParcelFileDescriptor? = null

    // Starts the server with the link's far end waiting; returns the phone screen's end
    @Synchronized
    fun connect(): ParcelFileDescriptor {
        val (ours, theirs) = ParcelFileDescriptor.createSocketPair()
        waiting?.close()
        waiting = theirs
        inBackground("am start-foreground-service -n $SERVER --ez $EXTRA_LINK true")
        return ours
    }

    @Synchronized
    fun take(): ParcelFileDescriptor? = waiting.also { waiting = null }

    fun stopServer() = inBackground("am start-foreground-service -a shutdown -n $SERVER")

    private fun inBackground(command: String) = Thread { runAsRoot(command) }.start()
}

// Runtime.exec("su") prompts the root manager for root the first time
fun runAsRoot(command: String): Boolean = try {
    val su = ProcessBuilder("su").redirectErrorStream(true).start()
    DataOutputStream(su.outputStream).use { it.writeBytes("$command\nexit\n"); it.flush() }
    su.waitFor() == 0
} catch (_: Exception) {
    false
}
