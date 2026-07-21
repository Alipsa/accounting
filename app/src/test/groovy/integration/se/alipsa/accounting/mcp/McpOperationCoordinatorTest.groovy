package se.alipsa.accounting.mcp

import static org.junit.jupiter.api.Assertions.*

import org.junit.jupiter.api.Test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class McpOperationCoordinatorTest {

  @Test
  void writeCallsUseTheUiGuardAndReadNotificationsDoNot() {
    CountingGuard guard = new CountingGuard()
    McpOperationCoordinator coordinator = new McpOperationCoordinator(guard)
    try {
      McpDispatcher dispatcher = new McpDispatcher()
      Object readResponse = coordinator.dispatch(dispatcher, [jsonrpc: '2.0', method: 'notifications/initialized'])
      assertNull(readResponse)
      assertEquals(0, guard.begins)
      coordinator.dispatch(dispatcher, [jsonrpc: '2.0', id: 1, method: 'tools/call', params: [name: 'set_active_voucher_draft']])
      assertEquals(1, guard.begins)
      assertEquals(1, guard.ends)
    } finally {
      coordinator.close()
    }
  }

  @Test
  void serializesWritesAndRejectsWhenTheWriteQueueIsFull() {
    CountingGuard guard = new CountingGuard()
    McpOperationCoordinator coordinator = new McpOperationCoordinator(guard, 1, 1)
    CountDownLatch firstStarted = new CountDownLatch(1)
    CountDownLatch releaseFirst = new CountDownLatch(1)
    Thread first = Thread.start {
      coordinator.execute(true) {
        firstStarted.countDown()
        releaseFirst.await(5, TimeUnit.SECONDS)
        'first'
      }
    }
    try {
      assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
      Thread queued = Thread.start { coordinator.execute(true) { 'second' } }
      waitFor { coordinator.writeQueueSize() == 1 }
      assertThrows(RejectedExecutionException) { coordinator.execute(true) { 'third' } }
      assertEquals(1, guard.begins)
      releaseFirst.countDown()
      first.join(5000)
      queued.join(5000)
      assertEquals(2, guard.begins)
      assertEquals(2, guard.ends)
    } finally {
      releaseFirst.countDown()
      coordinator.close()
    }
  }

  private static void waitFor(Closure<Boolean> condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition.call() && System.nanoTime() < deadline) {
      Thread.sleep(10)
    }
    assertTrue(condition.call())
  }

  private static final class CountingGuard implements McpUiGuard {
    int begins
    int ends

    @Override void beginWrite() { begins++ }
    @Override void endWrite() { ends++ }
  }
}
