package io.github.rushiranpise.aanext

import androidx.annotation.AttrRes

// The settings, grouped by what they are for. Each one opens its own screen, so the home screen stays a
// dashboard instead of one long list, and every screen shares the same rows and actions (SettingsScreen).
// Each group also has a tonal colour of its own, which the home card, the screen header and its rows use.
enum class Category(
    val title: Int,
    val summary: Int,
    val body: Int,
    val icon: Int,
    val settings: Int,
    @AttrRes val container: Int,
    @AttrRes val onContainer: Int,
) {
    DRIVING(
        R.string.category_driving,
        R.string.category_driving_summary,
        R.string.category_driving_body,
        R.drawable.ic_local_parking,
        1,
        com.google.android.material.R.attr.colorPrimaryContainer,
        com.google.android.material.R.attr.colorOnPrimaryContainer,
    ),
    APPS(
        R.string.category_apps,
        R.string.category_apps_summary,
        R.string.category_apps_body,
        R.drawable.ic_apps,
        10,
        com.google.android.material.R.attr.colorTertiaryContainer,
        com.google.android.material.R.attr.colorOnTertiaryContainer,
    ),
    NOTIFICATIONS(
        R.string.category_notifications,
        R.string.category_notifications_summary,
        R.string.category_notifications_body,
        R.drawable.ic_notifications_off,
        2,
        com.google.android.material.R.attr.colorSecondaryContainer,
        com.google.android.material.R.attr.colorOnSecondaryContainer,
    ),
    SCREEN(
        R.string.category_screen,
        R.string.category_screen_summary,
        R.string.category_screen_body,
        R.drawable.ic_smartphone,
        3,
        com.google.android.material.R.attr.colorTertiaryContainer,
        com.google.android.material.R.attr.colorOnTertiaryContainer,
    ),
    SYSTEM(
        R.string.category_system,
        R.string.category_system_summary,
        R.string.category_system_body,
        R.drawable.ic_admin_panel_settings,
        3,
        com.google.android.material.R.attr.colorPrimaryContainer,
        com.google.android.material.R.attr.colorOnPrimaryContainer,
    ),
    CUSTOM(
        R.string.category_custom,
        R.string.category_custom_summary,
        R.string.category_custom_body,
        R.drawable.ic_web,
        2,
        com.google.android.material.R.attr.colorSecondaryContainer,
        com.google.android.material.R.attr.colorOnSecondaryContainer,
    ),
    ABOUT(
        R.string.category_about,
        R.string.category_about_summary,
        R.string.category_about_body,
        R.drawable.ic_system_update,
        2,
        com.google.android.material.R.attr.colorPrimaryContainer,
        com.google.android.material.R.attr.colorOnPrimaryContainer,
    ),
    ;

    companion object {
        const val EXTRA = "category"

        fun from(name: String?): Category = values().firstOrNull { it.name == name } ?: DRIVING
    }
}
