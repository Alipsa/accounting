package se.alipsa.accounting.service

import java.nio.file.Path

/** Test seam for checking whether a path names an executable regular file. */
interface ExecutableProbe { boolean isExecutableFile(Path candidate) }
