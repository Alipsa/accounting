package se.alipsa.accounting.service

import groovy.transform.Canonical

import alipsa.sieparser.SieDocument

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.ImportJob

import java.nio.file.Path
import java.time.LocalDate

@Canonical
final class SieImportResult {

  ImportJob job
  FiscalYear fiscalYear
  boolean duplicate
  int accountsCreated
  int openingBalanceCount
  int voucherCount
  int lineCount
  List<String> warnings = []
}

@Canonical
final class SieImportPreview {

  String companyNameInFile
  LocalDate fiscalYearStart
  LocalDate fiscalYearEnd
  int accountCount
  int voucherCount
  int lineCount
  List<String> warnings = []
  String checksumSha256
  boolean replaceExisting
  boolean fiscalYearExists
  Long targetFiscalYearId
  String targetFiscalYearName
  FiscalYearPurgeSummary purgeSummary
  List<String> blockingIssues = []
  boolean duplicate
  Long duplicateJobId
}

@Canonical
final class SieExportResult {

  Path filePath
  FiscalYear fiscalYear
  String checksumSha256
  long fileSizeBytes
  int accountCount
  int openingBalanceCount
  int voucherCount
}

@Canonical
final class ParsedSie {

  SieDocument document
  List<String> warnings
}

@Canonical
final class ImportCounts {

  int accountsCreated
  int openingBalanceCount
  int voucherCount
  int lineCount
}

@Canonical
final class FiscalYearPurgeSummary {

  int attachmentCount
  int reportArchiveCount
  int openingBalanceCount
  int voucherCount
  int vatPeriodCount
  int auditLogCount

  boolean isEmpty() {
    attachmentCount == 0 && reportArchiveCount == 0 && openingBalanceCount == 0 &&
        voucherCount == 0 && vatPeriodCount == 0 && auditLogCount == 0
  }
}

@Canonical
final class FiscalYearReplacementPlan {

  FiscalYearPurgeSummary summary
  List<String> attachmentStoragePaths = []
  List<String> reportArchiveStoragePaths = []
}

@Canonical
final class VoucherImportSummary {

  int voucherCount
  int lineCount
}

@Canonical
final class AccountClassification {

  String accountClass
  String normalBalanceSide
  boolean manualReviewRequired
  String note
  String accountSubgroup
}

@Canonical
final class AccountSeed {

  String accountName
  String accountClass
  String normalBalanceSide
}

@Canonical
final class ExportVoucherSeed {

  long voucherId
  String seriesCode
  Integer runningNumber
  LocalDate accountingDate
  String description
  List<ExportLineSeed> lines
}

@Canonical
final class ExportLineSeed {

  int lineIndex
  String accountNumber
  String lineDescription
  BigDecimal debitAmount
  BigDecimal creditAmount
}

@Canonical
final class ExportPayload {

  long companyId
  SieDocument document
  FiscalYear fiscalYear
  int accountCount
  int openingBalanceCount
  int voucherCount
}
