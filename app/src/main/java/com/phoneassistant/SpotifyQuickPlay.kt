package com.phoneassistant

import android.os.SystemClock

/** Fast path for a visible Spotify playlist. Anything ambiguous goes back to the normal runner. */
internal object SpotifyQuickPlay {
    class Result(val line: String, val reason: String)

    fun fromHome(mine: Int, title: String, opening: Boolean, alive: () -> Boolean): Result? {
        val pkg = SpotifyQuickPlan.PACKAGE
        fun read() = Screen.snapshot(feed = true, expectedApp = pkg)
        // Bounded and cancellable. Replaces fixed sleeps on this path: when the desired
        // control is already there we can use it on the first read.
        fun <T> until(ms: Long, check: () -> T?): T? {
            val deadline = SystemClock.uptimeMillis() + ms
            while (alive() && Screen.available()) {
                check()?.let { return it }
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0L) return null
                SystemClock.sleep(minOf(125L, remaining))
            }
            return null
        }
        fun hasTitleAbove(screen: Screen.Snapshot, control: Screen.Item) = screen.items.any {
            val gap = control.bounds.centerY() - it.bounds.centerY()
            !it.editable && !it.password && SpotifyQuickPlan.isTitle(it.label, title) &&
                gap in 0..(10 * control.bounds.height())
        }
        fun control(screen: Screen.Snapshot, match: (String) -> Boolean) =
            SpotifyQuickPlan.unique(screen.items) { !it.editable && !it.password && match(it.label) }
        fun detail(screen: Screen.Snapshot): Boolean {
            if (screen.items.any { SpotifyQuickPlan.isRecentHeading(it.label) }) return false
            val scoped = control(screen) { SpotifyQuickPlan.isPlay(it) || SpotifyQuickPlan.isPause(it) }
            return scoped != null && hasTitleAbove(screen, scoped)
        }
        fun home(screen: Screen.Snapshot) = screen.items.any {
            !it.editable && !it.password && (SpotifyQuickPlan.isHome(it.label, it.node.isSelected || it.node.isChecked) ||
                SpotifyQuickPlan.isRecentHeading(it.label))
        }
        fun card(screen: Screen.Snapshot) = SpotifyQuickPlan.unique(screen.items) {
            !it.editable && !it.password && SpotifyQuickPlan.isCard(it.label, title)
        }
        var screen = until(if (opening) 2_100L else 375L) {
            read()?.takeIf { detail(it) || home(it) && card(it) != null }
        } ?: return null

        if (!detail(screen)) {
            val item = card(screen) ?: return null
            if (!alive() || !Screen.targetStillVisible(screen, item) || !alive()) return null
            val pressed = Screen.tap(item)
            Diag.jobStep(mine, "open visible Spotify playlist", "${item.label}: ${if (pressed) "tap accepted" else "tap declined"}", pressed)
            if (!pressed || !alive()) return null
            screen = until(1_900L) { read()?.takeIf(::detail) } ?: return null
        }

        if (control(screen, SpotifyQuickPlan::isPause) != null && control(screen, SpotifyQuickPlan::isPlay) == null) {
            Diag.jobStep(mine, "check playlist playback", "requested title and playlist Pause visible", true)
            return Result("‘$title’ is already playing.", "Spotify showed the requested playlist playing")
        }
        val play = control(screen, SpotifyQuickPlan::isPlay) ?: return null
        // Refuse a confusing page with simultaneous playlist Play and Pause. Recheck the
        // control and the foreground before pressing; a changed screen doesn't get a tap.
        if (control(screen, SpotifyQuickPlan::isPause) != null || !alive() ||
            !Screen.targetStillVisible(screen, play) || !alive()) return null
        val pressed = Screen.tap(play)
        Diag.jobStep(mine, "press Spotify playlist play", "${play.label}: ${if (pressed) "tap accepted" else "tap declined"}", pressed)
        if (!pressed) return null
        if (!alive()) return null
        val verified = until(1_750L) {
            val next = read()?.takeIf { it.windowId == screen.windowId } ?: return@until null
            if (next.items.any { !it.editable && SpotifyQuickPlan.isPlay(it.label) }) return@until null
            val pause = control(next, SpotifyQuickPlan::isPause)
                ?: SpotifyQuickPlan.unique(next.items) {
                    !it.editable && !it.password && SpotifyQuickPlan.isTitle(it.label, "pause") &&
                        it.role == play.role && it.bounds == play.bounds && (play.id == null || play.id == it.id)
                }
            pause?.takeIf { hasTitleAbove(next, it) }?.let { true }
        } == true
        Diag.jobStep(mine, "check playlist playback", if (verified) "Play changed to Pause on the requested playlist"
            else "Spotify took the tap, but playback was not confirmed", verified)
        // A Play tap may already have worked. Don't ask the model to try again and risk
        // pausing the music just because Spotify didn't expose the new button in time.
        return if (verified) Result("Playing ‘$title’ on Spotify.", "verified Spotify playlist playback control")
        else Result("I pressed Play on ‘$title’, but Spotify didn’t confirm playback.", "Spotify play accepted; playback not verified")
    }
}
