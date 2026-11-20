package io.github.rushiranpise.aanext

import android.content.Context
import android.os.Build
import android.os.SystemClock
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.reflect.Method
import java.util.zip.ZipFile

/**
 * Android Auto keeps the names of its manifest components, of the GMS Car API and of the framework
 * methods it overrides, and renames every other class (about 25,000 of them) with each build, often
 * reusing the old names for different classes. So every hook has two ways to find what it needs: the
 * name it had in 18.2, accepted only when the class still has the method with the expected signature,
 * and a DexKit query over the installed APK that finds it again from the kept types in its signature,
 * its shape, or the strings it logs.
 *
 * The name is always tried first: on 18.2 nothing is parsed, so the queries cost nothing until an
 * update renames something. The dex is then read once per process (DexKit's native library, taken
 * from the module's own APK) and the answer written to Android Auto's cache directory, so the next
 * process and every start after that only read a line. The log says which path found each hook
 * ("dex: frame.rate = ...") or that neither did.
 */
class Dex(private val apk: File?, private val cache: File?, private val cl: ClassLoader) {

    // "key=answer" lines from earlier runs, and the answers found in this one
    private val answers = HashMap<String, String>()
    private var read = false
    private var bridge: DexKitBridge? = null
    private var nativeLoaded = false
    private var nativeError: Throwable? = null

    // One cache file per Android Auto build: an update replaces the APK, which changes both length and
    // timestamp, so the old answers are never even read
    private val cacheFile: File? by lazy {
        val dir = cache ?: return@lazy null
        val stamp = apk?.let { "${it.length()}-${it.lastModified()}" } ?: "unknown"
        File(dir, "aanext-dex-$stamp.txt")
    }

    /**
     * A method of Android Auto: [methodName] on [className] with exactly [params] when that still
     * exists (the 18.2 name), otherwise the single method [query] describes.
     */
    fun method(key: String, className: String, methodName: String, vararg params: Any?, query: MethodMatcher.() -> Unit): Method? {
        // A renamed parameter type makes the name lookup throw rather than miss
        try {
            XposedHelpers.findMethodExactIfExists(className, cl, methodName, *params)?.let { return it }
        } catch (_: Throwable) {
            // Fall through to the query
        }
        val descriptor = answer(key) { dex ->
            val found = dex.findMethod { matcher(MethodMatcher().apply(query)) }
            if (found.size != 1) {
                log("dex: $key matched ${found.size} methods: ${found.take(6).joinToString()}")
                return@answer null
            }
            found[0].toDexMethod().toString()
        } ?: return null
        return try {
            DexMethod.deserialize(descriptor).getMethodInstance(cl)
        } catch (t: Throwable) {
            log("dex: $key no longer loads ($descriptor): $t")
            null
        }
    }

    /**
     * A class of Android Auto: [name] when it still exists (the 18.2 name) and passes [accept], otherwise
     * the single class [query] describes. [accept] is what keeps a reused name from pointing the hooks at
     * a different class: shape it checks the same things the query does.
     */
    fun clazz(key: String, name: String?, accept: (Class<*>) -> Boolean = { true }, query: ClassMatcher.() -> Unit): Class<*>? {
        if (name != null) {
            val named = try {
                XposedHelpers.findClassIfExists(name, cl)
            } catch (_: Throwable) {
                null
            }
            if (named != null && runCatching { accept(named) }.getOrDefault(false)) return named
        }
        val resolved = answer(key) { dex ->
            val found = dex.findClass { matcher(ClassMatcher().apply(query)) }
            if (found.size != 1) {
                log("dex: $key matched ${found.size} classes: ${found.take(6).joinToString()}")
                return@answer null
            }
            found[0].name
        } ?: return null
        return try {
            XposedHelpers.findClassIfExists(resolved, cl)?.takeIf { runCatching { accept(it) }.getOrDefault(false) }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Every class [query] describes, for the cases where the class to hook is the one thing several
     * matches have in common rather than a match itself.
     */
    fun classes(key: String, query: ClassMatcher.() -> Unit): List<Class<*>> {
        val names = answer(key) { dex ->
            dex.findClass { matcher(ClassMatcher().apply(query)) }.joinToString(";") { it.name }
        } ?: return emptyList()
        return names.split(';').mapNotNull { runCatching { XposedHelpers.findClassIfExists(it, cl) }.getOrNull() }
    }

    /**
     * Every method [query] describes, for the hooks that must cover every implementation rather than
     * one: sensor events, for example, leave a process through several classes, and a new build may
     * split or merge them.
     */
    fun methods(key: String, query: MethodMatcher.() -> Unit): List<Method> {
        val descriptors = answer(key) { dex ->
            // One per line: a method descriptor contains ';' in every object type
            dex.findMethod { matcher(MethodMatcher().apply(query)) }.joinToString("|") { it.toDexMethod().toString() }
        } ?: return emptyList()
        return descriptors.split('|').filter { it.isNotBlank() }.mapNotNull {
            runCatching { DexMethod.deserialize(it).getMethodInstance(cl) }.getOrNull()
        }.toList()
    }

    /** Frees the native dex index; a later query reads the dex files again. */
    fun close() {
        bridge?.let { runCatching { it.close() } }
        bridge = null
    }

    // The answer cached for [key], or the one [query] finds now, kept for the other processes
    private fun answer(key: String, query: (DexKitBridge) -> String?): String? {
        readAnswers()
        answers[key]?.let { return it }
        val value = try {
            query(bridge())
        } catch (t: Throwable) {
            log("dex: $key failed: $t")
            return null
        } ?: return null
        answers[key] = value
        cacheFile?.let { file ->
            try {
                file.parentFile?.mkdirs()
                file.appendText("$key=$value\n")
            } catch (_: Throwable) {
                // A cache that cannot be written only costs the next process a query
            }
        }
        log("dex: $key = $value")
        return value
    }

    private fun readAnswers() {
        if (read) return
        read = true
        val file = cacheFile ?: return
        try {
            if (!file.exists()) return
            file.forEachLine { line ->
                val at = line.indexOf('=')
                if (at > 0) answers[line.substring(0, at)] = line.substring(at + 1)
            }
        } catch (_: Throwable) {
            // An unreadable cache only costs a query
        }
    }

    private fun bridge(): DexKitBridge {
        bridge?.let { return it }
        val path = apk?.path ?: error("Android Auto's APK path is unknown")
        loadNative()
        val started = SystemClock.elapsedRealtime()
        log("dex: reading Android Auto's dex files (first query on this build)")
        return DexKitBridge.create(path).also {
            bridge = it
            log("dex: dex files read in ${SystemClock.elapsedRealtime() - started} ms")
        }
    }

    // DexKit has no JNI_OnLoad of its own: whoever uses it loads libdexkit.so. Xposed adds the module's
    // lib directory to the search path only when the installer extracted it, so fall back to copying
    // the library out of the module's own APK next to Android Auto's cache and loading it from there.
    private fun loadNative() {
        if (nativeLoaded) return
        nativeError?.let { throw IllegalStateException("libdexkit.so could not be loaded: $it", it) }
        try {
            loadNativeLibrary()
            nativeLoaded = true
        } catch (t: Throwable) {
            nativeError = t
            throw t
        }
    }

    // The name includes the module's size and timestamp, so an updated module never loads an old copy,
    // and each process copies to a temporary name first: another process may be copying the same file
    // at the same time, and a rename is atomic, so every process loads a complete one
    private fun loadNativeLibrary() {
        try {
            System.loadLibrary("dexkit")
            return
        } catch (_: UnsatisfiedLinkError) {
            // Not on the search path: take it from the module's APK
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: error("no supported ABI")
        val module = moduleApk() ?: error("the module's APK path is unknown")
        log("dex: module at $module")
        // The installer extracts the module's native libraries next to its APK (extractNativeLibs is
        // on for exactly this), and that copy is in a read-only directory, so prefer it
        extractedLibrary(module, abi)?.let { extracted ->
            System.load(extracted.path)
            log("dex: loaded ${extracted.path}")
            return
        }
        val dir = cache ?: File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")
        dir.mkdirs()
        val stamp = File(module).let { "${it.length()}-${it.lastModified()}" }
        val library = File(dir, "libdexkit-$abi-${stamp.hashCode()}.so")
        if (!library.exists()) {
            val temp = File(dir, "${library.name}.${android.os.Process.myPid()}.tmp")
            ZipFile(module).use { zip ->
                val entry = zip.getEntry("lib/$abi/libdexkit.so") ?: error("no libdexkit.so for $abi in $module")
                temp.outputStream().use { out -> zip.getInputStream(entry).use { it.copyTo(out) } }
            }
            temp.setReadable(true, false)
            temp.setExecutable(true, false)
            if (!temp.renameTo(library)) {
                temp.delete()
                if (!library.exists()) error("could not put libdexkit.so in ${dir.path}")
            }
        }
        library.setReadable(true, false)
        library.setExecutable(true, false)
        System.load(library.path)
        log("dex: loaded ${library.path}")
    }

    // The installer extracts the module's libraries to lib/<directory>/ next to the APK, but the
    // directory is not always named like the ABI in the APK: this device has lib/arm64 for the
    // arm64-v8a entry, so look for the library file instead of guessing the directory
    private fun extractedLibrary(module: String, abi: String): File? {
        val root = File(File(module).parentFile, "lib")
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return null
        val ordered = dirs.sortedBy { if (it.name == abi) 0 else if (abi.startsWith(it.name)) 1 else 2 }
        return ordered.map { File(it, "libdexkit.so") }.firstOrNull { it.exists() }
    }

    // The module's own APK. The package manager knows where it is installed, and the system context
    // exists before Android Auto's own context does (its package manager also is not filtered by
    // package visibility). The class loader and protection domain are fallbacks for frameworks where
    // ActivityThread differs
    private fun moduleApk(): String? {
        try {
            val thread = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.app.ActivityThread", cl), "currentActivityThread")
            val context = XposedHelpers.callMethod(thread, "getSystemContext") as? Context
            context?.packageManager?.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)?.sourceDir?.let { return it }
        } catch (_: Throwable) {
            // Fall back to the class loader
        }
        var loader: ClassLoader? = Entry::class.java.classLoader
        while (loader != null) {
            try {
                val pathList = XposedHelpers.getObjectField(loader, "pathList")
                for (field in arrayOf("dexElements", "pathElements")) {
                    val elements = runCatching { XposedHelpers.getObjectField(pathList, field) as? Array<*> }.getOrNull() ?: continue
                    for (element in elements) {
                        val path = runCatching { XposedHelpers.getObjectField(element, "path") as? String }.getOrNull() ?: continue
                        if (path.endsWith(".apk") && !path.contains(GEARHEAD)) return path
                    }
                }
            } catch (_: Throwable) {
                // Next loader
            }
            APK_IN_CLASS_LOADER.find(loader.toString())?.groupValues?.get(1)?.let { if (!it.contains(GEARHEAD)) return it }
            loader = loader.parent
        }
        return try {
            Entry::class.java.protectionDomain?.codeSource?.location?.toURI()?.let { File(it).path }
        } catch (_: Throwable) {
            null
        }
    }

    private fun log(msg: String) = XposedBridge.log("AANext: $msg")

    companion object {
        private const val GEARHEAD = "com.google.android.projection.gearhead"
        private val APK_IN_CLASS_LOADER = Regex("\"([^\"]+\\.apk)\"")
    }
}
