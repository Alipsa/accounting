package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.service.AccountingPeriodService
import se.alipsa.accounting.service.AttachmentService
import se.alipsa.accounting.service.AuditLogService
import se.alipsa.accounting.service.CompanyService
import se.alipsa.accounting.service.DatabaseService
import se.alipsa.accounting.service.FiscalYearService
import se.alipsa.accounting.service.VoucherService
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path

final class VoucherPanelNavigationTest {

  @TempDir
  Path tempDir

  private String previousHome
  private DatabaseService databaseService
  private VoucherPanel panel
  private List<List<Integer>> moves

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
    AuditLogService auditLogService = new AuditLogService(databaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    FiscalYearService fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    ActiveCompanyManager activeCompanyManager = new ActiveCompanyManager(new CompanyService(databaseService), fiscalYearService)
    panel = new VoucherPanel(
        new VoucherService(databaseService, auditLogService),
        new AccountService(databaseService),
        accountingPeriodService,
        new AttachmentService(databaseService, auditLogService),
        auditLogService,
        activeCompanyManager
    )
    moves = []
    panel.cursorMover = { int row, int col ->
      moves << [row, col]
      null
    } as Closure
  }

  @AfterEach
  void tearDown() {
    databaseService?.shutdown()
    if (previousHome == null) {
      System.clearProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    } else {
      System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
    }
  }

  @Test
  void advancesFromAccountColumnToDebitForDebitAccount() {
    setSingleLine('DEBIT')

    panel.advanceFromCell(0, 0)

    assertEquals([[0, 2]], moves)
  }

  @Test
  void advancesFromAccountColumnToCreditForCreditAccount() {
    setSingleLine('CREDIT')

    panel.advanceFromCell(0, 0)

    assertEquals([[0, 3]], moves)
  }

  @Test
  void advancesFromDescriptionColumnToDebitForDebitAccount() {
    setSingleLine('DEBIT')

    panel.advanceFromCell(0, 1)

    assertEquals([[0, 2]], moves)
  }

  @Test
  void advancesFromDescriptionColumnToCreditForCreditAccount() {
    setSingleLine('CREDIT')

    panel.advanceFromCell(0, 1)

    assertEquals([[0, 3]], moves)
  }

  @Test
  void advancesFromDebitColumnToCredit() {
    setSingleLine('DEBIT')

    panel.advanceFromCell(0, 2)

    assertEquals([[0, 3]], moves)
  }

  @Test
  void advancesFromCreditColumnToNextRowAccount() {
    setSingleLine('CREDIT')

    panel.advanceFromCell(0, 3)

    assertEquals([[1, 0]], moves)
  }

  @Test
  void advancesFromTextColumnToNextRowAccount() {
    setSingleLine('DEBIT')

    panel.advanceFromCell(0, 4)

    assertEquals([[1, 0]], moves)
  }

  private void setSingleLine(String normalBalanceSide) {
    VoucherPanel.LineEntry line = new VoucherPanel.LineEntry(normalBalanceSide: normalBalanceSide)
    panel.lineTableModel.rows.clear()
    panel.lineTableModel.rows.add(line)
  }
}
