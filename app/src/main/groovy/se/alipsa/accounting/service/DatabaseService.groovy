package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.TupleConstructor
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType

import se.alipsa.accounting.support.AppPaths

import java.nio.file.Path
import java.util.logging.Logger

/**
 * Bootstraps and provides access to the embedded H2 database.
 */
final class DatabaseService {

  private static final Logger log = Logger.getLogger(DatabaseService.name)
  // Eager singleton -- construction must remain side-effect-free because the
  // instance is created at class-loading time, before --home=... has been processed.
  static DatabaseService instance = new DatabaseService()
  static final String USERNAME = 'sa'
  static final String PASSWORD = ''
  private static final String DRIVER = 'org.h2.Driver'
  private static final List<MigrationDefinition> MIGRATIONS = [
      new MigrationDefinition(1, 'V1__baseline.sql', '/db/migrations/V1__baseline.sql'),
      new MigrationDefinition(2, 'V2__company_and_fiscal_years.sql', '/db/migrations/V2__company_and_fiscal_years.sql'),
      new MigrationDefinition(3, 'V3__chart_of_accounts.sql', '/db/migrations/V3__chart_of_accounts.sql'),
      new MigrationDefinition(4, 'V4__vouchers.sql', '/db/migrations/V4__vouchers.sql'),
      new MigrationDefinition(5, 'V5__audit_and_attachments.sql', '/db/migrations/V5__audit_and_attachments.sql'),
      new MigrationDefinition(6, 'V6__audit_log_chain_head.sql', '/db/migrations/V6__audit_log_chain_head.sql'),
      new MigrationDefinition(7, 'V7__vat_periods.sql', '/db/migrations/V7__vat_periods.sql'),
      new MigrationDefinition(8, 'V8__vat_periodicity.sql', '/db/migrations/V8__vat_periodicity.sql'),
      new MigrationDefinition(9, 'V9__audit_log_vat_period.sql', '/db/migrations/V9__audit_log_vat_period.sql'),
      new MigrationDefinition(10, 'V10__report_archive.sql', '/db/migrations/V10__report_archive.sql'),
      new MigrationDefinition(11, 'V11__import_job.sql', '/db/migrations/V11__import_job.sql'),
      new MigrationDefinition(12, 'V12__closing_entry.sql', '/db/migrations/V12__closing_entry.sql')
  ]

  static DatabaseService newForTesting() {
    new DatabaseService()
  }

  private DatabaseService() {
  }

  int expectedSchemaVersion() {
    MIGRATIONS.last().version
  }

  List<Map<String, Object>> knownMigrations() {
    List<Map<String, Object>> migrations = []
    MIGRATIONS.each { MigrationDefinition migration ->
      Map<String, Object> row = [:]
      row.put('version', migration.version)
      row.put('name', migration.name)
      row.put('resource', migration.resource)
      migrations << row
    }
    migrations
  }

  int currentSchemaVersion() {
    withSql { Sql sql ->
      currentSchemaVersion(sql)
    }
  }

  void initialize() {
    AppPaths.ensureDirectoryStructure()
    withTransaction { Sql sql ->
      ensureSchemaVersionTable(sql)
      applyPendingMigrations(sql)
      applyIndexes(sql)
    }
    log.info("Database initialized at ${AppPaths.databaseBasePath()}.mv.db")
  }

  String databaseUrl() {
    String override = System.getProperty(AppPaths.DATABASE_URL_PROPERTY, '').trim()
    if (override) {
      return validateDatabaseUrl(override)
    }
    validateDatabaseUrl(defaultDatabaseUrl())
  }

  static String embeddedDatabaseUrl(Path applicationHome) {
    "jdbc:h2:file:${AppPaths.databaseBasePath(applicationHome)};AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=FALSE"
  }

  @SuppressWarnings('FactoryMethodName')
  <T> T withSql(@ClosureParams(value = SimpleType, options = ['groovy.sql.Sql']) Closure<T> work) {
    Sql sql = Sql.newInstance(databaseUrl(), USERNAME, PASSWORD, DRIVER)
    try {
      work.call(sql)
    } finally {
      sql.close()
    }
  }

  <T> T withTransaction(@ClosureParams(value = SimpleType, options = ['groovy.sql.Sql']) Closure<T> work) {
    withSql { Sql sql ->
      T result = null
      sql.withTransaction {
        result = work.call(sql)
      }
      result
    }
  }

  private String defaultDatabaseUrl() {
    embeddedDatabaseUrl(AppPaths.applicationHome())
  }

  private String validateDatabaseUrl(String url) {
    String upper = url.toUpperCase(Locale.ROOT)
    if (!upper.startsWith('JDBC:H2:FILE:')) {
      throw new IllegalStateException('Only embedded file-based H2 URLs are allowed.')
    }
    if (upper.contains('AUTO_SERVER=TRUE')) {
      throw new IllegalStateException('AUTO_SERVER=TRUE is not allowed for Alipsa Accounting.')
    }
    if (upper.contains('JDBC:H2:TCP:') || upper.contains('JDBC:H2:SSL:') || upper.contains('JDBC:H2:MEM:')) {
      throw new IllegalStateException('Networked or in-memory H2 URLs are not allowed.')
    }
    url
  }

  private void ensureSchemaVersionTable(Sql sql) {
    if (schemaVersionTableExists(sql)) {
      return
    }
    executeScript(sql, loadResource('/db/schema.sql'))
    log.info('Created schema_version table.')
  }

  private boolean schemaVersionTableExists(Sql sql) {
    GroovyRowResult row = (GroovyRowResult) sql.firstRow(
        "select count(*) as total from information_schema.tables where table_name = 'SCHEMA_VERSION'"
    )
    ((Number) row.total).intValue() == 1
  }

  private void applyPendingMigrations(Sql sql) {
    int currentVersion = currentSchemaVersion(sql)
    List<MigrationDefinition> pending = MIGRATIONS.findAll { MigrationDefinition migration ->
      migration.version > currentVersion
    }.sort { MigrationDefinition migration ->
      migration.version
    }

    pending.each { MigrationDefinition migration ->
      executeScript(sql, loadResource(migration.resource))
      sql.executeInsert(
          'insert into schema_version (version, script_name, installed_at) values (?, ?, current_timestamp)',
          [migration.version, migration.name]
      )
      log.info("Applied migration ${migration.name}.")
    }
  }

  private int currentSchemaVersion(Sql sql) {
    GroovyRowResult row = (GroovyRowResult) sql.firstRow('select coalesce(max(version), 0) as version from schema_version')
    ((Number) row.version).intValue()
  }

  private void applyIndexes(Sql sql) {
    executeScript(sql, loadResource('/db/indexes.sql'))
  }

  private void executeScript(Sql sql, String script) {
    parseStatements(script).each { String statement ->
      sql.execute(statement)
    }
  }

  private String loadResource(String resourcePath) {
    InputStream stream = DatabaseService.getResourceAsStream(resourcePath)
    if (stream == null) {
      throw new IllegalStateException("Missing database resource: ${resourcePath}")
    }
    stream.withCloseable { InputStream input ->
      new String(input.readAllBytes(), 'UTF-8')
    }
  }

  private List<String> parseStatements(String script) {
    List<String> statements = []
    StringBuilder current = new StringBuilder()

    script.eachLine { String rawLine ->
      String line = rawLine.trim()
      if (!line || line.startsWith('--')) {
        return
      }
      current.append(rawLine).append('\n')
      if (line.endsWith(';')) {
        statements << stripStatementDelimiter(current.toString())
        current.setLength(0)
      }
    }

    if (current.length() > 0) {
      statements << current.toString().trim()
    }

    statements.findAll { String statement -> statement }
  }

  private String stripStatementDelimiter(String statement) {
    String trimmed = statement.trim()
    trimmed.endsWith(';') ? trimmed[0..-2].trim() : trimmed
  }

  @TupleConstructor
  private static final class MigrationDefinition {

    final int version
    final String name
    final String resource
  }
}
