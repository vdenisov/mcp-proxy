package org.plukh.mcpproxy

import java.security.MessageDigest

/**
 * Renders a secret as `<redacted: N chars, sha256:xxxx>` - enough to compare two values and spot an
 * empty one, never enough to use. Shared by the `--check` report and the `--login` token summary,
 * both of which end up in output users paste into issues.
 */
internal fun redactSecret(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    val fingerprint = digest.take(4).joinToString("") { "%02x".format(it) }
    return "<redacted: ${value.length} chars, sha256:$fingerprint>"
}
