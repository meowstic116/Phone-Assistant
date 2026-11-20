package com.phoneassistant

/** Only positive evidence of the launcher workspace can authorize a spontaneous app launch. */
internal object HomeScreenPolicy {
    private val workspace = setOf("workspace", "home_screen", "homescreen", "home_workspace")
    private val pageOrDock = setOf("hotseat", "hot_seat", "dock", "dock_view", "cell_layout", "workspace_page",
        "page_indicator", "page_indicator_dots", "home_page_indicator")
    private val otherSurfaces = setOf(
        "apps_view", "apps_list", "apps_screen", "apps_screen_view", "apps_grid", "apps_grid_view",
        "all_apps", "all_apps_view", "all_apps_container", "all_apps_container_view", "all_apps_recycler_view",
        "apps_recycler_view", "app_drawer", "app_drawer_view", "appdrawer", "overview", "overview_panel",
        "overview_actions_view", "recents", "recent_apps", "recents_view", "task_view", "search_results",
        "search_results_list", "search_result_list", "search_container_all_apps", "finder", "finder_view",
        "folder", "folder_content", "folder_name", "folder_page", "popup_container", "popup_container_with_arrow",
        "widgets_full_sheet", "widget_picker", "widget_picker_container", "widget_list", "edit_mode",
        "edit_mode_panel", "home_edit_layout", "workspace_overview", "home_option", "folder_popup",
        "launcher_overlay", "left_content", "feed_container", "discover_feed"
    )

    fun isWorkspace(ids: Set<String>): Boolean =
        ids.any(workspace::contains) && ids.any(pageOrDock::contains) && ids.none(otherSurfaces::contains)
}
