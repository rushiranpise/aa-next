package io.github.rushiranpise.aanext

import android.content.Context
import android.content.pm.PackageManager

// Which Xposed framework the phone runs, for the setup screens to name it. LSPosed and Vector are the same
// codebase (Vector is its rename), and either can hide its manager inside another app instead of installing
// it, so the manager apps are looked for first and, when root is already allowed, each framework's own
// Magisk module directory answers for the hidden ones. Anything else stays neutral
object Framework {
    const val LSPOSED = "LSPosed"
    const val VECTOR = "Vector"

    private val MANAGERS = mapOf(
        "org.lsposed.manager" to LSPOSED,
        "org.matrix.vector.manager" to VECTOR,
    )
    private val MODULES = mapOf(
        "/data/adb/modules/zygisk_lsposed" to LSPOSED,
        "/data/adb/modules/zygisk_vector" to VECTOR,
    )

    private var shallow: String? = null
    private var deep: String? = null

    // askRoot runs one su call per candidate, so only screens that already hold root ask for it
    fun name(context: Context, askRoot: Boolean = false): String {
        (if (askRoot) deep else shallow)?.let { return it }
        val pm = context.packageManager
        fun installed(pkg: String) = try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        val name = MANAGERS.entries.firstOrNull { installed(it.key) }?.value
            ?: (if (askRoot) MODULES.entries.firstOrNull { runAsRoot("test -d ${it.key}") }?.value else null)
            ?: "Xposed"
        // A deep answer also answers the shallow one: once root said which framework it is, plain callers
        // get that name instead of the neutral guess they may have cached
        if (askRoot) deep = name
        shallow = name
        return name
    }
}
