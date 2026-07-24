package se.alipsa.accounting.service

import java.nio.file.Path

/** Reports which secret files a purge removed or could not remove. */
final class PurgeResult {
  final List<Path> removed
  final List<Path> failed

  PurgeResult(List<Path> removed, List<Path> failed) {
    this.removed = List.copyOf(removed)
    this.failed = List.copyOf(failed)
  }

  boolean isComplete() { failed.isEmpty() }
}
