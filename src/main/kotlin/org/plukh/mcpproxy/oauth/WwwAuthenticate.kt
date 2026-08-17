package org.plukh.mcpproxy.oauth

/**
 * One challenge from a `WWW-Authenticate` header: a scheme plus auth-params with case-insensitive
 * names (RFC 9110 §11.6.1).
 */
data class Challenge(val scheme: String, val params: Map<String, String>) {

    private fun param(name: String): String? = params.entries.firstOrNull { it.key.equals(name, true) }?.value

    val resourceMetadata: String? get() = param("resource_metadata")
    val scope: String? get() = param("scope")
    val error: String? get() = param("error")
}

/**
 * Parses a `WWW-Authenticate` header into its challenges.
 *
 * Hand-rolled because the grammar is genuinely awkward: both challenges and auth-params are
 * comma-separated in the same header, and values may be quoted strings containing commas and
 * escaped quotes - a naive split corrupts exactly the URL this proxy needs
 * (`resource_metadata="https://host/path"` survives; so would a comma inside it).
 */
fun parseWwwAuthenticate(header: String): List<Challenge> {
    val challenges = mutableListOf<Challenge>()
    var scheme: String? = null
    var params = mutableMapOf<String, String>()
    var i = 0

    fun flush() {
        scheme?.let { challenges.add(Challenge(it, params)) }
        scheme = null
        params = mutableMapOf()
    }

    while (i < header.length) {
        while (i < header.length && (header[i] == ' ' || header[i] == '\t' || header[i] == ',')) i++
        if (i >= header.length) break

        val tokenStart = i
        while (i < header.length && header[i] !in " \t,=") i++
        val token = header.substring(tokenStart, i)
        while (i < header.length && (header[i] == ' ' || header[i] == '\t')) i++

        if (i < header.length && header[i] == '=') {
            i++
            while (i < header.length && (header[i] == ' ' || header[i] == '\t')) i++
            val value: String
            if (i < header.length && header[i] == '"') {
                i++
                val sb = StringBuilder()
                while (i < header.length && header[i] != '"') {
                    if (header[i] == '\\' && i + 1 < header.length) i++
                    sb.append(header[i])
                    i++
                }
                i++ // closing quote
                value = sb.toString()
            } else {
                val valueStart = i
                while (i < header.length && header[i] != ',') i++
                value = header.substring(valueStart, i).trim()
            }
            // A token followed by `=` is an auth-param of the current challenge; a param before any
            // scheme (malformed) is dropped rather than invented into one.
            if (scheme != null) params[token] = value
        } else {
            // A bare token starts a new challenge (its scheme).
            flush()
            scheme = token
        }
    }
    flush()
    return challenges
}
