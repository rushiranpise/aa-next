package io.github.rushiranpise.aanext

import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

// The flag editor: a searchable catalog of Android Auto's own switches with a control for each type,
// a section for flags added by hand, and the raw text list underneath. It writes the same
// Flag__name=value lines the hook reads (Entry.flagOverrides), so nothing about the hook changes.
class FlagsActivity : SettingsScreen() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var query: TextInputEditText
    private lateinit var resetRow: View
    private var needle = ""
    private var modifiedOnly = false

    // One row of the list, whether it comes from the catalog or was added by hand. A custom flag has no
    // default, and its title is its name, so the monospace name line is hidden for it.
    private data class Entry(
        val name: String,
        val title: String,
        val description: String?,
        val type: FlagType,
        val default: String?,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        rowContainer = com.google.android.material.R.attr.colorPrimaryContainer
        rowOnContainer = com.google.android.material.R.attr.colorOnPrimaryContainer
        setContentView(R.layout.activity_category)
        prepare(R.id.content, R.id.actions)

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        // Same header as the category screens: the app bar names the screen, the header keeps the description
        toolbar.title = getString(R.string.flags_title)

        header()
        search()
        filters()
        status = TextView(this).apply {
            setPadding(20.dp, 12.dp, 20.dp, 0)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        }
        content.addView(status)
        content.addView(TextView(this).apply {
            setText(R.string.flags_managed_note)
            setPadding(20.dp, 6.dp, 20.dp, 4.dp)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        empty = TextView(this).apply {
            setText(R.string.flags_empty)
            gravity = Gravity.CENTER
            setPadding(20.dp, 32.dp, 20.dp, 32.dp)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            isVisible = false
        }
        content.addView(empty)
        manage()
        render()
    }

    private fun header() {
        val view = layoutInflater.inflate(R.layout.item_category_header, content, false)
        view.findViewById<TextView>(R.id.summary).setText(R.string.flags_body)
        content.addView(view)
    }

    private fun search() {
        val view = layoutInflater.inflate(R.layout.item_search, content, false) as TextInputLayout
        query = view.findViewById(R.id.query)
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                needle = s?.toString().orEmpty()
                render()
            }
        })
        content.addView(view)
    }

    // All and Modified are enough here; the search already covers type, group and flag name
    private fun filters() {
        val group = ChipGroup(this).apply {
            isSingleSelection = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 8.dp }
        }
        val accent = MaterialColors.getColor(group, com.google.android.material.R.attr.colorPrimaryContainer)
        val onAccent = MaterialColors.getColor(group, com.google.android.material.R.attr.colorOnPrimaryContainer)
        val neutral = MaterialColors.getColor(group, com.google.android.material.R.attr.colorSurfaceVariant)
        val onNeutral = MaterialColors.getColor(group, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val checked = intArrayOf(android.R.attr.state_checked)
        val rest = intArrayOf()
        listOf(getString(R.string.flags_all) to false, getString(R.string.flags_modified) to true)
            .forEachIndexed { index, (text, modified) ->
                group.addView(Chip(this).apply {
                    id = View.generateViewId()
                    tag = modified
                    this.text = text
                    isCheckable = true
                    isChecked = index == 0
                    isCheckedIconVisible = false
                    chipStrokeWidth = 0f
                    chipBackgroundColor = ColorStateList(arrayOf(checked, rest), intArrayOf(accent, neutral))
                    setTextColor(ColorStateList(arrayOf(checked, rest), intArrayOf(onAccent, onNeutral)))
                })
            }
        group.setOnCheckedStateChangeListener { _, ids ->
            val chip = ids.firstOrNull()?.let { group.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            modifiedOnly = chip.tag as Boolean
            render()
        }
        content.addView(group)
    }

    // Grouped by area instead of one long alphabetical list; the custom group comes last so hand-added
    // names stay where the user expects them
    private fun render() {
        val overrides = FlagOverrides.parse(prefs.getString(Prefs.FLAGS, "").orEmpty())
        list.removeAllViews()
        var shown = 0

        FlagGroup.values().forEach { group ->
            val flags = FeatureFlag.all.filter { flag ->
                flag.group == group &&
                    (!modifiedOnly || flag.name in overrides) &&
                    matches(flag.name, flag.title, flag.description, flag.group.label, flag.type.label)
            }
            if (flags.isNotEmpty()) {
                list.addView(groupView(group.label, flags.map { it.entry() }, overrides))
                shown += flags.size
            }
        }

        val custom = overrides.keys.filter { it !in FeatureFlag.byName && matches(it) }
        if (custom.isNotEmpty()) {
            val entries = custom.map { name ->
                Entry(name, name, null, FlagOverrides.typeOf(overrides.getValue(name)), null)
            }
            list.addView(groupView(getString(R.string.flags_custom), entries, overrides))
            shown += entries.size
        }

        empty.isVisible = shown == 0
        status.text = if (overrides.isEmpty()) {
            getString(R.string.flags_none)
        } else {
            getString(
                R.string.flags_status,
                resources.getQuantityString(R.plurals.flags_count, overrides.size, overrides.size),
            )
        }
        status.setTextColor(
            MaterialColors.getColor(
                status,
                if (overrides.isEmpty()) com.google.android.material.R.attr.colorOnSurfaceVariant
                else androidx.appcompat.R.attr.colorPrimary,
            )
        )
        resetRow.isVisible = overrides.isNotEmpty()
    }

    private fun FeatureFlag.entry() = Entry(name, title, description, type, default)

    private fun matches(vararg parts: String?): Boolean =
        needle.isBlank() || parts.any { it?.contains(needle.trim(), ignoreCase = true) == true }

    private fun groupView(title: String, entries: List<Entry>, overrides: Map<String, String>): View {
        val section = layoutInflater.inflate(R.layout.item_section, list, false)
        section.findViewById<TextView>(R.id.header).text = title
        section.findViewById<MaterialCardView>(R.id.card).clipToOutline = true
        val rows = section.findViewById<LinearLayout>(R.id.rows)
        entries.forEach { rows.addView(flagRow(it, overrides[it.name])) }
        return section
    }

    private fun flagRow(entry: Entry, value: String?): View {
        val row = layoutInflater.inflate(R.layout.item_flag, list, false)
        row.findViewById<TextView>(R.id.title).text = entry.title
        row.findViewById<TextView>(R.id.name).apply {
            text = entry.name
            isVisible = entry.title != entry.name
        }
        row.findViewById<TextView>(R.id.description).apply {
            text = entry.description.orEmpty()
            isVisible = !entry.description.isNullOrEmpty()
        }

        val invalid = value != null && !FlagOverrides.accepts(entry.type, value)
        val badge = row.findViewById<TextView>(R.id.badge)
        val meta = row.findViewById<TextView>(R.id.meta)
        val accent = MaterialColors.getColor(row, com.google.android.material.R.attr.colorPrimaryContainer)
        val onAccent = MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnPrimaryContainer)
        val neutral = MaterialColors.getColor(row, com.google.android.material.R.attr.colorSurfaceVariant)
        val onNeutral = MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val error = MaterialColors.getColor(row, androidx.appcompat.R.attr.colorError)
        val onError = MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnError)
        when {
            invalid -> {
                badge.setText(R.string.flags_invalid)
                badge.backgroundTintList = ColorStateList.valueOf(error)
                badge.setTextColor(onError)
                meta.setText(R.string.flags_invalid_value)
            }
            value != null -> {
                badge.setText(R.string.flags_overridden)
                badge.backgroundTintList = ColorStateList.valueOf(accent)
                badge.setTextColor(onAccent)
                meta.text = entry.default?.let { getString(R.string.flags_meta, entry.type.label, it) }
                    ?: getString(R.string.flags_meta_custom, entry.type.label)
            }
            else -> {
                badge.setText(R.string.flags_default)
                badge.backgroundTintList = ColorStateList.valueOf(neutral)
                badge.setTextColor(onNeutral)
                meta.text = entry.default?.let { getString(R.string.flags_meta, entry.type.label, it) }.orEmpty()
            }
        }

        if (entry.type == FlagType.BOOLEAN) {
            val switch = row.findViewById<MaterialSwitch>(R.id.toggle)
            switch.isVisible = true
            switch.isChecked = (value ?: entry.default)?.toBooleanStrictOrNull() ?: false
            switch.contentDescription = entry.title
            switch.setOnCheckedChangeListener { _, checked ->
                val raw = prefs.getString(Prefs.FLAGS, "").orEmpty()
                // Turning a switch back to its stock value removes the line instead of writing a
                // redundant override, so Default means exactly that
                val next = if (entry.default != null && checked.toString() == entry.default) {
                    FlagOverrides.remove(raw, entry.name)
                } else {
                    FlagOverrides.set(raw, entry.name, checked.toString())
                }
                save(next)
            }
            row.setOnClickListener { switch.toggle() }
        } else {
            val button = row.findViewById<MaterialButton>(R.id.value)
            button.isVisible = true
            button.text = value ?: entry.default.orEmpty()
            row.setOnClickListener { edit(entry, value) }
            button.setOnClickListener { edit(entry, value) }
        }
        return row
    }

    private fun edit(entry: Entry, value: String?) {
        val input = TextInputEditText(this).apply {
            setText(value ?: entry.default.orEmpty())
            isSingleLine = true
            inputType = editorInputType(entry.type)
        }
        val layout = TextInputLayout(this).apply {
            hint = getString(R.string.flags_value)
            // TextInputLayout is a LinearLayout, so it only accepts that LayoutParams subclass
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val frame = FrameLayout(this).apply {
            setPadding(24.dp, 8.dp, 24.dp, 0)
            addView(layout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(entry.title)
            .setView(frame)
            .setPositiveButton(R.string.flags_save, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (value != null) builder.setNeutralButton(R.string.flags_reset, null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text?.toString()?.trim().orEmpty()
                if (!FlagOverrides.accepts(entry.type, text)) {
                    layout.error = getString(R.string.flags_invalid_value)
                    return@setOnClickListener
                }
                val raw = prefs.getString(Prefs.FLAGS, "").orEmpty()
                save(
                    if (entry.default != null && text == entry.default) FlagOverrides.remove(raw, entry.name)
                    else FlagOverrides.set(raw, entry.name, text)
                )
                dialog.dismiss()
            }
            if (value != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    save(FlagOverrides.remove(prefs.getString(Prefs.FLAGS, "").orEmpty(), entry.name))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun addDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_flag, null)
        val nameLayout = view.findViewById<TextInputLayout>(R.id.name_layout)
        val name = view.findViewById<TextInputEditText>(R.id.name)
        val types = view.findViewById<ChipGroup>(R.id.types)
        val valueLayout = view.findViewById<TextInputLayout>(R.id.value_layout)
        val value = view.findViewById<TextInputEditText>(R.id.value)
        var type = FlagType.BOOLEAN

        fun applyType(next: FlagType) {
            type = next
            value.inputType = editorInputType(next)
            if (next == FlagType.BOOLEAN && value.text.isNullOrBlank()) value.setText("true")
        }
        types.setOnCheckedStateChangeListener { _, ids ->
            applyType(
                when (ids.firstOrNull()) {
                    R.id.type_number -> FlagType.DOUBLE
                    R.id.type_text -> FlagType.STRING
                    else -> FlagType.BOOLEAN
                }
            )
        }
        applyType(FlagType.BOOLEAN)
        value.setText("true")

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.flags_add_title)
            .setView(view)
            .setPositiveButton(R.string.flags_add, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val flag = name.text?.toString()?.trim().orEmpty()
                val text = value.text?.toString()?.trim().orEmpty()
                if (!FlagOverrides.validName(flag)) {
                    nameLayout.error = getString(R.string.flags_name_invalid)
                    return@setOnClickListener
                }
                nameLayout.error = null
                if (!FlagOverrides.accepts(type, text)) {
                    valueLayout.error = getString(R.string.flags_invalid_value)
                    return@setOnClickListener
                }
                valueLayout.error = null
                save(FlagOverrides.set(prefs.getString(Prefs.FLAGS, "").orEmpty(), flag, text))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    // The old text field, kept as the escape hatch: anything the catalog doesn't know, comments and
    // malformed lines stay visible and editable here
    private fun rawDialog() {
        val input = TextInputEditText(this).apply {
            setText(prefs.getString(Prefs.FLAGS, "").orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
        }
        val helper = TextView(this).apply {
            setText(R.string.flags_raw_helper)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, 0, 0, 8.dp)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
            addView(helper)
            addView(
                TextInputLayout(this@FlagsActivity).apply {
                    addView(
                        input,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.flags_raw)
            .setView(column)
            .setPositiveButton(R.string.flags_save) { _, _ -> save(input.text?.toString().orEmpty()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun manage() {
        section(R.string.flags_manage) {
            row(R.drawable.ic_flag, R.string.flags_add).apply {
                findViewById<TextView>(R.id.summary).setText(R.string.flags_add_summary)
                setOnClickListener { addDialog() }
            }
            row(R.drawable.ic_terminal, R.string.flags_raw).apply {
                findViewById<TextView>(R.id.summary).setText(R.string.flags_raw_summary)
                setOnClickListener { rawDialog() }
            }
            resetRow = row(R.drawable.ic_error, R.string.flags_reset_all).apply {
                findViewById<TextView>(R.id.summary).setText(R.string.flags_reset_all_summary)
                findViewById<TextView>(R.id.title).setTextColor(
                    MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError)
                )
                findViewById<MaterialCardView>(R.id.tile).setCardBackgroundColor(
                    MaterialColors.getColor(this, com.google.android.material.R.attr.colorErrorContainer)
                )
                findViewById<ImageView>(R.id.icon).imageTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnErrorContainer)
                )
                setOnClickListener {
                    MaterialAlertDialogBuilder(this@FlagsActivity)
                        .setTitle(R.string.flags_reset_all)
                        .setMessage(R.string.flags_reset_all_message)
                        .setPositiveButton(R.string.flags_reset) { _, _ -> save("") }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private fun save(raw: String) {
        prefs.edit().putString(Prefs.FLAGS, raw).apply()
        // The row that fired this is about to be replaced; rebuilding after the touch finishes avoids
        // removing a view from inside its own callback
        list.post { render() }
        Snackbar.make(content, R.string.flags_saved, Snackbar.LENGTH_SHORT)
            .setAnchorView(R.id.actions)
            .show()
    }

    private fun editorInputType(type: FlagType) = when (type) {
        FlagType.LONG, FlagType.DOUBLE ->
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        FlagType.BOOLEAN, FlagType.STRING ->
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
}
