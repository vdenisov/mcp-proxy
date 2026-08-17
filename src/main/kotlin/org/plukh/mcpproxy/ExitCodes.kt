package org.plukh.mcpproxy

/**
 * Process exit codes. Clikt owns usage errors and exits **1** for them - measured, not assumed; an
 * earlier note here claimed 64, which nothing ever produced.
 */
object ExitCodes {
    /** Clean shutdown - the client closed stdin, or `--check` found nothing identifying. */
    const val OK = 0

    /** Configuration could not be read, parsed, interpolated or validated. */
    const val CONFIG_ERROR = 2

    /** `--check` (or an eager-connect run) could not reach the upstream server. */
    const val UPSTREAM_CONNECT_FAILED = 3

    /** The upstream connection failed mid-session, after the client had already connected. */
    const val UPSTREAM_FAILED = 4

    /** `--check` reached the upstream but found something identifying. */
    const val LEAKS_FOUND = 5

    /** The upstream requires OAuth and no usable cached token exists; run `--login`. */
    const val AUTH_REQUIRED = 6

    /** An OAuth flow ran and failed - denied, timed out, or the server rejected a step. */
    const val AUTH_FAILED = 7
}
