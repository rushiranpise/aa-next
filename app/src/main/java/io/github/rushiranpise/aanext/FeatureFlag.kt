package io.github.rushiranpise.aanext

// The type Android Auto reads a flag through. The hook converts an override to whatever the getter
// really returns (Entry.hookFlags), so this only decides which editor the settings screen shows and
// which values it accepts.
enum class FlagType(val label: String) {
    BOOLEAN("Switch"),
    LONG("Number"),
    DOUBLE("Decimal"),
    STRING("Text"),
}

enum class FlagGroup(val label: String) {
    LAYOUT("Layout & screen"),
    BACKDROP("Backdrop & cutouts"),
    NOTIFICATIONS("Notifications & status"),
    LAUNCHER("Phone Apps"),
    ASSISTANT("Assistant & suggestions"),
}

// A known Android Auto flag: its real name, the type and default of the build the catalog was taken
// from, and a short explanation. These are read by name at runtime, so a flag that has disappeared
// from a newer Android Auto simply never fires; it is not an error and the row just stays inert.
data class FeatureFlag(
    val name: String,
    val group: FlagGroup,
    val type: FlagType,
    val default: String,
    val title: String,
    val description: String,
) {
    companion object {
        // The names, types and defaults follow AA Experiments' catalog of Android Auto 17.8/18.0,
        // trimmed to the flags that are interesting on a car screen and safe to explain. The values
        // are the stock defaults from that catalog, not recommendations.
        val all = listOf(
            FeatureFlag(
                "SystemUi__widescreen_breakpoint_dp", FlagGroup.LAYOUT, FlagType.LONG, "1240",
                "Widescreen breakpoint",
                "Width in dp where Android Auto switches to its widescreen layout.",
            ),
            FeatureFlag(
                "SystemUi__semi_widescreen_breakpoint_dp", FlagGroup.LAYOUT, FlagType.LONG, "880",
                "Semi-widescreen breakpoint",
                "Width in dp where the semi-widescreen layout starts.",
            ),
            FeatureFlag(
                "SystemUi__portrait_breakpoint_dp", FlagGroup.LAYOUT, FlagType.LONG, "900",
                "Portrait breakpoint",
                "Width in dp where the portrait layout starts.",
            ),
            FeatureFlag(
                "SystemUi__short_portrait_breakpoint_dp", FlagGroup.LAYOUT, FlagType.LONG, "680",
                "Short portrait breakpoint",
                "Height in dp for the short-portrait layout.",
            ),
            FeatureFlag(
                "SystemUi__narrow_portrait_width_threshold_dp", FlagGroup.LAYOUT, FlagType.LONG, "730",
                "Narrow portrait width",
                "Width in dp for the narrow-portrait layout.",
            ),
            FeatureFlag(
                "SystemUi__horizontal_rail_canonical_breakpoint_dp", FlagGroup.LAYOUT, FlagType.LONG, "450",
                "Rail breakpoint",
                "Width in dp where the horizontal rail becomes canonical.",
            ),
            FeatureFlag(
                "SystemUi__full_maps_and_dashboard_width_threshold_dp", FlagGroup.LAYOUT, FlagType.LONG, "710",
                "Map and dashboard width",
                "Width in dp needed for a full map beside the dashboard.",
            ),
            FeatureFlag(
                "SystemUi__wide_dashboard_min_map_height_dp", FlagGroup.LAYOUT, FlagType.LONG, "400",
                "Wide dashboard map height",
                "Smallest map height in dp in the wide dashboard layout.",
            ),
            FeatureFlag(
                "SystemUi__widescreen_aspect_ratio_breakpoint", FlagGroup.LAYOUT, FlagType.DOUBLE, "1.67",
                "Widescreen aspect ratio",
                "Aspect ratio from which a display counts as widescreen.",
            ),
            FeatureFlag(
                "SystemUi__widescreen_aspect_ratio_breakpoint_for_portrait",
                FlagGroup.LAYOUT, FlagType.DOUBLE, "1.2",
                "Portrait aspect ratio",
                "Aspect-ratio breakpoint used for portrait displays.",
            ),
            FeatureFlag(
                "SystemUi__narrow_portrait_enabled_kill_switch", FlagGroup.LAYOUT, FlagType.BOOLEAN, "true",
                "Narrow portrait",
                "Allows the narrow-portrait layout. Set it to false to turn that layout off.",
            ),
            FeatureFlag(
                "SystemUi__wide_portrait_as_widescreen_enabled_kill_switch",
                FlagGroup.LAYOUT, FlagType.BOOLEAN, "true",
                "Wide portrait as widescreen",
                "Lets wide portrait displays use the widescreen layout.",
            ),
            FeatureFlag(
                "SystemUi__vertical_rail_widget_enabled", FlagGroup.LAYOUT, FlagType.BOOLEAN, "false",
                "Rail widget",
                "Shows the rail widget in more layouts, especially widescreen.",
            ),
            FeatureFlag(
                "SystemUi__vertical_rail_widget_enabled_all", FlagGroup.LAYOUT, FlagType.BOOLEAN, "false",
                "Rail widget on every display",
                "Forces the rail widget on the main display in every layout.",
            ),
            FeatureFlag(
                "SystemUi__use_compose_rail", FlagGroup.LAYOUT, FlagType.BOOLEAN, "false",
                "Compose rail",
                "Draws the rail with the newer Compose implementation instead of the legacy one.",
            ),
            FeatureFlag(
                "SystemUi__edge_to_edge_maps_enabled", FlagGroup.LAYOUT, FlagType.BOOLEAN, "false",
                "Edge-to-edge maps",
                "Lets eligible maps and apps draw edge to edge.",
            ),
            FeatureFlag(
                "Coolwalk__nav_app_border_width", FlagGroup.LAYOUT, FlagType.LONG, "0",
                "Navigation app border",
                "Border width around the navigation window. The catalog does not confirm the unit is dp.",
            ),

            FeatureFlag(
                "SystemUi__wallpaper_backdrop_enabled", FlagGroup.BACKDROP, FlagType.BOOLEAN, "false",
                "Wallpaper backdrop",
                "Draws the phone wallpaper behind the car UI.",
            ),
            FeatureFlag(
                "SystemUi__wallpaper_backdrop_threshold", FlagGroup.BACKDROP, FlagType.LONG, "20",
                "Wallpaper backdrop threshold",
                "Inset threshold for the wallpaper backdrop. The catalog does not confirm the unit.",
            ),
            FeatureFlag(
                "SystemUi__cielo_scrim_with_stable_area_cutout_enabled",
                FlagGroup.BACKDROP, FlagType.BOOLEAN, "false",
                "Scrim for display cutouts",
                "Applies Cielo scrim handling around stable-area cutouts.",
            ),
            FeatureFlag(
                "SystemUi__cielo_scrim_with_stable_area_cutout_variable_scrim_enabled",
                FlagGroup.BACKDROP, FlagType.BOOLEAN, "false",
                "Variable scrim",
                "Varies that scrim with the dashboard geometry.",
            ),
            FeatureFlag(
                "SystemUi__irregular_display_scrim_inset_diff_threshold_dp",
                FlagGroup.BACKDROP, FlagType.LONG, "40",
                "Irregular display scrim",
                "Per-side threshold in dp for scrim compensation on irregular displays.",
            ),

            FeatureFlag(
                "SystemUi__quick_settings_enabled", FlagGroup.NOTIFICATIONS, FlagType.BOOLEAN, "false",
                "Quick settings",
                "Adds theme, battery saver, Do Not Disturb and Settings to the notification centre.",
            ),
            FeatureFlag(
                "SystemUi__dual_sim_status_bar_enabled", FlagGroup.NOTIFICATIONS, FlagType.BOOLEAN, "false",
                "Dual-SIM status bar",
                "Enables dual-SIM handling in the status bar.",
            ),
            FeatureFlag(
                "SystemUi__dnd_suppress_notifications_enabled", FlagGroup.NOTIFICATIONS, FlagType.BOOLEAN, "false",
                "DND suppresses notifications",
                "Phone Do Not Disturb also suppresses heads-up and audio notifications.",
            ),
            FeatureFlag(
                "SystemUi__twin_smarts_mode", FlagGroup.NOTIFICATIONS, FlagType.LONG, "0",
                "Twin Smarts mode",
                "Set to 13007 for the Twin Smarts energy shader on notification cards.",
            ),

            FeatureFlag(
                "CradleFeature__allow_video_apps", FlagGroup.LAUNCHER, FlagType.BOOLEAN, "false",
                "Video apps in Phone Apps",
                "Lets video apps through Phone Apps eligibility.",
            ),
            FeatureFlag(
                "CradleFeature__app_controlled_immersive_mode_enabled",
                FlagGroup.LAUNCHER, FlagType.BOOLEAN, "false",
                "App-controlled immersive mode",
                "Lets a projected app control the system bars.",
            ),
            FeatureFlag(
                "CradleFeature__day_night_enabled", FlagGroup.LAUNCHER, FlagType.BOOLEAN, "false",
                "Day/night for Phone Apps",
                "Passes the car's day/night state to Phone Apps.",
            ),
            FeatureFlag(
                "CradleFeature__immersive_mode_enabled", FlagGroup.LAUNCHER, FlagType.BOOLEAN, "true",
                "Phone Apps immersive mode",
                "Master switch for Phone Apps immersive mode.",
            ),
            FeatureFlag(
                "CradleFeature__show_dpi_picker", FlagGroup.LAUNCHER, FlagType.BOOLEAN, "false",
                "DPI picker",
                "Shows the native DPI picker and allows a density override for projected apps.",
            ),

            FeatureFlag(
                "ApolloFeature__expressive_enabled", FlagGroup.ASSISTANT, FlagType.BOOLEAN, "false",
                "Expressive Assistant",
                "Enables the Expressive Assistant layout.",
            ),
            FeatureFlag(
                "ApolloFeature__no_auto_swipe_to_suggestions_kill_switch",
                FlagGroup.ASSISTANT, FlagType.BOOLEAN, "true",
                "No auto swipe to suggestions",
                "True stops the Assistant swiping to suggestion cards on its own.",
            ),
            FeatureFlag(
                "Assistant__check_all_assistant_regions_enabled", FlagGroup.ASSISTANT, FlagType.BOOLEAN, "false",
                "Assistant checks all regions",
                "Runs Assistant checks on additional car regions and displays.",
            ),
            FeatureFlag(
                "Assistant__gemini_barge_in_enabled_kill_switch", FlagGroup.ASSISTANT, FlagType.BOOLEAN, "true",
                "Gemini barge-in",
                "Allows Gemini barge-in. False blocks it through this gate.",
            ),
        )

        val byName = all.associateBy { it.name }
    }
}
