package se.alipsa.accounting.mcp

import groovy.transform.PackageScope

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Bounded read/write execution policy for MCP requests. */
final class McpOperationCoordinator implements Closeable {

  private static final long OPERATION_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30)
  private static final Set<String> WRITE_TOOLS = [
      'set_active_voucher_draft', 'create_correction_voucher', 'book_vat_transfer',
      'close_fiscal_year', 'import_sie', 'save_accounting_instruction'
  ] as Set<String>
  private final McpUiGuard uiGuard
  private final ExecutorService writeExecutor
  private final ExecutorService readExecutor
  private final long operationTimeoutMillis

  McpOperationCoordinator(McpUiGuard uiGuard) {
    this(uiGuard, 16, 32, OPERATION_TIMEOUT_MILLIS)
  }

  @PackageScope
  McpOperationCoordinator(McpUiGuard uiGuard, int writeQueueCapacity, int readQueueCapacity) {
    this(uiGuard, writeQueueCapacity, readQueueCapacity, OPERATION_TIMEOUT_MILLIS)
  }

  @PackageScope
  McpOperationCoordinator(McpUiGuard uiGuard, int writeQueueCapacity, int readQueueCapacity, long operationTimeoutMillis) {
    this.uiGuard = uiGuard
    this.operationTimeoutMillis = operationTimeoutMillis
    writeExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(writeQueueCapacity), new ThreadPoolExecutor.AbortPolicy())
    readExecutor = new ThreadPoolExecutor(8, 8, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(readQueueCapacity), new ThreadPoolExecutor.AbortPolicy())
  }

  Object dispatch(McpDispatcher dispatcher, Map<String, Object> request) {
    execute(isWrite(request)) { dispatcher.dispatch(request) }
  }

  @PackageScope
  Object execute(boolean write, Closure<Object> action) {
    long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(operationTimeoutMillis)
    Future<Object> result = (write ? writeExecutor : readExecutor).submit {
      if (System.nanoTime() >= deadlineNanos) {
        throw new McpOperationTimeoutException('MCP operation timed out before it could start.', true)
      }
      if (write) {
        uiGuard.beginWrite()
      }
      try {
        action.call()
      } finally {
        if (write) {
          uiGuard.endWrite()
        }
      }
    }
    try {
      result.get(operationTimeoutMillis, TimeUnit.MILLISECONDS)
    } catch (TimeoutException exception) {
      // Do not interrupt a started database operation. A queued operation observes the deadline below.
      throw new McpOperationTimeoutException('MCP operation timed out; a started operation may still be completing.', false)
    } catch (ExecutionException exception) {
      if (exception.cause instanceof McpOperationTimeoutException) {
        throw (McpOperationTimeoutException) exception.cause
      }
      throw exception
    }
  }

  @PackageScope
  int writeQueueSize() {
    ((ThreadPoolExecutor) writeExecutor).queue.size()
  }

  @Override
  void close() {
    writeExecutor.shutdown()
    readExecutor.shutdown()
    writeExecutor.awaitTermination(30, TimeUnit.SECONDS)
    readExecutor.awaitTermination(30, TimeUnit.SECONDS)
  }

  private static boolean isWrite(Map<String, Object> request) {
    if (request.method != 'tools/call' || !(request.params instanceof Map)) {
      return false
    }
    WRITE_TOOLS.contains(((Map) request.params).get('name'))
  }
}
