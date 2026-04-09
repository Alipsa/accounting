package se.alipsa.accounting.support

import groovy.transform.CompileStatic

import java.nio.file.Path
import java.util.logging.*

/**
 * Configures application logging for startup and runtime diagnostics.
 */
@CompileStatic
final class LoggingConfigurer {

  private static final Logger log = Logger.getLogger(LoggingConfigurer.name)
  private static final int LOG_FILE_LIMIT_BYTES = 1_048_576
  private static final int LOG_FILE_COUNT = 5
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

  static synchronized void shutdown() {
    if (!configured) {
      return
    }

    Logger appLogger = Logger.getLogger('se.alipsa.accounting')
    removeFileHandlers(appLogger)
    configured = false
  }

  private static void removeFileHandlers(Logger logger) {
    Handler[] handlers = logger.handlers
    handlers.findAll { Handler handler -> handler instanceof FileHandler }.each { Handler handler ->
      logger.removeHandler(handler)
      handler.close()
    }
  }

  private static FileHandler createFileHandler() {
    Path logFilePattern = AppPaths.logDirectory().resolve('accounting-%g.log')
    FileHandler handler = new FileHandler(
        logFilePattern.toString(),
        LOG_FILE_LIMIT_BYTES,
        LOG_FILE_COUNT,
        true
    )
    handler.level = Level.ALL
    handler.formatter = new SimpleFormatter()
    handler
  }
}
