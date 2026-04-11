package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.*

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path

class DatabaseServiceTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private String previousHome
  private String previousUrl

  @BeforeEach
  void captureProperties() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    previousUrl = System.getProperty(AppPaths.DATABASE_URL_PROPERTY)
    databaseService = DatabaseService.newForTesting()
  }

  @AfterEach
  void restoreProperties() {
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
    restoreProperty(AppPaths.DATABASE_URL_PROPERTY, previousUrl)
  }

  @Test
  void initializeCreatesBaselineSchemaVersion() {
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())

    databaseService.initialize()

    GroovyRowResult result = databaseService.withSql { Sql sql ->
      sql.firstRow('''
          select
              (select coalesce(max(version), 0) from schema_version) as version,
              (select count(*) from information_schema.tables where table_name = 'COMPANY_SETTINGS') as companySettings,
              (select count(*) from information_schema.tables where table_name = 'FISCAL_YEAR') as fiscalYear,
              (select count(*) from information_schema.tables where table_name = 'ACCOUNTING_PERIOD') as accountingPeriod,
              (select count(*) from information_schema.tables where table_name = 'ACCOUNT') as accountTable,
              (select count(*) from information_schema.tables where table_name = 'OPENING_BALANCE') as openingBalance,
              (select count(*) from information_schema.tables where table_name = 'VOUCHER_CHAIN_HEAD') as voucherChainHead,
              (select count(*) from information_schema.tables where table_name = 'VOUCHER_SERIES') as voucherSeries,
              (select count(*) from information_schema.tables where table_name = 'VOUCHER') as voucher,
              (select count(*) from information_schema.tables where table_name = 'VOUCHER_LINE') as voucherLine,
              (select count(*) from information_schema.tables where table_name = 'ATTACHMENT') as attachment,
              (select count(*) from information_schema.tables where table_name = 'AUDIT_LOG') as auditLog,
              (select count(*) from information_schema.tables where table_name = 'AUDIT_LOG_CHAIN_HEAD') as auditLogChainHead
      ''') as GroovyRowResult
    }

    assertEquals(6, ((Number) result.version).intValue())
    assertEquals(1, ((Number) result.companySettings).intValue())
    assertEquals(1, ((Number) result.fiscalYear).intValue())
    assertEquals(1, ((Number) result.accountingPeriod).intValue())
    assertEquals(1, ((Number) result.accountTable).intValue())
    assertEquals(1, ((Number) result.openingBalance).intValue())
    assertEquals(1, ((Number) result.voucherChainHead).intValue())
    assertEquals(1, ((Number) result.voucherSeries).intValue())
    assertEquals(1, ((Number) result.voucher).intValue())
    assertEquals(1, ((Number) result.voucherLine).intValue())
    assertEquals(1, ((Number) result.attachment).intValue())
    assertEquals(1, ((Number) result.auditLog).intValue())
    assertEquals(1, ((Number) result.auditLogChainHead).intValue())
    assertTrue(tempDir.resolve('data').resolve('accounting.mv.db').toFile().exists())
  }

  @Test
  void unsafeDatabaseUrlIsRejected() {
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    System.setProperty(AppPaths.DATABASE_URL_PROPERTY, 'jdbc:h2:file:/tmp/accounting;AUTO_SERVER=TRUE')

    Executable action = { databaseService.initialize() } as Executable

    assertThrows(IllegalStateException, action)
  }

  @Test
  void networkedDatabaseUrlIsRejected() {
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    System.setProperty(AppPaths.DATABASE_URL_PROPERTY, 'jdbc:h2:tcp://localhost/accounting')

    Executable action = { databaseService.initialize() } as Executable

    assertThrows(IllegalStateException, action)
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
