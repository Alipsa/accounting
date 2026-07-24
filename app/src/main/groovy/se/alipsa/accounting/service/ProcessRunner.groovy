package se.alipsa.accounting.service

import java.nio.file.Path

/** Test seam around spawning a terminal process. */
interface ProcessRunner { Process run(List<String> command, Path workingDirectory) }
