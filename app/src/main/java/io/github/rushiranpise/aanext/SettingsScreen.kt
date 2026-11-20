package io.github.rushiranpise.aanext

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.Executors

private const val GEARHEAD = "com.google.android.projection.gearhead"
// Its startup service brings Android Auto's background processes back after the force-stop that applies a change
private const val CAR_STARTUP = "$GEARHEAD/com.google.android.apps.auto.carservice.service.impl.GearheadCarStartupService"
// The public connection provider Android Auto serves for the Car App Library; anything but 0 is a live car
private const val CAR_CONNECTION = "content://androidx.car.app.connection"
private const val CAR_CONNECTION_STATE = "CarConnectionState"
// Long enough to fold a quick run of switch flips into one restart
private const val APPLY_DELAY_MS = 1000L
private val APP_SCALES = listOf(70, 80, 90, 100, 115, 130, 150)
// Android Auto's own developer setting values; "" leaves it to the car
private val RESOLUTIONS = listOf("", "720p", "480p")
// Android Auto's own heads-up timeout is 8 seconds
private val NOTIFICATION_TIMEOUTS = listOf(4000, Prefs.NOTIFICATION_TIMEOUT_DEFAULT, 15000, 30000)
private const val MICROG_DOWNLOAD = "https://github.com/MorpheApp/MicroG-RE/releases/latest"
private val MICROG_LOCATION = listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION")
// One of the permissions the microG switch's system part grants Android Auto
private const val SYSTEM_ACCESS_PROBE = "android.permission.ADD_TRUSTED_DISPLAY"

// What the home screen and every category screen share: the settings file, the row builders, the pickers,
// the two bottom actions and updates. A row therefore looks and behaves the same wherever it is shown, and
// the screens themselves only decide which rows they contain.
abstract class SettingsScreen : AppCompatActivity() {
    protected lateinit var prefs: SharedPreferences
    protected lateinit var content: LinearLayout
    protected lateinit var restartAction: MaterialButton
    protected val background = Executors.newSingleThreadExecutor()
    protected val network = Executors.newSingleThreadExecutor()
    protected var update: Updates.Release? = null
    protected var versionSummary: TextView? = null
    protected var updateBanner: View? = null
    protected var microgStatus: TextView? = null

    // Every screen writes the same settings file, so every screen's listener fires; AutoApply folds them into
    // one restart. Without it, a switch would only apply the next time Android Auto happened to be restarted.
    private val settingsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        AutoApply.schedule(applicationContext)
    }

    // The tonal pair a category's rows use; the home and the about screen keep the neutral default
    protected var rowContainer = com.google.android.material.R.attr.colorSecondaryContainer
    protected var rowOnContainer = com.google.android.material.R.attr.colorOnSecondaryContainer

    protected val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    // The two actions always sit above the navigation bar. The category lists end above them; the home
    // list scrolls behind them and keeps room at the end for its last card to clear them (SettingsActivity).
    protected fun prepare(contentId: Int, actionsId: Int) {
        // The settings live in this app's own file either way: the hooks read them through the bridge
        // provider (Entry), so a framework that doesn't share the module's file, LSPosed for one, still
        // gets every change. The hooks read the file only when Android Auto's processes start, so a change
        // also schedules the restart in AutoApply instead of waiting for the user to do it.
        prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(settingsChanged)
        content = findViewById(contentId)
        val actions = findViewById<View>(actionsId)
        val actionsBottom = actions.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(actions) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.updatePadding(bottom = actionsBottom + navBar)
            insets
        }
        restartAction = findViewById(R.id.restart_action)
        restartAction.setOnClickListener { restartAndroidAuto() }
    }

    // A section header and the card holding its rows, the one shape every settings group uses
    protected fun section(title: Int, rows: LinearLayout.() -> Unit): View {
        val view = layoutInflater.inflate(R.layout.item_section, content, false)
        view.findViewById<TextView>(R.id.header).setText(title)
        view.findViewById<MaterialCardView>(R.id.card).clipToOutline = true // keeps row ripples inside the round corners
        view.findViewById<LinearLayout>(R.id.rows).rows()
        content.addView(view)
        return view
    }

    protected fun label(title: Int): View {
        val view = layoutInflater.inflate(R.layout.item_label, content, false)
        view.findViewById<TextView>(R.id.header).setText(title)
        content.addView(view)
        return view
    }

    protected fun LinearLayout.row(icon: Int, title: Int): View =
        layoutInflater.inflate(R.layout.item_setting, this, false).also { row ->
            row.findViewById<ImageView>(R.id.icon).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(MaterialColors.getColor(row, rowOnContainer))
            }
            row.findViewById<MaterialCardView>(R.id.tile)
                .setCardBackgroundColor(MaterialColors.getColor(row, rowContainer))
            row.findViewById<TextView>(R.id.title).setText(title)
            addView(row)
        }

    protected fun LinearLayout.toggle(key: String, icon: Int, title: Int, summary: Int, onEnabled: (() -> Unit)? = null): View {
        val row = row(icon, title)
        row.findViewById<TextView>(R.id.summary).setText(summary)
        val switch = row.findViewById<MaterialSwitch>(R.id.toggle).apply {
            isVisible = true
            contentDescription = getString(title)
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
    protected fun checkMicroG(prompt: Boolean) = background.execute {
        val microG = installedMicroG()
        val label = microG?.let { appLabel(it.packageName) }
        val locationOff = microG != null &&
            MICROG_LOCATION.any { packageManager.checkPermission(it, microG.packageName) != PackageManager.PERMISSION_GRANTED }
        val systemAccessMissing = systemAccessMissing()
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            microgStatus?.let { status ->
                status.isVisible = true
                if (microG != null) {
                    status.text = getString(R.string.microg_installed, microG.versionName.orEmpty())
                    status.setTextColor(getColor(R.color.status_ok))
                } else {
                    status.setText(R.string.microg_missing)
                    status.setTextColor(MaterialColors.getColor(status, androidx.appcompat.R.attr.colorError))
                }
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

    protected fun open(intent: Intent) = try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }

    protected fun LinearLayout.picker(key: String, icon: Int, title: Int, choices: List<ComponentName>) {
        val row = row(icon, title)
        val summary = row.findViewById<TextView>(R.id.summary)
        fun current() = ComponentName.unflattenFromString(prefs.getString(key, "").orEmpty())
        summary.text = label(current())
        row.setOnClickListener {
            val options = listOf<ComponentName?>(null) + choices.sortedBy { label(it).lowercase() }
            MaterialAlertDialogBuilder(this@SettingsScreen)
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
    protected fun LinearLayout.appSize() {
        val row = row(R.drawable.ic_zoom_in, R.string.phone_app_size)
        val summary = row.findViewById<TextView>(R.id.summary)
        fun label(percent: Int) = if (percent == 100) getString(R.string.phone_app_size_default) else "$percent%"
        summary.text = label(prefs.getInt(Prefs.PHONE_APP_SCALE, 100))
        row.setOnClickListener {
            val current = APP_SCALES.indexOf(prefs.getInt(Prefs.PHONE_APP_SCALE, 100)).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this@SettingsScreen)
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
    protected fun LinearLayout.resolution() {
        val row = row(R.drawable.ic_high_quality, R.string.video_resolution)
        val summary = row.findViewById<TextView>(R.id.summary)
        fun label(value: String) = value.ifEmpty { getString(R.string.video_resolution_car) }
        summary.text = label(prefs.getString(Prefs.VIDEO_RESOLUTION, "").orEmpty())
        row.setOnClickListener {
            val current = RESOLUTIONS.indexOf(prefs.getString(Prefs.VIDEO_RESOLUTION, "").orEmpty()).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this@SettingsScreen)
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
    protected fun LinearLayout.notificationTimeout() {
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
            MaterialAlertDialogBuilder(this@SettingsScreen)
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
    protected fun LinearLayout.pinnedApps() {
        val row = row(R.drawable.ic_push_pin, R.string.pinned_apps)
        val summary = row.findViewById<TextView>(R.id.summary)
        fun pinned() = prefs.getString(Prefs.PINNED_APPS, "").orEmpty().split(',').filter { it.isNotEmpty() }
        fun describe(apps: List<String>) =
            if (apps.isEmpty()) getString(R.string.pinned_apps_none) else apps.joinToString { appLabel(it) }
        summary.text = describe(pinned())
        row.setOnClickListener {
            val apps = launcherApps().sortedBy { appLabel(it).lowercase() }
            val order = pinned().filter { it in apps }.toMutableList()
            val labels = apps.associateWith { appLabel(it) }
            // A filtered copy of apps; ticks are kept in order by package, so filtering never loses one
            val shown = apps.toMutableList()

            val view = layoutInflater.inflate(R.layout.dialog_pinned_apps, null) as LinearLayout
            val query = view.findViewById<TextInputEditText>(R.id.query)
            val list = view.findViewById<ListView>(R.id.apps)
            val adapter = object : BaseAdapter() {
                // Icons are looked up once per package; the list rebinds the same rows while scrolling
                private val icons = mutableMapOf<String, Drawable>()

                override fun getCount() = shown.size
                override fun getItem(position: Int) = shown[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = convertView ?: layoutInflater.inflate(R.layout.item_app_choice, parent, false)
                    val pkg = shown[position]
                    row.findViewById<TextView>(R.id.app_name).text = labels.getValue(pkg)
                    row.findViewById<ImageView>(R.id.app_icon).setImageDrawable(
                        icons.getOrPut(pkg) { appIcon(pkg) }
                    )
                    row.findViewById<CheckBox>(R.id.app_check).isChecked = pkg in order
                    return row
                }
            }
            list.adapter = adapter
            list.emptyView = view.findViewById(R.id.empty)
            // Tall enough to browse, capped so the dialog still fits on small screens
            val density = resources.displayMetrics.density
            view.findViewById<View>(R.id.list_frame).layoutParams.height =
                (resources.displayMetrics.heightPixels * 0.45f).toInt()
                    .coerceIn((200 * density).toInt(), (440 * density).toInt())

            fun filter(text: String) {
                val needle = text.trim()
                shown.clear()
                shown += if (needle.isEmpty()) apps else apps.filter { labels.getValue(it).contains(needle, ignoreCase = true) }
                adapter.notifyDataSetChanged()
            }
            filter("")
            query.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
            })
            list.setOnItemClickListener { _, _, position, _ ->
                val pkg = shown[position]
                if (pkg in order) order -= pkg else order += pkg
                adapter.notifyDataSetChanged()
            }

            MaterialAlertDialogBuilder(this@SettingsScreen)
                .setTitle(R.string.pinned_apps)
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    prefs.edit().putString(Prefs.PINNED_APPS, order.joinToString(",")).apply()
                    summary.text = describe(order)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    protected fun LinearLayout.field(key: String, icon: Int, hint: Int, helper: Int?, multiline: Boolean) {
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
    protected fun navApps(): List<ComponentName> {
        val carApps = Intent("androidx.car.app.CarAppService").addCategory("androidx.car.app.category.NAVIGATION")
        val projection = Intent(Intent.ACTION_MAIN).addCategory("com.google.android.gms.car.category.CATEGORY_PROJECTION")
        return services(carApps) + services(projection).filter { it.packageName != packageName && !it.packageName.startsWith(GEARHEAD) }
    }

    protected fun mediaApps() = services(Intent("android.media.browse.MediaBrowserService"))

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

    // Adaptive icons come back as drawables too, so the list can show them as they are on the phone
    private fun appIcon(pkg: String): Drawable = try {
        packageManager.getApplicationIcon(pkg)
    } catch (_: PackageManager.NameNotFoundException) {
        packageManager.defaultActivityIcon
    }

    // The About row: its summary becomes the update state, and tapping it offers the update or checks again
    protected fun LinearLayout.versionRow(): View = row(R.drawable.ic_system_update, R.string.app_name).apply {
        findViewById<TextView>(R.id.title).text = getString(R.string.version_title, Updates.installedVersion(this@SettingsScreen))
        versionSummary = findViewById<TextView>(R.id.summary).apply { setText(R.string.update_checking) }
        setOnClickListener { update?.let { showUpdate(it) } ?: checkForUpdates(offer = true) }
    }

    // Each time AA Next opens, like Kav: a newer release on GitHub is offered right away, and the home screen
    // also keeps a banner for it. A failed check only shows on the About row, not as a home banner.
    protected fun checkForUpdates(offer: Boolean) {
        versionSummary?.setText(R.string.update_checking)
        network.execute {
            val latest = runCatching { Updates.latest() }.getOrNull()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val newer = latest?.takeIf { Updates.isNewer(it.version, Updates.installedVersion(this)) }
                update = newer
                when {
                    latest == null -> versionSummary?.setText(R.string.update_failed)
                    newer == null -> versionSummary?.setText(R.string.update_latest)
                    else -> {
                        versionSummary?.text = getString(R.string.update_available, newer.version)
                        updateBanner?.let { banner ->
                            banner.isVisible = true
                            banner.findViewById<TextView>(R.id.update_title)?.text = getString(R.string.update_title, newer.version)
                            banner.findViewById<TextView>(R.id.update_summary)?.setText(R.string.update_banner_summary)
                        }
                        if (offer) showUpdate(newer)
                    }
                }
            }
        }
    }

    // The release notes without their install line, which is about downloading from GitHub by hand
    protected fun showUpdate(release: Updates.Release) {
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
                        .setAnchorView(R.id.actions)
                        .show()
                }
            }
        }
    }

    // The one bottom action: restart Android Auto so the current settings load, then open its settings
    // screen. A pending automatic restart is dropped first, since this restart covers it.
    private fun restartAndroidAuto() {
        AutoApply.cancel()
        restartAction.isEnabled = false
        background.execute {
            val ok = restartAndroidAutoProcesses()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                restartAction.isEnabled = true
                if (ok) {
                    openAndroidAuto()
                } else {
                    Snackbar.make(content, R.string.restart_aa_failed, Snackbar.LENGTH_SHORT)
                        .setAnchorView(R.id.actions)
                        .show()
                }
            }
        }
    }

    // Android Auto has no launcher screen on the phone; its settings screen is what the app itself opens
    private fun openAndroidAuto() {
        val intent = Intent().setClassName(GEARHEAD, "$GEARHEAD.companion.settings.DefaultSettingsActivity")
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(content, R.string.open_auto_failed, Snackbar.LENGTH_SHORT)
                .setAnchorView(R.id.actions)
                .show()
        }
    }

    override fun onPause() {
        super.onPause()
        currentFocus?.clearFocus()
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(settingsChanged)
        super.onDestroy()
    }
}

// Applies a settings change without making the user press Restart. Android Auto reads the settings only
// when its processes start, so the change settles for a moment and then Android Auto is force-stopped and
// its startup service is started again, which loads the hooks in the background. A restart during
// projection would end the drive, so that case is left for the next start. The restart is silent when it
// works; a toast only explains why a change is waiting or could not run.
private object AutoApply {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var context: Context
    private val restart = Runnable {
        val app = context
        Thread {
            val message = applyNow(app)
            if (message != 0) toast(app, message)
        }.start()
    }

    fun schedule(context: Context) {
        this.context = context.applicationContext
        main.removeCallbacks(restart)
        main.postDelayed(restart, APPLY_DELAY_MS)
    }

    fun cancel() = main.removeCallbacks(restart)

    private fun toast(context: Context, message: Int) {
        main.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
}

// 0 when the restart ran, otherwise the message explaining why it didn't
private fun applyNow(context: Context): Int {
    if (connected(context)) return R.string.settings_apply_later
    return if (restartAndroidAutoProcesses()) 0 else R.string.restart_aa_failed
}

// Anything but 0 from Android Auto's public connection provider means a car is attached (1 is a native
// head unit, 2 is projection), where force-stopping Android Auto would end the drive
private fun connected(context: Context) = runCatching {
    context.contentResolver.query(
        Uri.parse(CAR_CONNECTION),
        arrayOf(CAR_CONNECTION_STATE),
        null,
        null,
        null,
    )?.use { it.moveToFirst() && it.getInt(0) != 0 } ?: false
}.getOrDefault(false)

// force-stop clears the processes that read the old settings; the startup service brings the background
// ones back so the hooks are already loaded when Android Auto is next opened
private fun restartAndroidAutoProcesses(): Boolean {
    if (!runAsRoot("am force-stop $GEARHEAD")) return false
    runAsRoot("am startservice -n $CAR_STARTUP")
    return true
}
