package org.plukh.mcpproxy

import com.github.ajalt.clikt.core.main
import kotlin.system.exitProcess

/**
 * Flags that only ever print information and exit. They never serve a protocol session, so their
 * output belongs on real stdout - `mcp-proxy --help | less` should work like any other CLI.
 * Everything else locks stdout down before another line of code runs.
 */
private val INFO_ONLY_FLAGS = setOf("--help", "-h", "--version")

fun main(args: Array<String>) {
    if (args.none { it in INFO_ONLY_FLAGS }) {
        // Captures the real stdout for protocol frames and repoints System.out at stderr, before
        // any other class gets a chance to print to it.
        Stdio.lockdown()
    }

    val command = ProxyCommand()
    command.main(args)
    exitProcess(command.exitCode)
}
