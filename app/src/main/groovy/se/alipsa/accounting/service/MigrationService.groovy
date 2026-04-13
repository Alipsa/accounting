package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.Canonical

import java.time.LocalDateTime

/**
 * Exposes applied and expected forward-only schema migrations.
 */
final class MigrationService {

  private final DatabaseService databaseService

  MigrationService(DatabaseService databaseService = DatabaseService.instance) {
    this.databaseService = databaseService
  }

  int currentSchemaVersion() {
    databaseService.currentSchemaVersion()
  }

  int expectedSchemaVersion() {
    databaseService.expectedSchemaVersion()
  }

  List<AppliedMigration> listAppliedMigrations() {
    databaseService.withSql { Sql sql ->
      sql.rows('''
          select version,
                 script_name as scriptName,
                 installed_at as installedAt
            from schema_version
           order by version
      ''').collect { GroovyRowResult row ->
        new AppliedMigration(
            ((Number) row.get('version')).intValue(),
            row.get('scriptName') as String,
            SqlValueMapper.toLocalDateTime(row.get('installedAt'))
        )
      }
    }
  }

  List<Map<String, Object>> listKnownMigrations() {
    databaseService.knownMigrations()
  }

  @Canonical
  static final class AppliedMigration {

    int version
    String scriptName
    LocalDateTime installedAt
  }
}
