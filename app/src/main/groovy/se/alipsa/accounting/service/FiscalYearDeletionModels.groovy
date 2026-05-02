package se.alipsa.accounting.service

import groovy.transform.Canonical

@Canonical
final class StoredFileDeletionFailure {

  String storagePath
  String resolvedPath
  String message
}

@Canonical
final class FiscalYearDeletionResult {

  FiscalYearPurgeSummary summary
  List<String> attachmentStoragePaths = []
  List<String> reportArchiveStoragePaths = []
  List<String> deletedFiles = []
  List<StoredFileDeletionFailure> failedFiles = []
}
