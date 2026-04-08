package se.alipsa.accounting.support

import groovy.transform.CompileStatic

import java.nio.file.Path
import java.util.logging.FileHandler
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

/**
 * Configures application logging for startup and runtime diagnostics.
 */
@CompileStatic
final class LoggingConfigurer {

    private static final Logger log = Logger.getLogger(LoggingConfigurer.name)
    private static boolean configured

    private LoggingConfigurer() {
    }

    static synchronized void configure() {
        if (configured) {
            return
        }

        AppPaths.ensureDirectoryStructure()

        Logger appLogger = Logger.getLogger('se.alipsa.accounting')
        appLogger.level = Level.ALL
        removeFileHandlers(appLogger)
        appLogger.addHandler(createFileHandler())

        configured = true
        log.fine('Logging configured.')
    }

    private static void removeFileHandlers(Logger logger) {
        Handler[] handlers = logger.handlers
        handlers.findAll { Handler handler -> handler instanceof FileHandler }.each { Handler handler ->
            logger.removeHandler(handler)
            handler.close()
        }
    }

    private static FileHandler createFileHandler() {
        Path logFile = AppPaths.logDirectory().resolve('accounting.log')
        FileHandler handler = new FileHandler(logFile.toString(), true)
        handler.level = Level.ALL
        handler.formatter = new SimpleFormatter()
        handler
    }
}
