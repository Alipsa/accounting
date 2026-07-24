package se.alipsa.accounting.service

import java.nio.file.Path

/** Atomically writes one secret-bearing workspace file. */
interface SecretFileWriter {
  void write(Path root, Path target, byte[] content, SecretFileKind kind)
}
