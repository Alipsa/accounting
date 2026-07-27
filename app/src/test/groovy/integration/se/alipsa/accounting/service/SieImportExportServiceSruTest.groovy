package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import org.junit.jupiter.api.Test

import java.nio.file.Path

class SieImportExportServiceSruTest extends AbstractSieImportExportServiceTest {

  @Test
  void exportEmitsSruLinesForAccountsWithCodes() {
    switchHome(tempDir.resolve('sru-export-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SeededServices services = seedEnvironment(databaseService)
    databaseService.withSql { Sql sql ->
      sql.executeUpdate("update account set sru_code = ?, sru_code2 = ? where account_number = ?", ['7261', '7653', '1510'])
      sql.executeUpdate("update account set sru_code = ? where account_number = ?", ['7410', '3010'])
    }
    Path exportPath = tempDir.resolve('sru-export.sie')

    services.sieService.exportFiscalYear(services.fiscalYear.id, exportPath)

    List<String> sruLines = exportPath.toFile().readLines('windows-1252').findAll { it.startsWith('#SRU') }
    assertTrue(sruLines.contains('#SRU 1510 7261'))
    assertTrue(sruLines.contains('#SRU 1510 7653'))
    assertTrue(sruLines.contains('#SRU 3010 7410'))
    assertEquals(3, sruLines.size())
  }

  @Test
  void previewSieExportFlagsLegalFormUnset() {
    switchHome(tempDir.resolve('preview-unset-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SeededServices services = seedEnvironment(databaseService)

    SieExportPreview preview = services.sieService.previewSieExport(services.fiscalYear.id)

    assertTrue(preview.legalFormUnset)
    assertEquals([], preview.accountsMissingSruCode)
  }

  @Test
  void previewSieExportFlagsMissingCodesIncludingResultAccounts() {
    switchHome(tempDir.resolve('preview-missing-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SeededServices services = seedEnvironment(databaseService)
    databaseService.withSql { Sql sql ->
      sql.executeUpdate('update company set legal_form = ? where id = ?', ['AKTIEBOLAG', CompanyService.LEGACY_COMPANY_ID])
      sql.executeUpdate('update account set sru_code = ? where account_number = ?', ['7251', '1510'])
      sql.executeUpdate('update account set sru_code = ? where account_number = ?', ['7301', '2010'])
      // 2611 and 3010 are left without a code - 3010 is an INCOME account with a voucher line
      // but no opening/closing balance row, so it only shows up via voucher_line, not via
      // closings/openings (the bug this preview logic specifically fixes)
    }

    SieExportPreview preview = services.sieService.previewSieExport(services.fiscalYear.id)

    assertFalse(preview.legalFormUnset)
    assertTrue(preview.accountsMissingSruCode.contains('3010'), "3010 has voucher-line activity and no opening balance - must still be flagged")
    assertTrue(preview.accountsMissingSruCode.contains('2611'))
    assertFalse(preview.accountsMissingSruCode.contains('1510'))
    assertFalse(preview.accountsMissingSruCode.contains('2010'))
  }

  @Test
  void previewSieExportReturnsNoWarningsWhenAllUsedAccountsHaveCodes() {
    switchHome(tempDir.resolve('preview-clean-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SeededServices services = seedEnvironment(databaseService)
    databaseService.withSql { Sql sql ->
      sql.executeUpdate('update company set legal_form = ? where id = ?', ['AKTIEBOLAG', CompanyService.LEGACY_COMPANY_ID])
      sql.executeUpdate('update account set sru_code = ? where account_number = ?', ['7251', '1510'])
      sql.executeUpdate('update account set sru_code = ? where account_number = ?', ['7301', '2010'])
      sql.executeUpdate('update account set sru_code = ? where account_number = ?', ['7369', '2611'])
      sql.executeUpdate('update account set sru_code = ? where account_number = ?', ['7410', '3010'])
    }

    SieExportPreview preview = services.sieService.previewSieExport(services.fiscalYear.id)

    assertFalse(preview.legalFormUnset)
    assertEquals([], preview.accountsMissingSruCode)
  }

  @Test
  void previewSieExportDoesNotFlagAccountWithOnlySecondSruCodeSet() {
    // AccountService.updateAccount() allows sru_code to be null while sru_code2 is set, and
    // export emits a #SRU line from either column - the preview must not warn about an account
    // that will in fact get exported correctly just because the *first* slot is empty.
    switchHome(tempDir.resolve('preview-second-code-only-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SeededServices services = seedEnvironment(databaseService)
    databaseService.withSql { Sql sql ->
      sql.executeUpdate('update company set legal_form = ? where id = ?', ['AKTIEBOLAG', CompanyService.LEGACY_COMPANY_ID])
      sql.executeUpdate('update account set sru_code = ? where account_number = ?', ['7251', '1510'])
      sql.executeUpdate('update account set sru_code = ? where account_number = ?', ['7301', '2010'])
      sql.executeUpdate('update account set sru_code = ? where account_number = ?', ['7369', '2611'])
      sql.executeUpdate('update account set sru_code2 = ? where account_number = ?', ['7410', '3010'])
    }

    SieExportPreview preview = services.sieService.previewSieExport(services.fiscalYear.id)

    assertFalse(preview.legalFormUnset)
    assertFalse(preview.accountsMissingSruCode.contains('3010'), "3010 has an SRU code in the second slot - must not be flagged")
    assertEquals([], preview.accountsMissingSruCode)
  }

  @Test
  void importPersistsSruCodesOnNewAccount() {
    switchHome(tempDir.resolve('import-sru-new-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SieImportExportService service = createSieService(databaseService)
    Path filePath = tempDir.resolve('import-sru-new.sie')
    filePath.toFile().text = """#FLAGGA 0
#PROGRAM "Test" "1.0"
#FORMAT PC8
#GEN 20260101 "tester"
#SIETYP 4
#FNAMN "Testbolaget AB"
#ORGNR 556677-8899
#RAR 0 20260101 20261231
#KONTO 1630 "Andra kortfristiga fordringar"
#SRU 1630 7261
#IB 0 1630 100.00
"""

    service.importFile(CompanyService.LEGACY_COMPANY_ID, filePath)

    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow(
          'select sru_code as sruCode, sru_code2 as sruCode2 from account where account_number = ?', ['1630']
      ) as GroovyRowResult
      assertEquals('7261', row.get('sruCode'))
      assertNull(row.get('sruCode2'))
    }
  }

  @Test
  void importPersistsTwoSruCodesOnNewAccount() {
    switchHome(tempDir.resolve('import-sru-two-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SieImportExportService service = createSieService(databaseService)
    Path filePath = tempDir.resolve('import-sru-two.sie')
    filePath.toFile().text = """#FLAGGA 0
#PROGRAM "Test" "1.0"
#FORMAT PC8
#GEN 20260101 "tester"
#SIETYP 4
#FNAMN "Testbolaget AB"
#ORGNR 556677-8899
#RAR 0 20260101 20261231
#KONTO 6072 "Representation, ej avdragsgill"
#SRU 6072 7513
#SRU 6072 7653
"""

    service.importFile(CompanyService.LEGACY_COMPANY_ID, filePath)

    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow(
          'select sru_code as sruCode, sru_code2 as sruCode2 from account where account_number = ?', ['6072']
      ) as GroovyRowResult
      assertEquals('7513', row.get('sruCode'))
      assertEquals('7653', row.get('sruCode2'))
    }
  }

  @Test
  void importDropsInvalidSruCodeAndWarnsWithoutAbortingImport() {
    switchHome(tempDir.resolve('import-sru-invalid-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    SieImportExportService service = createSieService(databaseService)
    Path filePath = tempDir.resolve('import-sru-invalid.sie')
    filePath.toFile().text = """#FLAGGA 0
#PROGRAM "Test" "1.0"
#FORMAT PC8
#GEN 20260101 "tester"
#SIETYP 4
#FNAMN "Testbolaget AB"
#ORGNR 556677-8899
#RAR 0 20260101 20261231
#KONTO 1630 "Andra kortfristiga fordringar"
#SRU 1630 ABCD
#IB 0 1630 100.00
"""

    def result = service.importFile(CompanyService.LEGACY_COMPANY_ID, filePath)

    assertTrue(result.job.summary.contains('varningar'))
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow(
          'select sru_code as sruCode from account where account_number = ?', ['1630']
      ) as GroovyRowResult
      assertNull(row.get('sruCode'))
    }
  }

  @Test
  void importDoesNotOverwriteExistingManuallySetSruCode() {
    switchHome(tempDir.resolve('import-sru-preserve-db'))
    DatabaseService databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '1630', 'Manuellt namn', 'ASSET', 'DEBIT')
      sql.executeUpdate('update account set sru_code = ? where account_number = ?', ['7261', '1630'])
    }
    SieImportExportService service = createSieService(databaseService)
    Path filePath = tempDir.resolve('import-sru-preserve.sie')
    filePath.toFile().text = """#FLAGGA 0
#PROGRAM "Test" "1.0"
#FORMAT PC8
#GEN 20260101 "tester"
#SIETYP 4
#FNAMN "Testbolaget AB"
#ORGNR 556677-8899
#RAR 0 20260101 20261231
#KONTO 1630 "Andra kortfristiga fordringar"
#SRU 1630 9999
#IB 0 1630 100.00
"""

    service.importFile(CompanyService.LEGACY_COMPANY_ID, filePath)

    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow(
          'select sru_code as sruCode from account where account_number = ?', ['1630']
      ) as GroovyRowResult
      assertEquals('7261', row.get('sruCode'))
    }
  }
}
