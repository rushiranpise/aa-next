package uk.noammm.aamods

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Updates from GitHub releases, the way Kav does it: the latest release's tag against versionName, then its APK
// downloaded to the cache and handed to the system installer
object Updates {
    private const val REPO = "ImNoammm/aa-mods"
    private const val USER_AGENT = "AA Mods"

    class Release(val version: String, val notes: String, val apkUrl: String?, val apkBytes: Long)

    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    fun latest(): Release {
        val connection = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        if (connection.responseCode != 200) throw IOException("GitHub answered ${connection.responseCode}")
        val release = JSONObject(connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
        val assets = release.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0)).mapNotNull { assets!!.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
        return Release(
            version = release.optString("tag_name").removePrefix("v"),
            notes = release.optString("body"),
            apkUrl = apk?.optString("browser_download_url")?.takeIf { it.isNotBlank() },
            apkBytes = apk?.optLong("size") ?: -1L,
        )
    }

    fun isNewer(remote: String, installed: String): Boolean {
        val a = numbers(remote)
        val b = numbers(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(version: String) = Regex("\\d+").findAll(version).map { it.value.toIntOrNull() ?: 0 }.toList()

    private fun folder(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }

    // GitHub serves release files through a redirect to its CDN
    fun download(context: Context, release: Release, onProgress: (Long, Long) -> Unit): File {
        val url = release.apkUrl ?: throw IOException("This release has no APK")
        val file = File(folder(context), "aa-mods-${release.version}.apk")
        if (file.exists() && release.apkBytes > 0 && file.length() == release.apkBytes) {
            onProgress(file.length(), file.length())
            return file
        }
        folder(context).listFiles()?.forEach { if (it != file) it.delete() }
        val part = File(file.path + ".part")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        if (connection.responseCode != 200) throw IOException("The download failed (${connection.responseCode})")
        val total = if (release.apkBytes > 0) release.apkBytes else connection.contentLengthLong
        connection.inputStream.use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    onProgress(done, total)
                }
            }
        }
        if (total > 0 && part.length() != total) {
            part.delete()
            throw IOException("The download was cut short, try again")
        }
        if (!part.renameTo(file)) throw IOException("Couldn't keep the download")
        return file
    }

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun askInstallPermission(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        }
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}
