package se.alipsa.accounting.service

import java.nio.file.Path

/** Test seam around deletion of a workspace secret. */
interface FileDeleter { boolean deleteIfExists(Path path) }
