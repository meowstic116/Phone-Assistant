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
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

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
 * instructions: a suggestion is not a guarantee. The one caller allowed past it is [Vault]'s
 * sign-in, which is on the phone and stays there.
 */
object Screen {

    /** One usable thing on the screen, numbered the way the model is shown it. */
    class Item(
        val index: Int,
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

    /** The screen, read once: what it is, what is on it, and the listing for the model. */
    class Snapshot(val app: String, val items: List<Item>, val listing: String)

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

    fun snapshot(): Snapshot? {
        val svc = service ?: return null
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return null
        val items = ArrayList<Item>()
        collect(svc.packageName?.toString(), root, items)
        return Snapshot(root.packageName?.toString().orEmpty(), items, listing(items))
    }

    /**
     * Walks the tree in reading order. Our own overlay windows are skipped: Pip's bubble and
     * his panel are not things he should be pressing.
     */
    private fun collect(ownPackage: String?, root: AccessibilityNodeInfo, out: MutableList<Item>) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && out.size < MAX_ITEMS && seen < MAX_NODES) {
            val node = queue.removeFirst()
            seen++
            if (ownPackage != null && node.packageName?.toString() == ownPackage) continue
            if (node.isVisibleToUser) consider(node, out)
            for (child in 0 until node.childCount) {
                node.getChild(child)?.let { queue.add(it) }
            }
        }
    }

    private fun consider(node: AccessibilityNodeInfo, out: MutableList<Item>) {
        val editable = node.isEditable
        // What a field contains is never put in the listing, and the listing is the one thing
        // that leaves the phone: a username, a search for something private, or a password in
        // a field the app did not flag. Only the field's own label, whether it has anything in
        // it, and the app's placeholder go out — never the text.
        val label = (if (editable) node.contentDescription else node.text ?: node.contentDescription)
            ?.toString()?.replace(WHITESPACE, " ")?.trim()?.take(LABEL_MAX).orEmpty()
        val filled = editable && !node.text.isNullOrEmpty()
        val scrollable = node.isScrollable
        // Everything that can be pressed, typed into or scrolled, plus plain text: the text
        // is how the model knows it is looking at the right conversation or message.
        if (!editable && !node.isClickable && !node.isCheckable && !scrollable && label.isEmpty()) {
            return
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val role = node.className?.toString()?.substringAfterLast('.')?.take(24) ?: "View"
        out += Item(
            index = out.size + 1,
            role = role,
            label = label,
            hint = node.hintText?.toString()?.replace(WHITESPACE, " ")?.trim()
                ?.take(HINT_MAX).orEmpty(),
            editable = editable,
            password = node.isPassword,
            filled = filled,
            scrollable = scrollable,
            id = node.viewIdResourceName?.substringAfterLast('/'),
            bounds = bounds,
            node = node
        )
    }

    private fun listing(items: List<Item>): String {
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
     * Presses the element. The label is usually a child of the row that takes the press, so
     * a clickable ancestor is tried before falling back to a real tap on the element's
     * middle — which is also what gets through for windows that report nothing clickable.
     */
    fun tap(item: Item): Boolean {
        var node: AccessibilityNodeInfo? = item.node
        repeat(TAP_ANCESTORS) {
            val current = node ?: return@repeat
            if (current.isClickable) {
                val clicked = runCatching {
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                if (clicked) return true
            }
            node = runCatching { current.parent }.getOrNull()
        }
        return gestureTap(item.bounds.centerX().toFloat(), item.bounds.centerY().toFloat())
    }

    fun longPress(item: Item): Boolean {
        var node: AccessibilityNodeInfo? = item.node
        repeat(TAP_ANCESTORS) {
            val current = node ?: return@repeat
            if (current.isLongClickable) {
                val held = runCatching {
                    current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                }.getOrDefault(false)
                if (held) return true
            }
            node = runCatching { current.parent }.getOrNull()
        }
        return gestureLongPress(item.bounds.centerX().toFloat(), item.bounds.centerY().toFloat())
    }

    /**
     * Puts [text] into a field, replacing what is there. Refuses password fields outright.
     *
     * ACTION_SET_TEXT needs no keyboard, which is why it is used instead of typing key by
     * key — and why a job can fill a field in an app that is not even in front yet.
     */
    fun type(item: Item, text: String, secret: Boolean = false): Boolean {
        // Only the vault may fill a password field. [secret] is the one thing that says so,
        // and it is passed by the sign-in path alone — so no prompt, however it is worded,
        // can get a password typed by the model into a field.
        if (item.password && !secret) return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        val node = item.node
        if (runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }.getOrDefault(false)
        ) {
            return true
        }
        // Some fields only take text once they hold focus; asking directly is the reliable
        // second attempt and still needs no keyboard.
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        return runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
    }

    /** Scrolls the given element, its nearest scrollable parent, or the screen itself. */
    fun scroll(forward: Boolean, item: Item?): Boolean {
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        var node: AccessibilityNodeInfo? = item?.node
            ?: runCatching { service?.rootInActiveWindow }.getOrNull()
        repeat(SCROLL_ANCESTORS) {
            val current = node ?: return@repeat
            if (current.isScrollable &&
                runCatching { current.performAction(action) }.getOrDefault(false)
            ) {
                return true
            }
            node = runCatching { current.parent }.getOrNull()
        }
        return gestureScroll(forward)
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
        val relevant = apps.filter { appInRequest(listOf(it), request) != null }
        return (relevant + apps).distinctBy { it.packageName }.take(limit)
            .joinToString(", ") { it.label }
    }

    /**
     * The app a request names, if any: "text Sam on WhatsApp" is WhatsApp, and the longest
     * label wins so "Phone Assistant" could never lose to "Phone". Word boundaries keep
     * "gallery" out of "gallerystuff" and out of a word that merely contains it.
     */
    fun appInRequest(apps: List<AppRef>, request: String): AppRef? {
        val text = request.lowercase()
        return apps
            .filter { it.label.length >= 3 }
            .filter { app ->
                val label = app.label.lowercase()
                runCatching {
                    Regex("\\b" + Regex.escape(label) + "\\b").containsMatchIn(text)
                }.getOrDefault(false)
            }
            .maxByOrNull { it.label.length }
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
    fun openApp(ctx: Context, query: String): String? {
        val app = findApp(ctx, query) ?: return null
        return if (open(ctx, app)) app.label else null
    }

    fun findApp(ctx: Context, query: String): AppRef? {
        val wanted = query.trim().lowercase()
        if (wanted.isEmpty()) return null
        val all = apps(ctx)
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

    /** The package of the window in front: the app a snapshot would read. */
    fun inFront(): String? =
        runCatching { service?.rootInActiveWindow?.packageName?.toString() }.getOrNull()

    // ------------------------------------------------------------------ gestures

    private fun gestureTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 60L)
    }

    private fun gestureLongPress(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 550L)
    }

    private fun gestureScroll(forward: Boolean): Boolean {
        val metrics = service?.resources?.displayMetrics ?: return false
        val x = metrics.widthPixels * 0.5f
        val middle = metrics.heightPixels * 0.5f
        val travel = metrics.heightPixels * 0.26f
        // A finger moving up pushes the content up, which is scrolling down.
        val from = if (forward) middle + travel else middle - travel
        val to = if (forward) middle - travel else middle + travel
        val path = Path().apply {
            moveTo(x, from)
            lineTo(x, to)
        }
        return dispatch(path, 300L)
    }

    private fun dispatch(path: Path, durationMs: Long): Boolean {
        val svc = service ?: return false
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return runCatching {
            svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }.getOrDefault(false)
    }

    private val WHITESPACE = Regex("\\s+")

    private const val MAX_ITEMS = 48
    private const val MAX_NODES = 1500
    private const val LABEL_MAX = 70
    private const val HINT_MAX = 40
    private const val TAP_ANCESTORS = 4
    private const val SCROLL_ANCESTORS = 6
}
