package uk.noammm.aamods

import android.content.ComponentName
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.Executors

private const val GEARHEAD = "com.google.android.projection.gearhead"
private val APP_SCALES = listOf(70, 80, 90, 100, 115, 130, 150)
// Android Auto's own developer setting values; "" leaves it to the car
private val RESOLUTIONS = listOf("", "720p", "480p")
// Android Auto's own heads-up timeout is 8 seconds
private val NOTIFICATION_TIMEOUTS = listOf(4000, Prefs.NOTIFICATION_TIMEOUT_DEFAULT, 15000, 30000)
private const val MICROG_DOWNLOAD = "https://github.com/MorpheApp/MicroG-RE/releases/latest"
private val MICROG_LOCATION = listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION")
// One of the permissions the microG switch's system part grants Android Auto
private const val SYSTEM_ACCESS_PROBE = "android.permission.ADD_TRUSTED_DISPLAY"

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var content: LinearLayout
    private lateinit var restart: ExtendedFloatingActionButton
    private lateinit var microgStatus: TextView
    private lateinit var version: View
    private var update: Updates.Release? = null
    private val background = Executors.newSingleThreadExecutor()
    private val network = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (!getSharedPreferences(AppState.FILE, MODE_PRIVATE).getBoolean(AppState.WELCOME_DONE, false)) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_settings)
        content = findViewById(R.id.content)
        restart = findViewById(R.id.restart)

        // The settings live in this app's own file either way: the hooks read them through the bridge
        // provider (Entry), so a framework that doesn't share the module's file, LSPosed for one, still
        // gets every change after Android Auto restarts
        prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        // Until the module is on in the framework and has reached Android Auto: then the framework either
        // shares the file (Prefs.open, Vector's way) or the hooks reported in (AppState.reported)
        if (Prefs.open(this) == null && !AppState.reported(this)) {
            findViewById<View>(R.id.not_active).apply {
                isVisible = true
                setOnClickListener {
                    startActivity(Intent(this@SettingsActivity, WelcomeActivity::class.java))
                    finish()
                }
            }
        }

        val scroll = findViewById<NestedScrollView>(R.id.scroll)
        val listBottom = scroll.paddingBottom
        val fabBottom = (restart.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            scroll.updatePadding(bottom = listBottom + navBar)
            restart.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = fabBottom + navBar }
            insets
        }
        scroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, y, _, oldY ->
            if (y > oldY) restart.shrink() else if (y < oldY) restart.extend()
        })
        restart.setOnClickListener { restartAndroidAuto() }

        section(R.string.section_driving) {
            toggle(Prefs.NO_LOCKOUTS, R.drawable.ic_local_parking, R.string.no_lockouts, R.string.no_lockouts_summary)
        }
        section(R.string.section_apps) {
            toggle(Prefs.ANY_APP_LAUNCHER, R.drawable.ic_apps, R.string.any_app_launcher, R.string.any_app_launcher_summary)
            appSize()
            pinnedApps()
            toggle(Prefs.START_ON_LAUNCHER, R.drawable.ic_home, R.string.start_on_launcher, R.string.start_on_launcher_summary)
            toggle(Prefs.RECENTS, R.drawable.ic_recents, R.string.recents, R.string.recents_summary)
            toggle(Prefs.ANY_CAR_APP, R.drawable.ic_install_mobile, R.string.any_car_app, R.string.any_car_app_summary)
            toggle(Prefs.NO_FORCED_APPS, R.drawable.ic_block, R.string.no_forced_apps, R.string.no_forced_apps_summary)
            toggle(Prefs.HIDE_NEWS_WEATHER, R.drawable.ic_visibility_off, R.string.hide_news_weather, R.string.hide_news_weather_summary)
            picker(Prefs.DEFAULT_NAV, R.drawable.ic_navigation, R.string.default_nav, navApps())
            picker(Prefs.DEFAULT_MEDIA, R.drawable.ic_music_note, R.string.default_media, mediaApps())
        }
        section(R.string.section_notifications) {
            toggle(Prefs.MUTE_NOTIFICATIONS, R.drawable.ic_notifications_off, R.string.mute_notifications, R.string.mute_notifications_summary)
            notificationTimeout()
        }
        section(R.string.section_phone_screen) {
            row(R.drawable.ic_smartphone, R.string.phone_screen).apply {
                findViewById<TextView>(R.id.summary).setText(R.string.phone_screen_summary)
                setOnClickListener { open(Intent().setClassName(packageName, "uk.noammm.aamods.headunit.HeadUnitActivity")) }
            }
        }
        section(R.string.section_performance) {
            toggle(Prefs.LIGHTER, R.drawable.ic_speed, R.string.lighter, R.string.lighter_summary)
            resolution()
        }
        section(R.string.section_privacy) {
            toggle(Prefs.NO_TELEMETRY, R.drawable.ic_shield, R.string.no_telemetry, R.string.no_telemetry_summary)
        }
        section(R.string.section_custom) {
            toggle(Prefs.CUSTOM_TILE, R.drawable.ic_web, R.string.custom_tile, R.string.custom_tile_summary)
            field(Prefs.CUSTOM_TILE_URL, R.drawable.ic_link, R.string.custom_tile_url, helper = null, multiline = false)
        }
        section(R.string.section_system) {
            microgStatus = toggle(Prefs.MICROG, R.drawable.ic_admin_panel_settings, R.string.microg, R.string.microg_summary) {
                checkMicroG(prompt = true)
            }.findViewById(R.id.status)
        }
        section(R.string.section_flags) {
            field(Prefs.FLAGS, R.drawable.ic_flag, R.string.flags_hint, R.string.flags_helper, multiline = true)
        }
        section(R.string.section_about) {
            version = row(R.drawable.ic_system_update, R.string.app_name).apply {
                findViewById<TextView>(R.id.title).text = getString(R.string.version_title, Updates.installedVersion(this@SettingsActivity))
                setOnClickListener { update?.let { showUpdate(it) } ?: checkForUpdates(offer = true) }
            }
        }
        checkForUpdates(offer = savedInstanceState == null)
    }

    // Each time AA Mods opens, like Kav: a newer release on GitHub is offered right away
    private fun checkForUpdates(offer: Boolean) {
        val summary = version.findViewById<TextView>(R.id.summary)
        summary.setText(R.string.update_checking)
        network.execute {
            val latest = runCatching { Updates.latest() }.getOrNull()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val newer = latest?.takeIf { Updates.isNewer(it.version, Updates.installedVersion(this)) }
                update = newer
                when {
                    latest == null -> summary.setText(R.string.update_failed)
                    newer == null -> summary.setText(R.string.update_latest)
                    else -> {
                        summary.text = getString(R.string.update_available, newer.version)
                        if (offer) showUpdate(newer)
                    }
                }
            }
        }
    }

    // The release notes without their install line, which is about downloading from GitHub by hand
    private fun showUpdate(release: Updates.Release) {
        val notes = release.notes.lines().filterNot { it.startsWith("**Installing**") }.joinToString("\n").replace("**", "").trim()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_title, release.version))
            .setMessage(notes)
            .setPositiveButton(R.string.update_button) { _, _ -> startUpdate(release) }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    // Without the permission to install apps this opens its settings page; Update goes on from there next time
    private fun startUpdate(release: Updates.Release) {
        if (!Updates.canInstall(this)) {
            Updates.askInstallPermission(this)
            return
        }
        val progress = LinearProgressIndicator(this).apply { max = 100 }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_downloading, release.version))
            .setView(FrameLayout(this).apply {
                setPadding(padding, padding / 2, padding, 0)
                addView(progress)
            })
            .setCancelable(false)
            .show()
        network.execute {
            val file = runCatching {
                Updates.download(this, release) { done, total ->
                    if (total > 0) runOnUiThread { progress.setProgressCompat((done * 100 / total).toInt(), true) }
                }
            }
            runOnUiThread {
                dialog.dismiss()
                file.onSuccess { Updates.install(this, it) }.onFailure {
                    Snackbar.make(content, getString(R.string.update_error, it.message ?: it.javaClass.simpleName), Snackbar.LENGTH_LONG)
                        .setAnchorView(restart)
                        .show()
                }
            }
        }
    }

    // am force-stop needs a shell or root caller; the first su call makes the root manager ask
    private fun restartAndroidAuto() {
        restart.isEnabled = false
        Executors.newSingleThreadExecutor().execute {
            val ok = runAsRoot("am force-stop $GEARHEAD")
            runOnUiThread {
                restart.isEnabled = true
                Snackbar.make(content, if (ok) R.string.restart_aa_done else R.string.restart_aa_failed, Snackbar.LENGTH_SHORT)
                    .setAnchorView(restart)
                    .show()
            }
        }
    }

    private fun section(title: Int, rows: LinearLayout.() -> Unit) {
        val view = layoutInflater.inflate(R.layout.item_section, content, false)
        view.findViewById<TextView>(R.id.header).setText(title)
        view.findViewById<MaterialCardView>(R.id.card).clipToOutline = true // keeps row ripples inside the round corners
        view.findViewById<LinearLayout>(R.id.rows).rows()
        content.addView(view)
    }

    private fun LinearLayout.row(icon: Int, title: Int): View =
        layoutInflater.inflate(R.layout.item_setting, this, false).also {
            it.findViewById<ImageView>(R.id.icon).setImageResource(icon)
            it.findViewById<TextView>(R.id.title).setText(title)
            addView(it)
        }

    private fun LinearLayout.toggle(key: String, icon: Int, title: Int, summary: Int, onEnabled: (() -> Unit)? = null): View {
        val row = row(icon, title)
        row.findViewById<TextView>(R.id.summary).setText(summary)
        val switch = row.findViewById<MaterialSwitch>(R.id.toggle).apply {
            isVisible = true
            isChecked = Prefs.on(prefs, key)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(key, on).apply()
                if (on) onEnabled?.invoke()
            }
        }
        row.setOnClickListener { switch.toggle() }
        return row
    }

    // Shows next to the microG switch whether a microG app is installed and, when the switch goes on, asks for the
    // first thing missing: microG itself, its location permission (Android Auto gets its location from it), or the
    // system access an Android Auto that didn't come preinstalled needs
    private fun checkMicroG(prompt: Boolean) = background.execute {
        val microG = installedMicroG()
        val label = microG?.let { appLabel(it.packageName) }
        val locationOff = microG != null &&
            MICROG_LOCATION.any { packageManager.checkPermission(it, microG.packageName) != PackageManager.PERMISSION_GRANTED }
        val systemAccessMissing = systemAccessMissing()
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            microgStatus.isVisible = true
            if (microG != null) {
                microgStatus.text = getString(R.string.microg_installed, microG.versionName.orEmpty())
                microgStatus.setTextColor(getColor(R.color.status_ok))
            } else {
                microgStatus.setText(R.string.microg_missing)
                microgStatus.setTextColor(MaterialColors.getColor(microgStatus, androidx.appcompat.R.attr.colorError))
            }
            if (!prompt) return@runOnUiThread
            when {
                microG == null -> ask(getString(R.string.microg_install_title), getString(R.string.microg_install_message), R.string.microg_install_button) {
                    open(Intent(Intent.ACTION_VIEW, Uri.parse(MICROG_DOWNLOAD)))
                }
                locationOff -> ask(getString(R.string.microg_location_title, label), getString(R.string.microg_location_message, label), R.string.microg_location_button) {
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", microG!!.packageName, null)))
                }
                // The system access runs as hooks in system_server, which read the module's own file. Only a
                // framework that injects the module into its own app and shares that file (Prefs.open, Vector's
                // way) can get the switch there, so on frameworks that don't, like LSPosed, it isn't offered
                systemAccessMissing && Prefs.open(this) != null ->
                    ask(getString(R.string.microg_system_title), getString(R.string.microg_system_message, Framework.name(this)), android.R.string.ok, null)
            }
        }
    }

    // The microG app Android Auto would use, by the same rule as Entry.microGPackage: a renamed one (MicroG-RE) found by
    // the GetToken service every microG keeps, or microG installed as Play Services itself on a de-Googled phone
    private fun installedMicroG(): PackageInfo? {
        @Suppress("DEPRECATION")
        val all = packageManager.getInstalledPackages(PackageManager.GET_SERVICES)
        return all.firstOrNull { it.packageName != Entry.GMS_PACKAGE && it.services?.any { s -> s.name == Entry.GMS_GET_TOKEN } == true }
            ?: all.firstOrNull { it.packageName == Entry.GMS_PACKAGE && it.services?.any { s -> s.name.startsWith("org.microg.") } == true }
    }

    // A preinstalled Android Auto has system access already. Any other gets it from the switch's system part, which
    // only runs with System Framework in scope after a reboot, and shows by Android granting it one of its permissions.
    private fun systemAccessMissing(): Boolean = try {
        packageManager.getApplicationInfo(GEARHEAD, 0).flags and ApplicationInfo.FLAG_SYSTEM == 0 &&
            packageManager.checkPermission(SYSTEM_ACCESS_PROBE, GEARHEAD) != PackageManager.PERMISSION_GRANTED
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun ask(title: String, message: String, action: Int, onAction: (() -> Unit)?) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(action) { _, _ -> onAction?.invoke() }
            .apply { if (onAction != null) setNegativeButton(R.string.later, null) }
            .show()
    }

    private fun open(intent: Intent) = try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }

    private fun LinearLayout.picker(key: String, icon: Int, title: Int, choices: List<ComponentName>) {
        val row = row(icon, title)
        val summary = row.findViewById<TextView>(R.id.summary)
        fun current() = ComponentName.unflattenFromString(prefs.getString(key, "").orEmpty())
        summary.text = label(current())
        row.setOnClickListener {
            val options = listOf<ComponentName?>(null) + choices.sortedBy { label(it).lowercase() }
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(title)
                .setSingleChoiceItems(options.map { label(it) }.toTypedArray(), options.indexOf(current()).coerceAtLeast(0)) { dialog, i ->
                    prefs.edit().putString(key, options[i]?.flattenToString().orEmpty()).apply()
                    summary.text = label(options[i])
                    dialog.dismiss()
                }
                .show()
        }
    }

    // A percentage of the size Android Auto picks for each car's screen, so one choice suits every car
    private fun LinearLayout.appSize() {
        val row = row(R.drawable.ic_zoom_in, R.string.phone_app_size)
        val summary = row.findViewById<TextView>(R.id.summary)
        fun label(percent: Int) = if (percent == 100) getString(R.string.phone_app_size_default) else "$percent%"
        summary.text = label(prefs.getInt(Prefs.PHONE_APP_SCALE, 100))
        row.setOnClickListener {
            val current = APP_SCALES.indexOf(prefs.getInt(Prefs.PHONE_APP_SCALE, 100)).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(R.string.phone_app_size)
                .setSingleChoiceItems(APP_SCALES.map { label(it) }.toTypedArray(), current) { dialog, i ->
                    prefs.edit().putInt(Prefs.PHONE_APP_SCALE, APP_SCALES[i]).apply()
                    summary.text = label(APP_SCALES[i])
                    dialog.dismiss()
                }
                .show()
        }
    }

    // The largest picture the car may get: a car offers the sizes it can show and Android Auto takes the first allowed
    private fun LinearLayout.resolution() {
        val row = row(R.drawable.ic_high_quality, R.string.video_resolution)
        val summary = row.findViewById<TextView>(R.id.summary)
        fun label(value: String) = value.ifEmpty { getString(R.string.video_resolution_car) }
        summary.text = label(prefs.getString(Prefs.VIDEO_RESOLUTION, "").orEmpty())
        row.setOnClickListener {
            val current = RESOLUTIONS.indexOf(prefs.getString(Prefs.VIDEO_RESOLUTION, "").orEmpty()).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(R.string.video_resolution)
                .setSingleChoiceItems(RESOLUTIONS.map { label(it) }.toTypedArray(), current) { dialog, i ->
                    prefs.edit().putString(Prefs.VIDEO_RESOLUTION, RESOLUTIONS[i]).apply()
                    summary.text = label(RESOLUTIONS[i])
                    dialog.dismiss()
                }
                .show()
        }
    }

    // How long Android Auto keeps a heads-up notification on screen
    private fun LinearLayout.notificationTimeout() {
        val row = row(R.drawable.ic_notifications_off, R.string.notification_timeout)
        val summary = row.findViewById<TextView>(R.id.summary)
        fun label(ms: Int) = if (ms == Prefs.NOTIFICATION_TIMEOUT_DEFAULT) {
            getString(R.string.notification_timeout_default)
        } else {
            getString(R.string.notification_timeout_seconds, ms / 1000)
        }
        summary.text = label(prefs.getInt(Prefs.NOTIFICATION_TIMEOUT, Prefs.NOTIFICATION_TIMEOUT_DEFAULT))
        row.setOnClickListener {
            val current = NOTIFICATION_TIMEOUTS
                .indexOf(prefs.getInt(Prefs.NOTIFICATION_TIMEOUT, Prefs.NOTIFICATION_TIMEOUT_DEFAULT)).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(R.string.notification_timeout)
                .setSingleChoiceItems(NOTIFICATION_TIMEOUTS.map { label(it) }.toTypedArray(), current) { dialog, i ->
                    prefs.edit().putInt(Prefs.NOTIFICATION_TIMEOUT, NOTIFICATION_TIMEOUTS[i]).apply()
                    summary.text = label(NOTIFICATION_TIMEOUTS[i])
                    dialog.dismiss()
                }
                .show()
        }
    }

    // Stored in the order the apps were ticked, which is the order they get in the launcher
    private fun LinearLayout.pinnedApps() {
        val row = row(R.drawable.ic_push_pin, R.string.pinned_apps)
        val summary = row.findViewById<TextView>(R.id.summary)
        fun pinned() = prefs.getString(Prefs.PINNED_APPS, "").orEmpty().split(',').filter { it.isNotEmpty() }
        fun describe(apps: List<String>) =
            if (apps.isEmpty()) getString(R.string.pinned_apps_none) else apps.joinToString { appLabel(it) }
        summary.text = describe(pinned())
        row.setOnClickListener {
            val apps = launcherApps().sortedBy { appLabel(it).lowercase() }
            val order = pinned().filter { it in apps }.toMutableList()
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(R.string.pinned_apps)
                .setMultiChoiceItems(apps.map { appLabel(it) }.toTypedArray(), apps.map { it in order }.toBooleanArray()) { _, i, checked ->
                    if (checked) order += apps[i] else order -= apps[i]
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    prefs.edit().putString(Prefs.PINNED_APPS, order.joinToString(",")).apply()
                    summary.text = describe(order)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun LinearLayout.field(key: String, icon: Int, hint: Int, helper: Int?, multiline: Boolean) {
        val layout = layoutInflater.inflate(R.layout.item_field, this, false) as TextInputLayout
        layout.setHint(hint)
        layout.setStartIconDrawable(icon)
        helper?.let { layout.helperText = getString(it) }
        layout.findViewById<TextInputEditText>(R.id.input).apply {
            setText(prefs.getString(key, ""))
            inputType = if (multiline) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            if (multiline) minLines = 4
            setOnFocusChangeListener { _, focused -> if (!focused) prefs.edit().putString(key, text.toString()).apply() }
        }
        addView(layout)
    }

    // Navigation apps Android Auto can show: Car App Library navigation apps and older projection apps (Maps, Waze)
    private fun navApps(): List<ComponentName> {
        val carApps = Intent("androidx.car.app.CarAppService").addCategory("androidx.car.app.category.NAVIGATION")
        val projection = Intent(Intent.ACTION_MAIN).addCategory("com.google.android.gms.car.category.CATEGORY_PROJECTION")
        return services(carApps) + services(projection).filter { it.packageName != packageName && !it.packageName.startsWith(GEARHEAD) }
    }

    private fun mediaApps() = services(Intent("android.media.browse.MediaBrowserService"))

    private fun services(intent: Intent) = packageManager.queryIntentServices(intent, 0)
        .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        .distinctBy { it.packageName }

    // What the Phone Apps tab lists
    private fun launcherApps() = packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != packageName }

    private fun label(component: ComponentName?) =
        component?.let { appLabel(it.packageName) } ?: getString(R.string.android_auto_choice)

    private fun appLabel(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    override fun onResume() {
        super.onResume()
        checkMicroG(prompt = false)
    }

    override fun onPause() {
        super.onPause()
        currentFocus?.clearFocus()
    }
}
