package com.phoneassistant

/**
 * The site as a host: lower case, no scheme, no "www.", no port, no path. It is only used
 * to match one entry with another, so a login saved from a link the user pasted still
 * answers when the model names the bare domain. Split out of [Vault] so the host tests can
 * compile the rules without Android.
 */
internal fun siteHost(raw: String): String {
    var text = raw.trim().lowercase()
    text = text.substringAfter("://", text)
    text = text.substringBefore('/')
    text = text.substringBefore('?')
    text = text.substringBefore('#')
    text = text.substringBefore(':')
    return text.removePrefix("www.").trim('.')
}
