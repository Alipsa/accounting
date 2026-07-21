package se.alipsa.accounting.mcp

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded read/write execution policy for MCP requests. */
final class McpOperationCoordinator implements Closeable {

  private static final Set<String> WRITE_TOOLS = [
      'set_active_voucher_draft', 'create_correction_voucher', 'book_vat_transfer',
      'close_fiscal_year', 'import_sie', 'export_sie'
  ] as Set<String>
  private final McpUiGuard uiGuard
  private final ExecutorService writeExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy())
  private final ExecutorService readExecutor = new ThreadPoolExecutor(8, 8, 0L, TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.AbortPolicy())

  McpOperationCoordinator(McpUiGuard uiGuard) {
    this.uiGuard = uiGuard
  }

  Object dispatch(McpDispatcher dispatcher, Map<String, Object> request) {
    boolean write = isWrite(request)
    Future<Object> result = (write ? writeExecutor : readExecutor).submit {
      if (write) {
        uiGuard.beginWrite()
      }
      try {
        dispatcher.dispatch(request)
      } finally {
        if (write) {
          uiGuard.endWrite()
        }
      }
    }
    result.get()
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
