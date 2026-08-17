package org.plukh.mcpproxy.oauth

import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Desktop
import java.net.URI

private val log = KotlinLogging.logger {}

/**
 * Opens the system browser. Failures are logged, never thrown - the authorization URL is always
 * printed as well, so a headless or browserless environment degrades to copy-and-paste.
 */
object BrowserLauncher {

    fun open(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
            val os = System.getProperty("os.name").lowercase()
            val command = when {
                // Not `cmd /c start`: cmd parses `&`, which every authorize URL is full of.
                os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
                os.contains("mac") -> listOf("open", url)
                else -> listOf("xdg-open", url)
            }
            ProcessBuilder(command).start()
        } catch (e: Exception) {
            log.warn(e) { "Could not open a browser; use the printed URL" }
        }
    }
}
