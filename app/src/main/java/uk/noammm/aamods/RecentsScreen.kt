package uk.noammm.aamods

import android.content.ComponentName
import android.content.Context
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// The recents screen on the car display: a card per open app, newest first, each with a close button, and Close all.
// Plain framework views only, since it runs inside Android Auto, which has none of this app's themes or libraries.
// context is the car screen's; strings comes from AA Mods' own package, the only one with these strings.
class RecentsScreen(
    private val context: Context,
    private val strings: Context,
    private val apps: List<ComponentName>,
    private val onOpen: (ComponentName) -> Unit,
    private val onClose: (ComponentName) -> Unit,
    private val onCloseAll: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    fun build(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BACKGROUND)
        setPadding(dp(24), dp(20), dp(24), dp(20))
        addView(header())
        addView(if (apps.isEmpty()) empty() else cards(), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    private fun header() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text(strings.getString(R.string.recents_title), 28f, TEXT, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (apps.isNotEmpty()) {
            addView(text(strings.getString(R.string.recents_close_all), 20f, TEXT, bold = true).apply {
                setPadding(dp(24), dp(12), dp(24), dp(12))
                background = rounded(CARD, dp(28))
                setOnClickListener { onCloseAll() }
            })
        }
    }

    private fun empty() = text(strings.getString(R.string.recents_empty), 22f, TEXT_DIM).apply { gravity = Gravity.CENTER }

    private fun cards() = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
            for (app in apps.asReversed()) addView(card(app), LinearLayout.LayoutParams(dp(200), MATCH_PARENT).apply { marginEnd = dp(16) })
        }, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
    }

    private fun card(app: ComponentName) = FrameLayout(context).apply {
        background = rounded(CARD, dp(24))
        setOnClickListener { onOpen(app) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ImageView(context).apply { setImageDrawable(icon(app)) }, LinearLayout.LayoutParams(dp(96), dp(96)))
            addView(text(label(app), 20f, TEXT).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(dp(12), dp(16), dp(12), 0)
            })
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(text("✕", 22f, TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(CLOSE, dp(24))
            contentDescription = strings.getString(R.string.recents_close)
            setOnClickListener { onClose(app) }
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    // The component's own label and icon (Android Auto's built-in apps such as Calendar are its services), which
    // fall back to the app's
    private fun info(app: ComponentName): ComponentInfo? = with(context.packageManager) {
        try {
            getServiceInfo(app, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            try {
                getActivityInfo(app, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    private fun icon(app: ComponentName) = info(app)?.loadIcon(context.packageManager)

    private fun label(app: ComponentName) = info(app)?.loadLabel(context.packageManager)?.toString() ?: app.packageName

    private companion object {
        // Android Auto's own dark car surfaces
        val BACKGROUND = Color.rgb(0x26, 0x26, 0x26)
        val CARD = Color.rgb(0x3A, 0x3A, 0x3C)
        val CLOSE = Color.rgb(0x52, 0x52, 0x55)
        val TEXT = Color.rgb(0xE8, 0xEA, 0xED)
        val TEXT_DIM = Color.rgb(0x9A, 0xA0, 0xA6)
    }
}
