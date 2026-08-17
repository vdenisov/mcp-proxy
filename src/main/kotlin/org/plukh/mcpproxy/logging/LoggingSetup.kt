package org.plukh.mcpproxy.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.FileAppender
import java.nio.file.Files
import java.nio.file.Path
import org.plukh.mcpproxy.config.LoggingConfig
import org.plukh.mcpproxy.proxyHome
import org.plukh.mcpproxy.resolveUnderHome
import org.slf4j.LoggerFactory
import org.slf4j.Logger as Slf4jLogger

/**
 * Applies the configured log level and optional file appender on top of `logback.xml`.
 *
 * The console appender is left alone: it is bound to stderr, which - together with the stdout
 * lockdown in [org.plukh.mcpproxy.Stdio] - is what keeps protocol frames the only thing on stdout.
 */
object LoggingSetup {

    /**
     * @param home base for a relative `logging.file`, so logs land in the proxy home and follow
     *   `$MCP_PROXY_HOME` rather than scattering across whatever directory the client started us in
     */
    fun configure(config: LoggingConfig, home: Path = proxyHome()) {
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        val root = context.getLogger(Slf4jLogger.ROOT_LOGGER_NAME)

        root.level = Level.toLevel(config.level.uppercase(), Level.INFO)

        config.file?.let { path -> root.addAppender(fileAppender(context, resolveUnderHome(path, home))) }
    }

    private fun fileAppender(context: LoggerContext, path: Path): FileAppender<ch.qos.logback.classic.spi.ILoggingEvent> {
        Files.createDirectories(path.toAbsolutePath().parent)

        val encoder = PatternLayoutEncoder().apply {
            this.context = context
            // %replace keeps the brackets out of the line entirely when there is no session context.
            pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %logger{36}%replace(%X{ctx}){'^(.+)\$', ' [\$1]'} - %msg%n"
            start()
        }
        return FileAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply {
            this.context = context
            name = "FILE"
            file = path.toAbsolutePath().toString()
            isAppend = true
            this.encoder = encoder
            start()
        }
    }

}
