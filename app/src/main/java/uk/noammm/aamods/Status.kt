package uk.noammm.aamods

import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap

// What the hooks report about themselves, recorded inside Android Auto and shown on AA Mods' status
// screen. A hook is ready when it installed, failed when installing it threw (almost always because the
// Android Auto build moved the class or method), and off when its setting is off. The counter shows how
// often a hook has actually done something when it has a log line to count.
object Status {
    const val READY = "ready"
    const val FAILED = "failed"
    const val OFF = "off"

    private class Hook(var state: String, var detail: String, var used: Int)

    private val hooks = LinkedHashMap<String, Hook>()

    @Synchronized
    fun installed(name: String, detail: String = "") {
        val hook = hooks[name]
        if (hook == null) {
            hooks[name] = Hook(READY, detail, 0)
        } else if (hook.state == READY && detail.isNotEmpty()) {
            hook.detail = detail
        }
    }

    @Synchronized
    fun failed(name: String, error: Throwable) {
        val detail = (error.message ?: error.javaClass.simpleName).lineSequence().first().take(200)
        hooks[name] = Hook(FAILED, detail, hooks[name]?.used ?: 0)
    }

    @Synchronized
    fun off(name: String, detail: String = "") {
        if (name !in hooks) hooks[name] = Hook(OFF, detail, 0)
    }

    // Counts what a hook did after it was installed; only the hooks whose work logs it report this
    @Synchronized
    fun used(name: String) {
        hooks[name]?.let { it.used++ }
    }

    @Synchronized
    fun report(process: String, androidAuto: String): String {
        val list = JSONArray()
        for ((name, hook) in hooks) {
            list.put(
                JSONObject()
                    .put("name", name)
                    .put("state", hook.state)
                    .put("detail", hook.detail)
                    .put("used", hook.used)
            )
        }
        return JSONObject()
            .put("process", process)
            .put("androidAuto", androidAuto)
            .put("module", BuildConfig.VERSION_NAME)
            .put("at", System.currentTimeMillis())
            .put("hooks", list)
            .toString()
    }
}
