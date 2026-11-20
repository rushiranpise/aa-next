package uk.noammm.aamods

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

// The last hook report from inside Android Auto: ready (installed), failed (the Android Auto update moved
// it) and off (its switch is off). It is stored by AndroidAutoBridge, so this screen needs neither root nor
// a running Android Auto.
class StatusActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        content = findViewById(R.id.content)
    }

    // Read again on every return, so coming back from Open Auto picks up the report it just made
    override fun onResume() {
        super.onResume()
        content.removeAllViews()
        show(AppState.status(this))
    }

    private fun show(report: JSONObject?) {
        if (report == null) {
            textCard(getString(R.string.hook_status_no_report), getString(R.string.hook_status_no_report_body))
            return
        }
        val hooks = report.optJSONArray("hooks") ?: JSONArray()
        val items = (0 until hooks.length()).mapNotNull { runCatching { hooks.getJSONObject(it) }.getOrNull() }
        val failed = items.filter { it.optString("state") == Status.FAILED }.sortedBy { it.optString("name") }
        val ready = items.filter { it.optString("state") == Status.READY }.sortedBy { it.optString("name") }
        val off = items.filter { it.optString("state") == Status.OFF }.sortedBy { it.optString("name") }
        summary(report, ready.size, off.size, failed.size)
        if (failed.isNotEmpty()) section(getString(R.string.hook_status_failed_group, failed.size), failed)
        if (ready.isNotEmpty()) section(getString(R.string.hook_status_ready_group, ready.size), ready)
        if (off.isNotEmpty()) section(getString(R.string.hook_status_off_group, off.size), off)
    }

    private fun summary(report: JSONObject, ready: Int, off: Int, failed: Int) {
        val card = card()
        val box = box()
        box.addView(TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            text = if (off > 0) {
                getString(R.string.hook_status_counts_off, ready, off, failed)
            } else {
                getString(R.string.hook_status_counts, ready, failed)
            }
            setTextColor(
                when {
                    failed > 0 -> MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError)
                    ready > 0 -> getColor(R.color.status_ok)
                    else -> MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
                }
            )
        })
        box.addView(TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, 8.dp, 0, 0)
            text = getString(
                R.string.hook_status_module,
                report.optString("module"),
                report.optString("androidAuto").ifEmpty { "Android Auto" },
            )
        })
        val at = report.optLong("at")
        if (at > 0) box.addView(TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            val process = report.optString("process")
            val name = if (process == Entry.GEARHEAD) getString(R.string.hook_status_main) else process.substringAfterLast(':')
            text = getString(
                R.string.hook_status_reported,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at)),
                name,
            )
        })
        box.addView(TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, 12.dp, 0, 0)
            setText(R.string.hook_status_explanation)
        })
        card.addView(box)
        content.addView(card)
    }

    private fun section(header: String, hooks: List<JSONObject>) {
        val view = layoutInflater.inflate(R.layout.item_section, content, false)
        view.findViewById<TextView>(R.id.header).text = header
        view.findViewById<MaterialCardView>(R.id.card).clipToOutline = true
        val rows = view.findViewById<LinearLayout>(R.id.rows)
        for (hook in hooks) rows.addView(row(hook))
        content.addView(view)
    }

    private fun row(hook: JSONObject): View {
        val row = layoutInflater.inflate(R.layout.item_setting, content, false)
        val state = hook.optString("state")
        val failed = state == Status.FAILED
        val off = state == Status.OFF
        row.findViewById<ImageView>(R.id.icon).setImageResource(
            when {
                failed -> R.drawable.ic_error
                off -> R.drawable.ic_visibility_off
                else -> R.drawable.ic_extension
            }
        )
        val name = hook.optString("name")
        row.findViewById<TextView>(R.id.title).text =
            if (name == "microG") name else name.replaceFirstChar { it.uppercase() }
        val summary = row.findViewById<TextView>(R.id.summary)
        val used = hook.optInt("used")
        val detail = hook.optString("detail")
        summary.text = when {
            failed -> detail.ifEmpty { getString(R.string.hook_status_failed) }
            off -> detail.ifEmpty { getString(R.string.hook_status_off) }
            else -> listOfNotNull(
                detail.ifEmpty { null },
                if (used > 0) resources.getQuantityString(R.plurals.hook_status_used, used, used) else null,
                if (detail.isEmpty() && used == 0) getString(R.string.hook_status_installed) else null,
            ).joinToString(" · ")
        }
        val status = row.findViewById<TextView>(R.id.status)
        status.isVisible = true
        status.setText(
            when {
                failed -> R.string.hook_status_failed
                off -> R.string.hook_status_off
                else -> R.string.hook_status_ready
            }
        )
        status.setTextColor(
            when {
                failed -> MaterialColors.getColor(status, androidx.appcompat.R.attr.colorError)
                off -> MaterialColors.getColor(status, com.google.android.material.R.attr.colorOnSurfaceVariant)
                else -> getColor(R.color.status_ok)
            }
        )
        return row
    }

    private fun textCard(title: String, body: String) {
        val card = card()
        val box = box()
        box.addView(TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            text = title
        })
        box.addView(TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, 8.dp, 0, 0)
            text = body
        })
        card.addView(box)
        content.addView(card)
    }

    private fun card() = MaterialCardView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = 8.dp }
        radius = 24.dp.toFloat()
        setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh))
    }

    private fun box() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dp, 20.dp, 20.dp, 20.dp)
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
