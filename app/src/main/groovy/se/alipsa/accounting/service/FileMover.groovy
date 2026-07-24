package se.alipsa.accounting.service

import java.nio.file.Path

/** Test seam around the atomic rename of a temporary secret file. */
interface FileMover {
  void move(Path from, Path to)
}
