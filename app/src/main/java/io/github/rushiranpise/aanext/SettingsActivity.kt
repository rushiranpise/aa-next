package io.github.rushiranpise.aanext

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator

// The home screen: the hook report, a way into each group of settings, and the two actions that stay on
// every screen. The settings themselves live on their category screens (Category), so this stays a
// dashboard that can be read at a glance.
class SettingsActivity : SettingsScreen() {
    private lateinit var statusRow: View
    private lateinit var statusSummary: TextView
    private lateinit var statusReady: TextView
    private lateinit var statusOff: TextView
    private lateinit var statusFailed: TextView
    private lateinit var statusMeta: TextView
    private lateinit var statusProgress: LinearProgressIndicator

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (!getSharedPreferences(AppState.FILE, MODE_PRIVATE).getBoolean(AppState.WELCOME_DONE, false)) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_settings)
        prepare(R.id.content, R.id.actions)
        findViewById<CollapsingToolbarLayout>(R.id.collapsing).title = getString(R.string.app_name)
        // The list scrolls behind the two fixed actions, but keeps enough room at the end for its last
        // card to come clear of them
        val scroll = findViewById<NestedScrollView>(R.id.scroll)
        val listBottom = scroll.paddingBottom
        findViewById<View>(R.id.actions).addOnLayoutChangeListener { actions, _, _, _, _, _, _, _, _ ->
            val bottom = listBottom + actions.height
            if (actions.height > 0 && scroll.paddingBottom != bottom) scroll.updatePadding(bottom = bottom)
        }

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

        content.addView(layoutInflater.inflate(R.layout.item_home_intro, content, false))

        statusRow = layoutInflater.inflate(R.layout.item_status_hero, content, false).also {
            it.setOnClickListener { startActivity(Intent(this@SettingsActivity, StatusActivity::class.java)) }
            content.addView(it)
        }
        statusSummary = statusRow.findViewById(R.id.status_summary)
        statusReady = statusRow.findViewById(R.id.status_ready)
        statusOff = statusRow.findViewById(R.id.status_off)
        statusFailed = statusRow.findViewById(R.id.status_failed)
        statusMeta = statusRow.findViewById(R.id.status_meta)
        statusProgress = statusRow.findViewById(R.id.status_progress)
        refreshStatus()

        updateBanner = layoutInflater.inflate(R.layout.item_update_banner, content, false).also {
            it.setOnClickListener { update?.let { release -> showUpdate(release) } }
            content.addView(it)
        }

        label(R.string.home_settings)
        featured(Category.APPS)
        categoryRow(Category.DRIVING, Category.NOTIFICATIONS)
        categoryRow(Category.SCREEN, Category.SYSTEM)
        categoryRow(Category.CUSTOM, Category.ABOUT)

        checkForUpdates(offer = savedInstanceState == null)
    }

    private fun featured(category: Category) {
        val card = layoutInflater.inflate(R.layout.item_category_featured, content, false)
        bind(card, category, featured = true)
        content.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 12.dp
        })
    }

    // Two cards share a row; both are the same height because every card has the same lines of text
    private fun categoryRow(vararg categories: Category) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        categories.forEachIndexed { index, category ->
            val card = layoutInflater.inflate(R.layout.item_category, row, false)
            bind(card, category)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (index > 0) params.marginStart = 12.dp
            row.addView(card, params)
        }
        content.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 12.dp
        })
    }

    private fun bind(card: View, category: Category, featured: Boolean = false) {
        card.findViewById<ImageView>(R.id.icon).apply {
            setImageResource(category.icon)
            // The featured card already has its own tertiary tile inside a tertiary card
            if (!featured) imageTintList = ColorStateList.valueOf(MaterialColors.getColor(card, category.onContainer))
        }
        if (!featured) {
            card.findViewById<MaterialCardView>(R.id.tile)
                .setCardBackgroundColor(MaterialColors.getColor(card, category.container))
        }
        card.findViewById<TextView>(R.id.title).setText(category.title)
        card.findViewById<TextView>(R.id.summary).setText(category.summary)
        card.findViewById<TextView>(R.id.count).text = count(category)
        card.setOnClickListener {
            startActivity(Intent(this, CategoryActivity::class.java).putExtra(Category.EXTRA, category.name))
        }
    }

    private fun count(category: Category): String =
        if (category == Category.ABOUT) {
            getString(R.string.version_title, Updates.installedVersion(this))
        } else {
            resources.getQuantityString(R.plurals.settings_count, category.settings, category.settings)
        }

    private fun refreshStatus() {
        val report = AppState.status(this)
        val onContainer = MaterialColors.getColor(statusSummary, com.google.android.material.R.attr.colorOnPrimaryContainer)
        if (report == null) {
            for (number in listOf(statusReady, statusOff, statusFailed)) {
                number.text = "—"
                number.alpha = 0.55f
            }
            statusFailed.setTextColor(onContainer)
            statusSummary.setTextColor(onContainer)
            statusSummary.setText(R.string.hook_status_waiting)
            statusMeta.setText(R.string.hook_status_hint)
            statusProgress.isVisible = false
            return
        }
        val hooks = report.optJSONArray("hooks")
        var ready = 0
        var off = 0
        var failed = 0
        if (hooks != null) for (i in 0 until hooks.length()) {
            when (hooks.getJSONObject(i).optString("state")) {
                Status.READY -> ready++
                Status.OFF -> off++
                Status.FAILED -> failed++
            }
        }
        statusReady.text = ready.toString()
        statusOff.text = off.toString()
        statusFailed.text = failed.toString()
        statusReady.alpha = 1f
        statusOff.alpha = if (off > 0) 1f else 0.55f
        statusFailed.alpha = if (failed > 0) 1f else 0.55f
        statusFailed.setTextColor(
            if (failed > 0) MaterialColors.getColor(statusFailed, androidx.appcompat.R.attr.colorError) else onContainer
        )
        statusSummary.setTextColor(
            if (failed > 0) MaterialColors.getColor(statusSummary, androidx.appcompat.R.attr.colorError) else onContainer
        )
        statusSummary.text = when {
            failed > 0 -> resources.getQuantityString(R.plurals.hook_status_attention, failed, failed)
            off > 0 -> getString(R.string.hook_status_no_failures, off)
            ready > 0 -> getString(R.string.hook_status_all_good)
            else -> getString(R.string.hook_status_waiting)
        }
        statusMeta.text = getString(
            R.string.hook_status_module,
            report.optString("module"),
            report.optString("androidAuto").ifEmpty { "Android Auto" },
        )
        val total = ready + off + failed
        statusProgress.isVisible = total > 0
        if (total > 0) {
            statusProgress.max = total
            statusProgress.setProgressCompat(ready, true)
            statusProgress.setIndicatorColor(
                MaterialColors.getColor(
                    statusProgress,
                    if (failed > 0) androidx.appcompat.R.attr.colorError else androidx.appcompat.R.attr.colorPrimary,
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::statusRow.isInitialized) refreshStatus()
    }
}
