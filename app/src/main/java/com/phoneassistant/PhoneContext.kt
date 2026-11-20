package com.phoneassistant

/** An ordinary chat must never grant permission to send a screen snapshot. */
internal object PhoneContext {
    private val question = Regex(
        """^(?:(?:hey )?pip[, ]+)?(?:please )?(?:(?:can|could|would) you (?:tell me|check|read|look at|see)|what|which|who|how (?:many|much)|tell me|read|check|look at)\b""",
        RegexOption.IGNORE_CASE
    )
    private val onDevice = Regex(
        """\b(?:this screen|(?:on|from) (?:my|the) screen|(?:on|from) my phone|(?:in|from|on) this app|(?:currently )?open (?:right now|on my phone)|(?:on|from) (?:this|the) page|(?:in|from) this (?:chat|conversation)|(?:visible|displayed|shown) (?:here|right now))\b""",
        RegexOption.IGNORE_CASE
    )
    private val quotedCommand = Regex("""\b(?:repeat|pretend|hypothetically|imagine)\b""", RegexOption.IGNORE_CASE)

    fun mayInspect(message: String): Boolean = question.containsMatchIn(message.trim()) &&
        onDevice.containsMatchIn(message) && !quotedCommand.containsMatchIn(message)

    fun permitsWindow(packageName: String): Boolean = packageName != "android" &&
        packageName != "com.android.systemui" && !packageName.endsWith(".systemui")
}
