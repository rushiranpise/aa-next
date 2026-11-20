package uk.noammm.aamods

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

// The first screen: checks the three things AA Mods needs (root, the module enabled in the Xposed framework,
// and the module running inside Android Auto) and helps with each one that's missing
class WelcomeActivity : AppCompatActivity() {
    private val background = Executors.newSingleThreadExecutor()
    private lateinit var state: SharedPreferences
    private lateinit var root: View
    private lateinit var xposed: View
    private lateinit var androidAuto: View
    private var rooted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)
        state = getSharedPreferences(AppState.FILE, MODE_PRIVATE)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top + 24.dp, bottom = bars.bottom + 24.dp)
            insets
        }

        val section = layoutInflater.inflate(R.layout.item_section, findViewById(R.id.checks), false)
        section.findViewById<TextView>(R.id.header).setText(R.string.welcome_checks)
        section.findViewById<MaterialCardView>(R.id.card).clipToOutline = true
        findViewById<LinearLayout>(R.id.checks).addView(section)
        val rows = section.findViewById<LinearLayout>(R.id.rows)
        root = rows.check(R.drawable.ic_terminal, R.string.check_root, R.string.check_root_summary) { checkRoot() }
        xposed = rows.check(R.drawable.ic_extension, R.string.check_xposed, R.string.check_xposed_summary) { explainFramework() }
        // check_xposed names the framework, so it's filled in here rather than straight from the resource,
        // and filled in again once root is granted and the deeper check can read the Magisk module directories
        xposed.findViewById<TextView>(R.id.title).text = getString(R.string.check_xposed, Framework.name(this))
        androidAuto = rows.check(R.drawable.ic_directions_car, R.string.check_android_auto, R.string.check_android_auto_summary) {
            if (rooted) checkAndroidAuto(restart = true) else Toast.makeText(this, R.string.restart_aa_failed, Toast.LENGTH_LONG).show()
        }

        findViewById<View>(R.id.done).setOnClickListener {
            state.edit().putBoolean(AppState.WELCOME_DONE, true).apply()
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }
        checkRoot()
    }

    override fun onResume() {
        super.onResume()
        // The framework shares the module's file where it injects the module into its own app (Prefs.open,
        // Vector's way); elsewhere, LSPosed for one, the report from inside Android Auto is the sign it's on
        status(xposed, Prefs.open(this) != null || AppState.reported(this), R.string.status_enabled, R.string.status_not_enabled)
        checkAndroidAuto(restart = false)
    }

    private fun LinearLayout.check(icon: Int, title: Int, summary: Int, onClick: () -> Unit): View =
        layoutInflater.inflate(R.layout.item_setting, this, false).also {
            it.findViewById<ImageView>(R.id.icon).setImageResource(icon)
            it.findViewById<TextView>(R.id.title).setText(title)
            it.findViewById<TextView>(R.id.summary).setText(summary)
            it.setOnClickListener { onClick() }
            addView(it)
        }

    // The first su call is what makes the root manager ask
    private fun checkRoot() {
        status(root, null)
        background.execute {
            val ok = runAsRoot("id")
            runOnUiThread {
                rooted = ok
                status(root, ok, R.string.status_granted, R.string.status_allow_root)
                if (ok) refreshFramework()
            }
        }
    }

    // Root granted: the manager may hide inside another app instead of installing, and the framework's own
    // Magisk module directory tells them apart, so the row title can name the framework from here on
    private fun refreshFramework() = background.execute {
        val name = Framework.name(this@WelcomeActivity, askRoot = true)
        runOnUiThread {
            xposed.findViewById<TextView>(R.id.title).text = getString(R.string.check_xposed, name)
        }
    }

    // Querying Android Auto's public car connection provider starts its :provider process, where the module reports
    // in through AndroidAutoBridge. Only a report since this version of AA Mods was installed counts, so an Android
    // Auto still running older module code shows as needing a restart.
    private fun checkAndroidAuto(restart: Boolean) {
        status(androidAuto, null)
        background.execute {
            if (restart) runAsRoot("am force-stop ${Entry.GEARHEAD}")
            runCatching { contentResolver.query(CAR_CONNECTION, null, null, null, null)?.close() }
            val deadline = SystemClock.elapsedRealtime() + REPORT_WAIT_MS
            while (!loaded() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(250)
            val ok = loaded()
            runOnUiThread { status(androidAuto, ok, R.string.status_working, R.string.status_restart_aa) }
        }
    }

    private fun loaded() = AppState.reported(this)

    private fun explainFramework() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.framework_help_title, Framework.name(this)))
            .setMessage(R.string.framework_help_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // null while checking
    private fun status(row: View, ok: Boolean?, good: Int = 0, bad: Int = 0) {
        val status = row.findViewById<TextView>(R.id.status)
        status.isVisible = true
        when (ok) {
            null -> {
                status.setText(R.string.status_checking)
                status.setTextColor(MaterialColors.getColor(status, com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            true -> {
                status.setText(good)
                status.setTextColor(getColor(R.color.status_ok))
            }
            false -> {
                status.setText(bad)
                status.setTextColor(MaterialColors.getColor(status, androidx.appcompat.R.attr.colorError))
            }
        }
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        val CAR_CONNECTION: Uri = Uri.parse("content://androidx.car.app.connection")
        const val REPORT_WAIT_MS = 6000L
    }
}
