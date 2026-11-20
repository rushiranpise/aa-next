package io.github.rushiranpise.aanext

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import com.google.android.material.appbar.MaterialToolbar
import io.github.rushiranpise.aanext.headunit.HeadUnitActivity

// One category per screen: its name in the app bar, then its description and groups. The rows are the
// same builders the home uses (SettingsScreen), so moving a setting here never changes what it does.
class CategoryActivity : SettingsScreen() {
    private lateinit var category: Category
    private lateinit var toolbar: MaterialToolbar
    private var flagsSummary: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        category = Category.from(intent.getStringExtra(Category.EXTRA))
        rowContainer = category.container
        rowOnContainer = category.onContainer
        setContentView(R.layout.activity_category)
        prepare(R.id.content, R.id.actions)
        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        // The app bar names the screen; the header below keeps only the description
        toolbar.title = getString(category.title)
        header()
        when (category) {
            Category.DRIVING -> driving()
            Category.APPS -> apps()
            Category.NOTIFICATIONS -> notifications()
            Category.SCREEN -> screen()
            Category.SYSTEM -> system()
            Category.CUSTOM -> custom()
            Category.ABOUT -> about()
        }
        if (category == Category.ABOUT) checkForUpdates(offer = false)
    }

    private fun header() {
        val view = layoutInflater.inflate(R.layout.item_category_header, content, false)
        view.findViewById<TextView>(R.id.summary).setText(category.body)
        content.addView(view)
    }

    private fun driving() {
        section(R.string.group_driver_access) {
            toggle(Prefs.NO_LOCKOUTS, R.drawable.ic_local_parking, R.string.no_lockouts, R.string.no_lockouts_summary)
        }
    }

    private fun apps() {
        section(R.string.group_phone_apps) {
            toggle(Prefs.ANY_APP_LAUNCHER, R.drawable.ic_apps, R.string.any_app_launcher, R.string.any_app_launcher_summary)
            appSize()
            pinnedApps()
        }
        section(R.string.group_launcher) {
            toggle(Prefs.START_ON_LAUNCHER, R.drawable.ic_home, R.string.start_on_launcher, R.string.start_on_launcher_summary)
            toggle(Prefs.RECENTS, R.drawable.ic_recents, R.string.recents, R.string.recents_summary)
        }
        section(R.string.group_compatibility) {
            toggle(Prefs.ANY_CAR_APP, R.drawable.ic_install_mobile, R.string.any_car_app, R.string.any_car_app_summary)
            toggle(Prefs.NO_FORCED_APPS, R.drawable.ic_block, R.string.no_forced_apps, R.string.no_forced_apps_summary)
            toggle(Prefs.HIDE_NEWS_WEATHER, R.drawable.ic_visibility_off, R.string.hide_news_weather, R.string.hide_news_weather_summary)
        }
        section(R.string.group_defaults) {
            picker(Prefs.DEFAULT_NAV, R.drawable.ic_navigation, R.string.default_nav, navApps())
            picker(Prefs.DEFAULT_MEDIA, R.drawable.ic_music_note, R.string.default_media, mediaApps())
        }
    }

    private fun notifications() {
        section(R.string.group_alerts) {
            toggle(Prefs.MUTE_NOTIFICATIONS, R.drawable.ic_notifications_off, R.string.mute_notifications, R.string.mute_notifications_summary)
        }
        section(R.string.group_on_screen) {
            notificationTimeout()
        }
    }

    private fun screen() {
        section(R.string.group_phone_screen) {
            row(R.drawable.ic_smartphone, R.string.phone_screen).apply {
                findViewById<TextView>(R.id.summary).setText(R.string.phone_screen_summary)
                setOnClickListener { open(Intent(this@CategoryActivity, HeadUnitActivity::class.java)) }
            }
        }
        section(R.string.group_performance) {
            toggle(Prefs.LIGHTER, R.drawable.ic_speed, R.string.lighter, R.string.lighter_summary)
            resolution()
        }
    }

    private fun system() {
        section(R.string.group_privacy) {
            toggle(Prefs.NO_TELEMETRY, R.drawable.ic_shield, R.string.no_telemetry, R.string.no_telemetry_summary)
        }
        section(R.string.group_microg) {
            microgStatus = toggle(
                Prefs.MICROG,
                R.drawable.ic_admin_panel_settings,
                R.string.microg,
                R.string.microg_summary,
            ) { checkMicroG(prompt = true) }.findViewById(R.id.status)
        }
        section(R.string.group_advanced) {
            row(R.drawable.ic_flag, R.string.flags_hint).apply {
                flagsSummary = findViewById(R.id.summary)
                setOnClickListener { open(Intent(this@CategoryActivity, FlagsActivity::class.java)) }
            }
        }
        updateFlags()
    }

    // The flag editor keeps the same Flag__name=value lines the hook reads, so the row only has to say
    // how many are set; the count is refreshed when coming back from the editor
    private fun updateFlags() {
        val count = FlagOverrides.count(prefs.getString(Prefs.FLAGS, "").orEmpty())
        flagsSummary?.text = if (count == 0) {
            getString(R.string.flags_none)
        } else {
            resources.getQuantityString(R.plurals.flags_count, count, count)
        }
    }

    private fun custom() {
        section(R.string.group_prototype) {
            toggle(Prefs.CUSTOM_TILE, R.drawable.ic_web, R.string.custom_tile, R.string.custom_tile_summary)
            field(Prefs.CUSTOM_TILE_URL, R.drawable.ic_link, R.string.custom_tile_url, helper = null, multiline = false)
        }
    }

    private fun about() {
        section(R.string.group_app) {
            versionRow()
            row(R.drawable.ic_link, R.string.credit_aa_mods).apply {
                findViewById<TextView>(R.id.summary).setText(R.string.credit_aa_mods_summary)
                setOnClickListener {
                    open(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ImNoammm/aa-mods")))
                }
            }
            row(R.drawable.ic_coffee, R.string.buy_me_a_coffee).apply {
                findViewById<TextView>(R.id.summary).setText(R.string.buy_me_a_coffee_summary)
                setOnClickListener {
                    open(Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/rushiranpise")))
                }
            }
        }
        section(R.string.group_module) {
            row(R.drawable.ic_extension, R.string.hook_status).apply {
                findViewById<TextView>(R.id.summary).setText(R.string.about_status_summary)
                setOnClickListener { startActivity(Intent(this@CategoryActivity, StatusActivity::class.java)) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::category.isInitialized && category == Category.SYSTEM) {
            checkMicroG(prompt = false)
            updateFlags()
        }
    }
}
