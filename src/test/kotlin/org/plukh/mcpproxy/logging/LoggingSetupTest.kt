package org.plukh.mcpproxy.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.FileAppender
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.plukh.mcpproxy.config.LoggingConfig
import org.slf4j.LoggerFactory
import org.slf4j.Logger as Slf4jLogger

/**
 * The file appender is installed on the *root* logger of the shared context, so every test here
 * detaches it again - a leaked appender would keep writing the rest of the suite's output into a
 * temp file, and the level would stay wherever the last test left it.
 */
class LoggingSetupTest {

    private val context = LoggerFactory.getILoggerFactory() as LoggerContext
    private val root = context.getLogger(Slf4jLogger.ROOT_LOGGER_NAME)
    private val originalLevel: Level = root.level

    @AfterTest
    fun restore() {
        // Kept as Appender<ILoggingEvent>: detachAppender does not accept a star-projected type.
        root.iteratorForAppenders().asSequence().toList().filter { it is FileAppender<*> }.forEach {
            it.stop()
            root.detachAppender(it)
        }
        root.level = originalLevel
    }

    private fun appenderFile(): String =
        root.iteratorForAppenders().asSequence().filterIsInstance<FileAppender<*>>().first().file

    /**
     * The point of `$MCP_PROXY_HOME`: everything the proxy puts in its home moves with it, logs
     * included. A relative `logging.file` used to be resolved against the working directory, which
     * the MCP client chooses - so the log landed in whichever project the proxy was started from.
     */
    @Test
    fun `a relative log file lands in the proxy home`() {
        val home: Path = Files.createTempDirectory("mcp-proxy-log-home")

        LoggingSetup.configure(LoggingConfig(file = "proxy.log", level = "INFO"), home)

        assertEquals(home.resolve("proxy.log").toAbsolutePath().toString(), appenderFile())
    }

    @Test
    fun `an absolute log file is left where it points`() {
        val home: Path = Files.createTempDirectory("mcp-proxy-log-home")
        val elsewhere = Files.createTempDirectory("mcp-proxy-log-elsewhere").resolve("proxy.log")

        LoggingSetup.configure(LoggingConfig(file = elsewhere.toString(), level = "INFO"), home)

        assertEquals(elsewhere.toAbsolutePath().toString(), appenderFile())
    }
}
