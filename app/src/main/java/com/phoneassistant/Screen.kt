package com.phoneassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The phone under Pip's hands: reading the app in front as a short list of usable elements,
 * and acting on them.
 *
 * All of this goes through the accessibility service, which is the only API that can see
 * another app's screen and press its buttons. Reading is limited to the window in front, and
 * happens only while a job is running — nothing is kept afterwards, and the only thing that
 * leaves the phone is the list of visible labels sent to the model that was asked to choose
 * a step.
 *
 * Password fields are listed so the model can see they exist, and refused if it tries to
 * type into one. That refusal is here, at the point of typing, rather than only in the
 * instructions: a suggestion is not a guarantee. The job runner has no vault access.
 */
object Screen {

    /** One usable thing on the screen, numbered the way the model is shown it. */
    class Item(
        var index: Int,
        val role: String,
        val label: String,
        val hint: String,
        val editable: Boolean,
        val password: Boolean,
        /** Whether a text field already holds something. Its contents are never kept. */
        val filled: Boolean,
        val scrollable: Boolean,
        val id: String?,
        val bounds: Rect,
        val node: AccessibilityNodeInfo
    )

    /**
     * The screen, read once: what it is, what is on it, and the listing for the model.
     *
     * A value, used and then dropped. The nodes it holds are never handed back to the
     * framework: a root is the window's own node rather than one asked of it, and returning
     * those was followed by a job that opened an app and then could not read it at all. They
     * are left to be collected instead.
     */
    class Snapshot(val app: String, val windowId: Int, val items: List<Item>, val listing: String)

    @Volatile
    private var service: AccessibilityService? = null

    fun connect(service: AccessibilityService) {
        this.service = service
    }

    fun disconnect(service: AccessibilityService) {
        if (this.service === service) this.service = null
    }

    /** Whether the accessibility service is actually bound, not just switched on. */
    fun available(): Boolean = service != null

    // -------------------------------------------------------------------- reading

    fun snapshot(web: Boolean = false, feed: Boolean = false): Snapshot? {
        val svc = service ?: return null
        // No window at all and a framework that refused are the same thing to a caller — both
        // are a screen it cannot read — but only one of them is normal, and the record says
        // which one happened.
        val root = try {
            svc.rootInActiveWindow
        } catch (t: Throwable) {
            Diag.caught("screen read", t)
            return null
        } ?: return null
        // Read before the walk: the walk gives its nodes back as it goes, the root included.
        val app = root.packageName?.toString()?.takeIf { it.isNotBlank() && it != svc.packageName }
            ?: return null
        val windowId = root.windowId
        val items = ArrayList<Item>()
        val localFeed = feed
        val limit = if (web || localFeed) MAX_NODES else MAX_ITEMS
        collect(svc.packageName?.toString(), root, items, limit, localFeed)
        val visible = if (web) {
            // Reserve space for labelled controls nested below the shallow browser chrome.
            val topFirst = compareBy<Item>({ it.bounds.top }, { it.bounds.left })
            val ordered = items.sortedWith(topFirst)
            val controls = ordered.filter { it.label.isNotBlank() && it.node.isClickable }
                .take(MAX_ITEMS * 2 / 3)
            (controls + ordered.filter { it.label.isNotBlank() || it.scrollable } + ordered)
                .distinct().take(MAX_ITEMS).sortedWith(topFirst)
                .also { list -> list.forEachIndexed { i, item -> item.index = i + 1 } }
        } else items
        return Snapshot(app, windowId, visible, if (localFeed) "" else listing(visible, web))
    }

    /**
     * Walks the tree in reading order. Our own overlay windows are skipped: Pip's bubble and
     * his panel are not things he should be pressing.
     */
    private fun collect(
        ownPackage: String?, root: AccessibilityNodeInfo, out: MutableList<Item>, limit: Int, feed: Boolean
    ) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && out.size < limit && seen < MAX_NODES) {
            val node = queue.removeFirst()
            seen++
            // His own windows are skipped whole: a child of one cannot be pressed either.
            if (ownPackage != null && node.packageName?.toString() == ownPackage) {
                if (node !== root) hand(node)
                continue
            }
            val kept = node.isVisibleToUser && consider(node, out, feed)
            // The children are read before the node goes back: a child is its own instance, so
            // giving the parent back now cannot take one of them with it.
            // Cap queued descendants as well as processed nodes; a large tree must not
            // allocate unbounded Binder-backed nodes before the outer limit is reached.
            val children = node.childCount.coerceAtMost(MAX_NODES - seen - queue.size)
            for (child in 0 until children) {
                node.getChild(child)?.let { queue.add(it) }
            }
            // The root is the window rather than a node asked of it, so it is left alone even
            // when nothing on it was usable.
            if (!kept && node !== root) hand(node)
        }
        // A walk that stopped at the item or node ceiling still has what it had queued: those
        // were asked of the framework like any other, and go back the same way.
        while (queue.isNotEmpty()) hand(queue.removeFirst())
    }

    /**
     * Gives back a node that was asked of the framework and not kept — a container walked for
     * its children, an ancestor tried on the way up a press. Only those: a read's root and the
     * nodes it turned into items are left for the framework to collect, because handing them
     * back has cost a read before.
     */
    @Suppress("DEPRECATION") // Below API 33 nodes come from a pool and have to go back into it.
    private fun hand(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT >= 33) return
        runCatching { node.recycle() }
    }

    /** Whether this node became an item. One that did is kept until its snapshot is released. */
    private fun consider(node: AccessibilityNodeInfo, out: MutableList<Item>, feed: Boolean): Boolean {
        val editable = node.isEditable
        // What a field contains is never put in the listing, and the listing is the one thing
        // that leaves the phone: a username, a search for something private, or a password in
        // a field the app did not flag. Only the field's own label, whether it has anything in
        // it, and the app's placeholder go out — never the text.
        val label = nodeLabel(node)
        val filled = editable && !node.text.isNullOrEmpty()
        val scrollable = node.isScrollable
        // Everything that can be pressed, typed into or scrolled, plus plain text: the text
        // is how the model knows it is looking at the right conversation or message.
        val id = node.viewIdResourceName?.substringAfterLast('/')
        val feedViewer = feed && (JobProgress.matchesFeedViewer(id, "reels", node.packageName?.toString()) ||
            JobProgress.matchesFeedViewer(id, "shorts"))
        if (!editable && !node.isClickable && !node.isCheckable && !scrollable && label.isEmpty() && !feedViewer &&
            !(feed && node.rangeInfo != null)) {
            return false
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val role = node.className?.toString()?.substringAfterLast('.')?.take(24) ?: "View"
        out += Item(
            index = out.size + 1,
            role = role,
            label = label,
            hint = node.hintText?.takeUnless { node.isPassword }?.toString()?.replace(WHITESPACE, " ")?.trim()
                ?.take(HINT_MAX).orEmpty(),
            editable = editable,
            password = node.isPassword,
            filled = filled,
            scrollable = scrollable,
            id = id,
            bounds = bounds,
            node = node
        )
        return true
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        if (node.isPassword) return "Password field"
        return (if (node.isEditable) node.contentDescription else node.text ?: node.contentDescription)
            ?.toString()?.replace(WHITESPACE, " ")?.trim()?.take(LABEL_MAX).orEmpty()
    }

    internal fun sameWindow(screen: Snapshot): Boolean = runCatching {
        val root = service?.rootInActiveWindow ?: return false
        root.windowId == screen.windowId && root.packageName?.toString() == screen.app
    }.getOrDefault(false)

    /** Models can take seconds. IDs and coordinates from an old page must not be reused. */
    internal fun targetStillVisible(screen: Snapshot, item: Item): Boolean = runCatching {
        val node = item.node
        sameWindow(screen) && node.refresh() && node.isVisibleToUser && node.isEnabled &&
            node.windowId == screen.windowId && node.packageName?.toString() == screen.app &&
            node.isEditable == item.editable && node.isPassword == item.password &&
            nodeLabel(node) == item.label && Rect().also(node::getBoundsInScreen) == item.bounds
    }.getOrDefault(false)

    private fun listing(items: List<Item>, web: Boolean): String {
        if (items.isEmpty()) return "(nothing usable — a picture, a game, or still loading)"
        return buildString {
            items.forEach { item ->
                append(item.index).append(". [").append(item.role).append("] ")
                when {
                    item.label.isNotEmpty() -> append('"').append(item.label).append('"')
                    item.id != null -> append("id/").append(item.id)
                    else -> append("(no label)")
                }
                if (item.editable) {
                    append(if (item.filled) " · text field, has text" else " · text field, empty")
                }
                if (item.password) append(" · password, never type into it")
                else if (item.scrollable) append(" · scrollable")
                if (web && item.node.isClickable && !item.editable) append(" · can tap")
                append('\n')
            }
        }.trimEnd()
    }

    /** The app's name, for the model to recognise; the package is the fallback. */
    fun appLabel(ctx: Context, packageName: String): String? {
        if (packageName.isBlank()) return null
        return runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
    }

    // -------------------------------------------------------------------- acting

    /**
     * Tries [action] on [start], then on its ancestors, [hops] of them at most — the one thing
     * all three walks up the tree do. True when something took the action.
     *
     * Only the ancestors are given back: they are the one kind of node asked of the framework
     * and dropped again in the same breath. [start] belongs to a read, or is the window's own
     * root, and is left alone either way. The parent is read before the node it came from is
     * handed back, so no step of the walk looks through a node that has already gone.
     */
    private fun actUpwards(
        start: AccessibilityNodeInfo,
        action: Int,
        hops: Int,
        fits: (AccessibilityNodeInfo) -> Boolean
    ): Boolean {
        var node = start
        var climbed = 0
        while (climbed < hops) {
            climbed++
            if (fits(node) && runCatching { node.performAction(action) }.getOrDefault(false)) {
                if (node !== start) hand(node)
                return true
            }
            val parent = runCatching { node.parent }.getOrNull() ?: break
            if (node !== start) hand(node)
            node = parent
        }
        if (node !== start) hand(node)
        return false
    }

    /**
     * Presses the element. The label is usually a child of the row that takes the press, so
     * a clickable ancestor is tried before falling back to a real tap on the element's
     * middle — which is also what gets through for windows that report nothing clickable.
     */
    fun tap(item: Item): Boolean = accessibilityTap(item) || touch(item)

    fun accessibilityTap(item: Item): Boolean =
        actUpwards(item.node, AccessibilityNodeInfo.ACTION_CLICK, TAP_ANCESTORS) {
            it.isClickable
        }

    fun touch(item: Item): Boolean = gestureTap(
        item.bounds.centerX().toFloat(), item.bounds.centerY().toFloat()
    )

    internal fun reactionState(item: Item, reaction: JobProgress.Reaction): JobProgress.ReactionState {
        val node = item.node
        if (node.isEditable || node.isPassword) return JobProgress.ReactionState.UNKNOWN
        val state = if (Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString().orEmpty() else ""
        val result = JobProgress.reactionState(
            reaction, node.contentDescription?.toString().orEmpty(), node.text?.toString().orEmpty(), state,
            checked = when {
                node.isSelected -> true
                node.isCheckable -> node.isChecked
                else -> null
            }
        )
        return if (result == JobProgress.ReactionState.READY && (!node.isEnabled || !node.isClickable)) {
            JobProgress.ReactionState.UNKNOWN
        } else result
    }

    internal enum class ReactionPress { STALE, DECLINED, ACCEPTED }

    /** Re-read the same control before acting, with no coordinate or parent fallback. */
    internal fun pressReaction(
        item: Item, screen: Snapshot, reaction: JobProgress.Reaction, active: () -> Boolean
    ): ReactionPress {
        if (!targetStillVisible(screen, item) || reactionState(item, reaction) != JobProgress.ReactionState.READY || !active()) {
            return ReactionPress.STALE
        }
        return if (runCatching { item.node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
            ReactionPress.ACCEPTED
        } else ReactionPress.DECLINED
    }

    internal fun isFeedTab(item: Item, surface: String): Boolean = !item.editable && !item.password &&
        listOf(item.node.text, item.node.contentDescription).any {
            JobProgress.matchesFeedTab(it?.toString().orEmpty(), surface)
        }

    internal fun isSelectedFeedTab(item: Item, surface: String): Boolean =
        isFeedTab(item, surface) && (item.node.isSelected || item.node.isChecked ||
            listOf(item.node.text, item.node.contentDescription).any {
                JobProgress.selectedFeedTab(it?.toString().orEmpty(), surface)
            })

    internal fun pressFeedTab(item: Item, screen: Snapshot, surface: String): Boolean = runCatching {
        targetStillVisible(screen, item) && item.node.isClickable && isFeedTab(item, surface) &&
            item.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }.getOrDefault(false)

    internal fun playback(item: Item): JobProgress.Playback? {
        if (item.editable || item.password) return null
        val id = item.id.orEmpty()
        val relevantId = id.contains("progress") && (id.contains("playback") || id.contains("video") || id.contains("reel"))
        val timeline = item.role == "SeekBar" || relevantId
        val labels = listOf(item.node.contentDescription, item.node.text)
        labels.firstNotNullOfOrNull { JobProgress.playbackLabel(it?.toString().orEmpty(), timeline) }?.let { return it }
        val described = labels.any { JobProgress.normalize(it?.toString().orEmpty()) in
            setOf("reel progress", "video progress", "playback progress", "playback position") }
        if (!relevantId && !described) return null
        val range = item.node.rangeInfo ?: return null
        if (!range.current.isFinite() || !range.min.isFinite() || !range.max.isFinite() || range.max <= range.min) return null
        return JobProgress.Playback(((range.current - range.min) / (range.max - range.min)).coerceIn(0f, 1f))
    }

    internal fun feedScroller(screen: Snapshot): Item? = screen.items.filter { it.scrollable }
        .maxByOrNull { it.bounds.width().toLong() * it.bounds.height() }

    internal class FeedScrollProbe(val app: String, val windowId: Int) {
        val motion = JobProgress.FeedMotion()
        val started = SystemClock.uptimeMillis()
    }

    @Volatile private var feedProbe: FeedScrollProbe? = null

    internal fun startFeedScrollProbe(screen: Snapshot) = FeedScrollProbe(screen.app, screen.windowId)
        .also { feedProbe = it }

    internal fun endFeedScrollProbe(probe: FeedScrollProbe) {
        if (feedProbe === probe) feedProbe = null
    }

    internal fun recordFeedScroll(event: AccessibilityEvent) {
        val probe = feedProbe ?: return
        if (Build.VERSION.SDK_INT < 28 || event.eventTime < probe.started ||
            event.windowId != probe.windowId || event.packageName?.toString() != probe.app) return
        probe.motion.observe(event.scrollDeltaX, event.scrollDeltaY)
    }

    internal fun swipeFeed(screen: Snapshot, shortVideo: Boolean, retry: Int, active: () -> Boolean): Boolean {
        if (!active()) return false
        val svc = service ?: return false
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return false
        if (root.windowId != screen.windowId || root.packageName?.toString() != screen.app) return false
        val metrics = svc.resources.displayMetrics
        val area = Rect().also(root::getBoundsInScreen)
        if (!area.intersect(0, 0, metrics.widthPixels, metrics.heightPixels) ||
            area.width() < metrics.widthPixels * 0.45f || area.height() < metrics.heightPixels * 0.45f) return false
        val swipe = JobProgress.feedSwipe(shortVideo, retry)
        val x = area.left + area.width() * swipe.x
        val from = area.top + area.height() * swipe.fromY
        val to = area.top + area.height() * swipe.toY
        val path = Path().apply { moveTo(x, from); lineTo(x, to) }
        return dispatch(path, swipe.durationMs, x, from, to, active, expected = screen)
    }

    fun longPress(item: Item): Boolean =
        actUpwards(item.node, AccessibilityNodeInfo.ACTION_LONG_CLICK, TAP_ANCESTORS) {
            it.isLongClickable
        } || gestureLongPress(item.bounds.centerX().toFloat(), item.bounds.centerY().toFloat())

    /** Fill a fresh, non-password field only. */
    fun type(item: Item, text: String): Boolean {
        val node = item.node
        if (item.password || !item.editable || node.isPassword || !node.isEditable) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching {
            val before = node.text?.toString()
            if (before == text || !node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
            repeat(3) {
                if (gestureGuard.get()?.active?.invoke() == false) return false
                if (node.refresh() && !node.isPassword && node.text?.toString() == text) return true
                android.os.SystemClock.sleep(150L)
            }
            false
        }.getOrDefault(false)
    }

    /**
     * Scrolls the given element, its nearest scrollable parent, or the screen itself.
     *
     * With no element the walk starts at the window root. That root is left with the framework
     * like the one a read keeps: it is the window, not a node asked of it.
     */
    fun scroll(forward: Boolean, item: Item?): Boolean {
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val start = item?.node
            ?: runCatching { service?.rootInActiveWindow }.getOrNull()
            ?: return gestureScroll(forward)
        return actUpwards(start, action, SCROLL_ANCESTORS) { it.isScrollable } ||
            gestureScroll(forward)
    }

    fun back(): Boolean = global(AccessibilityService.GLOBAL_ACTION_BACK)

    fun home(): Boolean = global(AccessibilityService.GLOBAL_ACTION_HOME)

    fun recents(): Boolean = global(AccessibilityService.GLOBAL_ACTION_RECENTS)

    fun notifications(): Boolean = global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)

    private fun global(action: Int): Boolean =
        runCatching { service?.performGlobalAction(action) == true }.getOrDefault(false)

    /** An app on the phone, as a job can name it. */
    class AppRef(val label: String, val packageName: String, val activity: String)

    /**
     * Every app with a launcher icon, by label. Read once at the start of a job and named in
     * each step, so the model can only ask to open something that is really installed — and
     * so "open Messages" reaches the phone's own Messages rather than a guess.
     *
     * Pip's own app is left out: it is not a place a job can do anything.
     */
    fun apps(ctx: Context): List<AppRef> {
        val pm = ctx.packageManager
        val start = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION") // The flags overload is the one that works from API 26.
        val found = runCatching { pm.queryIntentActivities(start, 0) }.getOrDefault(emptyList())
        return found.mapNotNull { info ->
            val activity = info.activityInfo
            if (activity.packageName == ctx.packageName) return@mapNotNull null
            val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()
                ?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            AppRef(label, activity.packageName, activity.name)
        }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * The labels of those apps, comma-separated, for the prompt. Apps the request already
     * names come first, so a long list cut to a readable length can never lose the one app the
     * job is about to the alphabet.
     */
    fun appNames(apps: List<AppRef>, request: String = "", limit: Int = 60): String {
        val relevant = appsInRequest(apps, request)
        return (relevant + apps).distinctBy { it.packageName }.take(limit)
            .joinToString(", ") { it.label }
    }

    /**
     * Every app a request names, as whole words: "text Sam on WhatsApp" names WhatsApp, and
     * word boundaries keep "gallery" out of "gallerystuff" and out of a word that merely
     * contains it.
     */
    fun appsInRequest(apps: List<AppRef>, request: String): List<AppRef> {
        val text = request.lowercase()
        return apps
            .filter { it.label.length >= 3 }
            .filter { app -> mentions(text, app.label.lowercase()) }
            .sortedByDescending { it.label.length }
    }

    /**
     * Whether [text] says [word] as a whole word. Written as a scan rather than a pattern: this
     * runs for every installed app on every step of every job, and a regex compiled per app on
     * that path is work paid between steps for an answer that cannot change.
     */
    private fun mentions(text: String, word: String): Boolean {
        var from = 0
        while (from <= text.length - word.length) {
            val at = text.indexOf(word, from)
            if (at < 0) return false
            val before = text.getOrNull(at - 1)
            val after = text.getOrNull(at + word.length)
            if ((before == null || !before.isLetterOrDigit()) &&
                (after == null || !after.isLetterOrDigit())
            ) {
                return true
            }
            from = at + 1
        }
        return false
    }

    /**
     * The one app a request names, and only when it names exactly one.
     *
     * A request that names two is a request that moves between them, and opening either before
     * the first step puts a screen in front that the task did not ask for. "Check Discord
     * messages and then open Strava" used to open Messages: the word is in the sentence, and
     * that label is longer than Discord's, so the longest match won.
     */
    fun appInRequest(apps: List<AppRef>, request: String): AppRef? =
        appsInRequest(apps, request).singleOrNull()

    internal fun defaultCommunicationPackage(ctx: Context, kind: JobProgress.CommunicationKind): String? = runCatching {
        when (kind) {
            JobProgress.CommunicationKind.MESSAGE -> Telephony.Sms.getDefaultSmsPackage(ctx)
            JobProgress.CommunicationKind.CALL -> ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }?.takeIf { it.isNotBlank() && it != ctx.packageName }
    }.getOrNull()

    internal fun openCommunication(ctx: Context, pkg: String, kind: JobProgress.CommunicationKind, app: AppRef?): Boolean {
        if (app != null) return open(ctx, app)
        // A role holder without a launcher icon can still expose the standard entry point.
        val entry = when (kind) {
            JobProgress.CommunicationKind.MESSAGE -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
            JobProgress.CommunicationKind.CALL -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
        }.setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(entry); true }.getOrDefault(false)
    }

    /** Launches an app, straight to the activity that owns its icon. */
    fun open(ctx: Context, app: AppRef): Boolean {
        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.packageName, app.activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching {
            ctx.startActivity(launch)
            true
        }.getOrDefault(false)
    }

    /**
     * Opens an app by the name the model used. Exact label first, then a prefix, then
     * anything containing it, then the package name — "messages" should reach Messages and
     * not something with the word buried in a longer title.
     */
    fun findApp(all: List<AppRef>, query: String): AppRef? {
        val wanted = query.trim().lowercase()
        if (wanted.isEmpty()) return null
        return all.firstOrNull { it.label.lowercase() == wanted }
            ?: all.firstOrNull { it.label.lowercase().startsWith(wanted) }
            ?: all.firstOrNull { it.label.lowercase().contains(wanted) }
            ?: all.firstOrNull { it.packageName.lowercase().contains(wanted) }
    }

    /**
     * Hands a web search to the phone's browser: whichever one the user has made their own.
     *
     * A search is a URL, so this is one action instead of driving a browser by hand — and a
     * browser's search box is exactly the thing that cannot be driven, because putting text
     * in it needs an enter key that the accessibility service has no way to press. Typed in
     * by hand the field fills, the page never changes, and the job has spent four steps to
     * get nowhere. [images] lands on the pictures rather than the results page.
     */
    fun search(ctx: Context, query: String, images: Boolean): Boolean {
        val url = buildString {
            append("https://www.google.com/search?q=").append(Uri.encode(query))
            if (images) append("&tbm=isch")
        }
        return openUrl(ctx, url)
    }

    internal fun openUrl(ctx: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // ACTION_VIEW is what "the default browser" means: the user's choice, not one guessed
        // from the list of installed apps. With nothing set to answer it, nothing opens and
        // the step says so rather than pretending it searched.
        return runCatching {
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * Whether an app is one that opens web pages. A request that says "the internet" names one
     * of them, but a web page is opened by the phone's **default** browser, not by whichever
     * app the request happened to name — so launching that one first would put the wrong
     * browser in front of a job that is about to use the right one.
     */
    fun opensWebPages(ctx: Context, packageName: String): Boolean {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        @Suppress("DEPRECATION") // The flags overload is the one that works from API 26.
        val found = runCatching { ctx.packageManager.queryIntentActivities(probe, 0) }
            .getOrDefault(emptyList())
        return found.any { it.activityInfo.packageName == packageName }
    }

    /**
     * What the service says is in front, for the record, when the normal read finds nothing.
     *
     * Taken from the window list rather than from the active window, because the active window
     * is the thing that just came back empty — the list can still say which app, keyboard or
     * overlay was there. Read-only: nothing is acted on from this, and its root is not given
     * back either.
     */
    fun frontWindow(): String {
        val svc = service ?: return "no accessibility service"
        return runCatching {
            val windows = svc.windows
            if (windows.isEmpty()) return@runCatching "no windows on the display"
            val window = windows.firstOrNull { it.isFocused }
                ?: windows.firstOrNull { it.isActive }
                ?: return@runCatching "${windows.size} windows, none focused or active"
            val app = runCatching { window.root?.packageName?.toString() }.getOrNull()
                ?: "unknown app"
            val kind = when (window.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard"
                AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
                else -> "window type ${window.type}"
            }
            "$app ($kind)"
        }.getOrElse { "window list threw ${it.javaClass.simpleName}" }
    }

    /** The package of the window in front: the app a snapshot would read. */
    fun inFront(): String? {
        val root = runCatching { service?.rootInActiveWindow }.getOrNull() ?: return null
        // Not recycled: this is a window root, which is the kind of node that is left with the
        // framework. It is asked on a poll while a login is waited on, and asked again a moment
        // before an app is opened — a bad place to hand back something still in use.
        return runCatching { root.packageName?.toString() }.getOrNull()
    }

    // ------------------------------------------------------------------ gestures

    private class GestureGuard(val active: () -> Boolean, val overlay: (Rect?) -> Boolean)
    private val gestureGuard = ThreadLocal<GestureGuard>()

    // Only the job's worker can inject raw touches. Direct accessibility actions don't use this.
    internal fun withGestureGuard(active: () -> Boolean, overlay: (Rect?) -> Boolean, block: () -> Unit) {
        gestureGuard.set(GestureGuard(active, overlay))
        try {
            block()
        } finally {
            gestureGuard.remove()
        }
    }

    private fun gestureTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 60L, x, y, y)
    }

    private fun gestureLongPress(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 550L, x, y, y)
    }

    private fun gestureScroll(forward: Boolean): Boolean {
        val metrics = service?.resources?.displayMetrics ?: return false
        val x = metrics.widthPixels * 0.5f
        val middle = metrics.heightPixels * 0.5f
        val travel = metrics.heightPixels * 0.26f
        val from = if (forward) middle + travel else middle - travel
        val to = if (forward) middle - travel else middle + travel
        val path = Path().apply { moveTo(x, from); lineTo(x, to) }
        return dispatch(path, 300L, x, from, to)
    }

    private fun dispatch(
        path: Path, durationMs: Long, x: Float, from: Float, to: Float,
        active: () -> Boolean = { true }, expected: Snapshot? = null
    ): Boolean {
        val svc = service ?: return false
        val guard = gestureGuard.get() ?: return false
        if (!active() || !guard.active() || Looper.myLooper() == Looper.getMainLooper()) return false
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return false
        val app = root.packageName?.toString()
        val window = root.windowId
        if (expected != null && (window != expected.windowId || app != expected.app)) return false
        // Include a small margin around both the contact point and the entire swipe path.
        val footprint = Rect(
            floor(x).toInt() - 4, floor(minOf(from, to)).toInt() - 4,
            ceil(x).toInt() + 5, ceil(maxOf(from, to)).toInt() + 5
        )
        try {
            if (!guard.overlay(footprint) || !guard.active() || service !== svc) return false
            val front = svc.rootInActiveWindow ?: return false
            if (front.windowId != window || front.packageName?.toString() != app) return false
            val complete = CountDownLatch(1)
            var landed = false
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    landed = true
                    complete.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    complete.countDown()
                }
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            if (!active() || !guard.active() || !svc.dispatchGesture(gesture, callback, Handler(Looper.getMainLooper()))) return false
            return complete.await(durationMs + 1_000L, TimeUnit.MILLISECONDS) && landed
        } catch (e: Exception) {
            Diag.caught("dispatch gesture", e)
            return false
        } finally {
            guard.overlay(null)
        }
    }

    private val WHITESPACE = Regex("\\s+")

    private const val MAX_ITEMS = 48
    private const val MAX_NODES = 1500
    private const val LABEL_MAX = 70
    private const val HINT_MAX = 40
    private const val TAP_ANCESTORS = 4
    private const val SCROLL_ANCESTORS = 6
}
