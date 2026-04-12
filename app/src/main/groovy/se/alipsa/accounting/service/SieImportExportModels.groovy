package se.alipsa.accounting.service

import groovy.transform.Canonical
import groovy.transform.CompileStatic

import alipsa.sieparser.SieDocument

import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.ImportJob

import java.nio.file.Path
import java.time.LocalDate

@Canonical
@CompileStatic
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
@CompileStatic
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
@CompileStatic
final class ParsedSie {

  SieDocument document
  List<String> warnings
}

@Canonical
@CompileStatic
final class ImportCounts {

  int accountsCreated
  int openingBalanceCount
  int voucherCount
  int lineCount
}

@Canonical
@CompileStatic
final class VoucherImportSummary {

  int voucherCount
  int lineCount
}

@Canonical
@CompileStatic
final class AccountClassification {

  String accountClass
  String normalBalanceSide
  boolean manualReviewRequired
  String note
}

@Canonical
@CompileStatic
final class AccountSeed {

  String accountName
  String accountClass
  String normalBalanceSide
}

@Canonical
@CompileStatic
final class ExportVoucherSeed {

  long voucherId
  String seriesCode
  Integer runningNumber
  LocalDate accountingDate
  String description
  List<ExportLineSeed> lines
}

@Canonical
@CompileStatic
final class ExportLineSeed {

  int lineIndex
  String accountNumber
  String lineDescription
  BigDecimal debitAmount
  BigDecimal creditAmount
}

@Canonical
@CompileStatic
final class ExportPayload {

  SieDocument document
  FiscalYear fiscalYear
  int accountCount
  int openingBalanceCount
  int voucherCount
}
