package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path
import java.sql.SQLException

/**
 * Verifies the post-V17 schema state: DRAFT/BOOKED distinction is gone, the hash
 * chain is dismantled and the status CHECK constraint only accepts the simplified
 * ACTIVE/CANCELLED/CORRECTION values.
 */
class VoucherLifecycleMigrationTest {

  @TempDir
  Path tempDir

  private DatabaseService databaseService
  private String previousHome

  @BeforeEach
  void setUp() {
    previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
    databaseService = DatabaseService.newForTesting()
    databaseService.initialize()
  }

  @AfterEach
  void tearDown() {
    restoreProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
  }

  @Test
  void schemaVersionReachesSeventeen() {
    int version = databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow(
          'select coalesce(max(version), 0) as version from schema_version'
      ) as GroovyRowResult
      ((Number) row.get('version')).intValue()
    } as int
    assertEquals(18, version)
  }

  @Test
  void voucherHashColumnsAreDropped() {
    Map<String, Integer> columnCounts = databaseService.withSql { Sql sql ->
      Map<String, Integer> counts = [:]
      ['PREVIOUS_HASH', 'CONTENT_HASH', 'BOOKED_AT'].each { String column ->
        GroovyRowResult row = sql.firstRow('''
            select count(*) as total
              from information_schema.columns
             where table_name = 'VOUCHER'
               and column_name = ?
        ''', [column]) as GroovyRowResult
        counts[column] = ((Number) row.get('total')).intValue()
      }
      counts
    } as Map<String, Integer>
    assertEquals(0, columnCounts['PREVIOUS_HASH'], 'voucher.previous_hash ska vara borttagen.')
    assertEquals(0, columnCounts['CONTENT_HASH'], 'voucher.content_hash ska vara borttagen.')
    assertEquals(0, columnCounts['BOOKED_AT'], 'voucher.booked_at ska vara borttagen.')
  }

  @Test
  void voucherChainHeadTableIsDropped() {
    int tableCount = databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow('''
          select count(*) as total
            from information_schema.tables
           where table_name = 'VOUCHER_CHAIN_HEAD'
      ''') as GroovyRowResult
      ((Number) row.get('total')).intValue()
    } as int
    assertEquals(0, tableCount, 'voucher_chain_head-tabellen ska vara borttagen.')
  }

  @Test
  void statusCheckConstraintAcceptsSimplifiedLifecycle() {
    seedVoucherFixtures()
    databaseService.withTransaction { Sql sql ->
      insertVoucher(sql, 'ACTIVE', 1, 'A-1')
      insertVoucher(sql, 'CANCELLED', 2, 'A-2')
      insertVoucher(sql, 'CORRECTION', 3, 'A-3')
    }

    int total = databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow('select count(*) as total from voucher') as GroovyRowResult
      ((Number) row.get('total')).intValue()
    } as int
    assertEquals(3, total)
  }

  @Test
  void statusCheckConstraintRejectsLegacyStatuses() {
    seedVoucherFixtures()
    ['DRAFT', 'BOOKED', 'UNKNOWN'].each { String status ->
      Executable insert = {
        databaseService.withTransaction { Sql sql ->
          insertVoucher(sql, status, 1, 'A-1')
        }
      } as Executable
      assertThrows(SQLException, insert, "Status ${status} ska avvisas av CHECK-constraint.")
    }
  }

  private void seedVoucherFixtures() {
    databaseService.withTransaction { Sql sql ->
      sql.executeInsert('''
          insert into fiscal_year (
              company_id, name, start_date, end_date, created_at
          ) values (1, '2026', DATE '2026-01-01', DATE '2026-12-31', current_timestamp)
      ''')
      sql.executeInsert('''
          insert into voucher_series (
              fiscal_year_id, series_code, series_name, next_running_number,
              created_at, updated_at
          ) values (
              (select max(id) from fiscal_year),
              'A', 'Serie A', 1,
              current_timestamp, current_timestamp
          )
      ''')
    }
  }

  private static void insertVoucher(Sql sql, String status, int runningNumber, String voucherNumber) {
    sql.executeInsert('''
        insert into voucher (
            fiscal_year_id,
            voucher_series_id,
            running_number,
            voucher_number,
            accounting_date,
            description,
            status,
            company_id,
            created_at,
            updated_at
        ) values (
            (select max(id) from fiscal_year),
            (select max(id) from voucher_series),
            ?, ?, DATE '2026-01-15', ?, ?, 1,
            current_timestamp, current_timestamp
        )
    ''', [runningNumber, voucherNumber, "Test ${status}".toString(), status])
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
