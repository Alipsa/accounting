package se.alipsa.accounting.mcp

import java.util.concurrent.ConcurrentHashMap

/** Tracks single-use write-preview tokens for one desktop session. */
final class PreviewTokenLedger {

  // Intentionally unbounded for the bounded lifetime of a desktop process to preserve replay protection.
  private final Set<String> consumedTokens = ConcurrentHashMap.newKeySet()

  boolean reserve(String token) {
    consumedTokens.add(token)
  }

  Object executeReservedWrite(String token, Closure<Object> write) {
    try {
      write.call()
    } catch (Exception exception) {
      consumedTokens.remove(token)
      throw exception
    }
  }

  @groovy.transform.PackageScope
  boolean isConsumed(String token) {
    consumedTokens.contains(token)
  }
}
