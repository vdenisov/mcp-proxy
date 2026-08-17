package org.plukh.mcpproxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * Owns the real stdin/stdout file descriptors and keeps everything else away from them.
 *
 * The MCP stdio transport requires stdout to carry protocol frames and nothing else. [lockdown]
 * captures the real FD1 for [protocolOut] and then repoints `System.out` at stderr, so a stray
 * `println` - ours, a library's, or a future dependency's - cannot corrupt the stream. Logback is
 * additionally configured to write to stderr only, but that is defence in depth: this is the layer
 * that makes corruption physically impossible.
 *
 * [lockdown] must be the first statement in `main`, before any other class is touched.
 */
object Stdio {

    /** The real stdin. Owned by the downstream endpoint; nothing else may read it. */
    val protocolIn: InputStream = BufferedInputStream(FileInputStream(FileDescriptor.`in`), 64 * 1024)

    /** The real stdout, captured before `System.out` is redirected. Protocol frames only. */
    val protocolOut: OutputStream = BufferedOutputStream(FileOutputStream(FileDescriptor.out), 64 * 1024)

    private var lockedDown = false

    @Synchronized
    fun lockdown() {
        if (lockedDown) return
        // Nothing should have been written yet, but flush before we let go of the original stream.
        System.out.flush()
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))
        lockedDown = true
    }
}
