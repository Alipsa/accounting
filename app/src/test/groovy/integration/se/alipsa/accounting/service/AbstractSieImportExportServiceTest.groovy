package se.alipsa.accounting.service

import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.Company
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VatPeriodicity
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.support.AppPaths
import se.alipsa.accounting.support.I18n

import java.nio.file.Path
import java.time.LocalDate

abstract class AbstractSieImportExportServiceTest {

  @TempDir
  Path tempDir

  private String previousHome
  private Locale previousLocale

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    previousLocale = I18n.instance.locale
    I18n.instance.setLocale(Locale.forLanguageTag('sv'))
  }

  @AfterEach
  void tearDown() {
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
    I18n.instance.setLocale(previousLocale)
  }

  protected SieImportExportService createSieService(DatabaseService databaseService) {
    AuditLogService auditLogService = new AuditLogService(databaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    VoucherService voucherService = new VoucherService(databaseService, auditLogService)
    ReportIntegrityService reportIntegrityService = new ReportIntegrityService(
        new AttachmentService(databaseService, auditLogService),
        auditLogService
    )
    new SieImportExportService(
        databaseService,
        accountingPeriodService,
        voucherService,
        new CompanyService(databaseService),
        reportIntegrityService,
        auditLogService,
        new FiscalYearService(databaseService)
    )
  }

  protected SeededServices seedEnvironment(DatabaseService databaseService) {
    AuditLogService auditLogService = new AuditLogService(databaseService)
    AccountingPeriodService accountingPeriodService = new AccountingPeriodService(databaseService, auditLogService)
    FiscalYearService fiscalYearService = new FiscalYearService(databaseService, accountingPeriodService, auditLogService)
    VoucherService voucherService = new VoucherService(databaseService, auditLogService)
    CompanyService companyService = new CompanyService(databaseService)
    companyService.save(new Company(
        CompanyService.LEGACY_COMPANY_ID, 'Testbolaget AB', '556677-8899', 'SEK', 'sv-SE',
        VatPeriodicity.MONTHLY, true, null, null
    ))
    FiscalYear fiscalYear = fiscalYearService.createFiscalYear(CompanyService.LEGACY_COMPANY_ID, '2026', LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

    databaseService.withTransaction { Sql sql ->
      insertAccount(sql, '2010', 'Eget kapital', 'EQUITY', 'CREDIT')
      insertAccount(sql, '1510', 'Kundfordringar', 'ASSET', 'DEBIT')
      insertAccount(sql, '2611', 'Utgående moms 25%', 'LIABILITY', 'CREDIT')
      insertAccount(sql, '3010', 'Försäljning', 'INCOME', 'CREDIT')
      sql.executeInsert('''
          insert into opening_balance (
              fiscal_year_id,
              account_id,
              amount,
              created_at,
              updated_at
          ) values (?, (select id from account where account_number = ?), ?, current_timestamp, current_timestamp)
      ''', [fiscalYear.id, '1510', 100.00G])
      sql.executeInsert('''
          insert into opening_balance (
              fiscal_year_id,
              account_id,
              amount,
              created_at,
              updated_at
          ) values (?, (select id from account where account_number = ?), ?, current_timestamp, current_timestamp)
      ''', [fiscalYear.id, '2010', 100.00G])
    }

    voucherService.createVoucher(
        fiscalYear.id,
        'A',
        LocalDate.of(2026, 1, 15),
        'Försäljning januari',
        [
            new VoucherLine(null, null, 0, null, '1510', null, 'Kundfordran', 1250.00G, 0.00G),
            new VoucherLine(null, null, 0, null, '3010', null, 'Försäljning', 0.00G, 1000.00G),
            new VoucherLine(null, null, 0, null, '2611', null, 'Utgående moms', 0.00G, 250.00G)
        ]
    )

    new SeededServices(
        createSieService(databaseService),
        fiscalYear,
        4,
        2,
        1,
        3
    )
  }

  protected static void insertAccount(Sql sql, String accountNumber, String accountName, String accountClass, String normalBalanceSide) {
    sql.executeInsert('''
        insert into account (
            company_id,
            account_number,
            account_name,
            account_class,
            normal_balance_side,
            vat_code,
            active,
            manual_review_required,
            classification_note,
            created_at,
            updated_at
        ) values (1, ?, ?, ?, ?, null, true, false, null, current_timestamp, current_timestamp)
    ''', [accountNumber, accountName, accountClass, normalBalanceSide])
  }

  protected void switchHome(Path home) {
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, home.toString())
  }

  protected static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }

  protected static final class SeededServices {

    final SieImportExportService sieService
    final FiscalYear fiscalYear
    final int accountCount
    final int openingBalanceCount
    final int voucherCount
    final int lineCount

    private SeededServices(
        SieImportExportService sieService,
        FiscalYear fiscalYear,
        int accountCount,
        int openingBalanceCount,
        int voucherCount,
        int lineCount
    ) {
      this.sieService = sieService
      this.fiscalYear = fiscalYear
      this.accountCount = accountCount
      this.openingBalanceCount = openingBalanceCount
      this.voucherCount = voucherCount
      this.lineCount = lineCount
    }
  }
}
