package com.phoneassistant

/** Instagram also hosts private messages. A Reels job is not permission to read its app tree. */
internal object InstagramReelsPolicy {
    const val PACKAGE = "com.instagram.android"

    private val homeTabs = setOf("home_tab", "feed_tab", "tab_home")
    private val reelsTabs = setOf("clips_tab", "reels_tab", "tab_clips", "tab_reels")
    private val captions = setOf("clips_caption", "clips_caption_text", "clips_video_caption", "clips_caption_text_view",
        "reel_caption", "reels_caption", "caption_text", "row_feed_textview_caption")
    private val likes = setOf("clips_like_button", "reel_like_button", "row_feed_button_like")
    private val timelines = setOf("clips_video_progress_bar", "video_progress_bar", "video_seek_bar", "seek_bar", "progress_bar")
    private val adBadges = setOf("ad_label", "ad_badge", "ad_disclosure", "sponsored_label", "sponsored_badge")
    private val privateSurfaces = setOf("direct_inbox", "direct_inbox_root", "direct_thread", "direct_thread_root",
        "direct_thread_message_list", "direct_thread_message_input", "direct_message_list", "direct_message_input",
        "comments_bottom_sheet", "comment_composer", "layout_comment_thread", "layout_comment_composer",
        "clips_share_sheet", "direct_share_sheet")

    fun requested(app: String, surface: String?, bulk: Boolean) = app == PACKAGE && surface == "reels" && !bulk

    fun tab(id: String?): String? = when (id) {
        in homeTabs -> "home"
        in reelsTabs -> "reels"
        else -> null
    }

    fun viewer(id: String?, broad: Boolean) = broad && JobProgress.matchesFeedViewer(id, "reels", PACKAGE)

    fun blocked(id: String?, broad: Boolean): Boolean {
        val name = id.orEmpty()
        return name in privateSurfaces || broad &&
            (name.startsWith("direct_") || name.startsWith("inbox_") || name.startsWith("message_") ||
                name.startsWith("comments_") || name.startsWith("comment_") || name.startsWith("share_sheet"))
    }

    fun publicCaption(id: String?) = id in captions

    fun likeControl(id: String?) = id in likes
    fun adBadge(id: String?) = id in adBadges
    fun timeline(id: String?) = id in timelines

    fun reelItem(id: String?, inViewer: Boolean) = inViewer &&
        (publicCaption(id) || likeControl(id) || timeline(id) || adBadge(id))

    fun readable(blocked: Boolean, fullScan: Boolean, hasViewer: Boolean, hasReelsTab: Boolean, requireViewer: Boolean) =
        !blocked && fullScan && (hasViewer || (!requireViewer && hasReelsTab))
}
