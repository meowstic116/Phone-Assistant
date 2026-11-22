package com.phoneassistant

/**
 * Housekeeping: taking a pile of rows off a screen, one after another.
 *
 * This exists because "press remove on all of them" kept getting refused. Pip's persona says he
 * never deletes something the user did not name, and that rule is right for a one-off deletion —
 * "delete the photo of the dog" needs to know *which* photo. It is the wrong shape for
 * housekeeping: "remove these twelve" does name them, as a set, and making the user tap twelve
 * times is the whole problem they asked about.
 *
 * So it is bounded the way the feed jobs are:
 * - it only starts from an explicit request that qualifies the *screen* rather than some content
 *   ("all categories", "every remove button", "all of them"), so "remove every photo of the dog"
 *   stays a normal job;
 * - only controls whose own label is a bare dismissal are pressed — never "Unsubscribe",
 *   "Delete account", "Remove ads", or anything with a price;
 * - it stops the moment the screen stops being the same list, and reports exactly how many it
 *   pressed rather than claiming the screen is now clean.
 *
 * Money is not part of this: [PurchasePolicy] decides that before a job ever starts.
 */
internal object ClearRows {
    /** What the user is asking Pip to press. */
    private const val DISMISS = "(?:remove|delete|clear|dismiss|get rid of|take out|drop|bin|trash|unlist)"

    /** The scope words that make it a set rather than one named thing. */
    private const val SCOPE = "(?:all(?: of)?(?: the| those| these)?|every(?: one| single)?(?: of the)?|each|both|" +
        "the whole (?:list|page|screen)|these|those)"

    /**
     * What the sweep may be about: the rows and controls on the screen. Anything else — photos,
     * messages, an account — is content, and "all of my messages" is a different request.
     */
    private const val NOUN = "(?:rows?|items?|entries|controls?|buttons?|categories|fields?|things?|ones?|list)"

    /** Scope then noun: "all categories", "every remove button", "each of those rows". */
    private val scopeThenNoun = Regex(
        "\\b$SCOPE\\b(?:\\s+(?:of\\s+)?(?:the|those|these|my)\\b)?\\s+(?:\\w+\\s+)?$NOUN\\b",
        RegexOption.IGNORE_CASE
    )

    /** Verb then scope: "remove all of them", "delete every row", "clear all". */
    private val verbThenScope = Regex(
        "\\b$DISMISS\\b(?:\\s+(?:them|those|these|it|everything))?" +
            "(?:\\s+(?:of\\s+)?$SCOPE\\b|\\s+(?:on|from|of)\\s+$SCOPE\\b)",
        RegexOption.IGNORE_CASE
    )

    /** The controls themselves, named in the plural and with no scope word at all. */
    private val theControls = Regex(
        "\\b$DISMISS\\b(?:\\s+(?:the|those|these))?\\s+(?:\\w+\\s+)?(?:buttons|rows|controls)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Every one of something, with no noun of its own: "remove everything", "clear it all". */
    private val allOfIt = Regex(
        "\\b$DISMISS\\b\\s+(?:everything|every one|all(?: of)? it|all(?: of)? them|the lot)\\b" +
            "|\\b$DISMISS\\b\\s+all\\b(?!\\s+of\\s+(?:the|my|his|her|their|our)\\b)",
        RegexOption.IGNORE_CASE
    )

    /**
     * A scope word qualifying some *content* by name: "every photo of the dog", "all of my
     * messages". That is a selection, not a sweep of the screen, so it is not housekeeping.
     * Only content words are listed here — "all of the items" is the sweep itself.
     */
    private const val CONTENT = "(?:photos?|pictures?|images?|videos?|emails?|contacts?|accounts?|" +
        "profiles?|posts?|messages?|chats?|conversations?|threads?|documents?|files?|albums?|" +
        "playlists?|songs?|notifications?|data|history|friends?|groups?|members?|devices?|apps?)"

    private val qualified = Regex("\\b$SCOPE\\s+(?:\\w+\\s+)?of\\s+(?:the\\s+|my\\s+|his\\s+|her\\s+)?$CONTENT\\b",
        RegexOption.IGNORE_CASE)

    /**
     * Content that is not a row: a conversation, a message, a photo. "Delete every
     * conversation" names what goes, which is a selection the user has to be asked about.
     */
    private val contentNoun = Regex(
        "\\b(?:conversations?|messages?|chats?|photos?|pictures?|images?|videos?|emails?|contacts?|" +
            "accounts?|profiles?|posts?|documents?|files?|albums?|playlists?|songs?|notifications?)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Labels that are a bare dismissal. Anything longer is a specific action, not a row. */
    private val bareDismissal = Regex(
        "^(?:remove|delete|clear|dismiss|close|drop|bin|trash|unlist|x|×|✕|✖|remove item|delete item|" +
            "remove row|delete row|remove from list)$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Labels that dismiss one row but that must never be pressed in bulk: they change a
     * relationship or a plan, not a list. Checked on the row's own label before anything moves.
     */
    private val neverInBulk = Regex(
        "\\b(?:account|subscription|unsubscribe|block|report|cancel (?:order|booking|flight|ride|" +
            "appointment|subscription)|refund|payment|card|password|sign out|log out|delete (?:all )?(?:data|history)|" +
            "deactivate|disable|leave|quit|stop (?:following|sharing)|remove (?:friend|member|participant))\\b",
        RegexOption.IGNORE_CASE
    )

    /** A price, an order, or a checkout anywhere in the label: money stays the user's business. */
    private val money = Regex(
        "\\b(?:[0-9]+[.,][0-9]{2}|\\$|€|£|usd|checkout|check out|pay|buy|purchase|place order|" +
            "confirm (?:order|payment|purchase))\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * True when the request is housekeeping: a dismissal over the screen's own rows and
     * controls, and not a phrase that qualifies some other content to be dropped.
     */
    fun requested(text: String): Boolean {
        val line = text.trim()
        if (line.isEmpty() || line.length > 300) return false
        // A purchase is never a bulk job. Checked first so "remove all" over a cart is refused
        // by the money rule rather than swept up here.
        if (PurchasePolicy.asksToPay(line)) return false
        if (qualified.containsMatchIn(line) || contentNoun.containsMatchIn(line)) return false
        return scopeThenNoun.containsMatchIn(line) || verbThenScope.containsMatchIn(line) ||
            theControls.containsMatchIn(line) || allOfIt.containsMatchIn(line)
    }

    /** True when this label is a row's own dismissal, safe to press as part of a sweep. */
    fun isDismissal(label: String): Boolean {
        val clean = JobProgress.normalize(label).trimEnd('.', '!', ':', ' ')
        if (clean.isEmpty() || clean.length > 32) return false
        if (neverInBulk.containsMatchIn(clean) || money.containsMatchIn(clean)) return false
        return bareDismissal.matches(clean)
    }

    /**
     * Whether [after] is still the list we started on. A sweep stops as soon as the screen is a
     * different place — a settings page, a confirmation dialog, an empty state — because
     * pressing on there is how a bulk job becomes a bulk mistake.
     */
    fun sameList(before: Set<String>, after: Set<String>): Boolean {
        if (after.isEmpty()) return true
        // The remaining dismissals must overlap the ones we started with *and* not have grown:
        // a dialog that adds buttons is a different screen even when it shares a word with the
        // list behind it. Both halves are needed — either alone lets a dialog through.
        return before.intersect(after).isNotEmpty() && after.size <= before.size
    }

    /** The line said at the end. Counts are exact: a claim of zero matters as much as twelve. */
    fun report(pressed: Int, more: Boolean): String = when {
        pressed == 0 -> "I didn’t find any remove buttons on that screen."
        more -> "Pressed $pressed. There are still more on the next screen."
        pressed == 1 -> "Pressed one."
        else -> "Pressed $pressed."
    }

    /**
     * A reply that declines for the reason the persona no longer allows, e.g. "deleting things
     * without a name makes me nervous". The screen's policy is fixed, but a model can still
     * produce one of these, and a refusal to a request Pip can actually do is a bug to the
     * person reading it. The chat path uses this to hand the job to the hands instead of
     * relaying the refusal.
     */
    private val refusal = Regex(
        "\\b(?:makes me nervous|i (?:can'?t|cannot|won'?t|wouldn'?t)|i'?m not going to|" +
            "not something i (?:can|do)|my (?:tiny )?(?:legs|hands) (?:only|can'?t|cannot)|" +
            "that'?s a job for a human|you'?ll have to do (?:the honors|that yourself)|" +
            "do (?:the honors|that) yourself|tell me (?:the )?(?:exact|which)|" +
            "deleting things without a name)\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * True when a spoken reply is a refusal to do something that is merely a job. Curly
     * apostrophes are normalised first: a decoder emits them, and "can’t" is not "can't" to a
     * regular expression.
     */
    fun soundsLikeRefusal(reply: String): Boolean {
        val line = reply.replace('’', '\'').replace('‘', '\'')
        return refusal.containsMatchIn(line)
    }

    /** Said when the screen turns into something the sweep must not walk into. */
    fun stoppedEarly(pressed: Int): String =
        "Pressed $pressed — then the screen changed into something else, so I stopped instead of pressing on."
}
