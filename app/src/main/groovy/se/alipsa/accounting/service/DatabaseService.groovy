package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.CompileStatic
import groovy.transform.TupleConstructor
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType

import se.alipsa.accounting.support.AppPaths

import java.util.logging.Logger

/**
 * Bootstraps and provides access to the embedded H2 database.
 */
@Singleton(lazy = true)
@CompileStatic
final class DatabaseService {

    private static final Logger log = Logger.getLogger(DatabaseService.name)
    private static final String DRIVER = 'org.h2.Driver'
    private static final List<MigrationDefinition> MIGRATIONS = [
        new MigrationDefinition(1, 'V1__baseline.sql', '/db/migrations/V1__baseline.sql')
    ]

    void initialize() {
        AppPaths.ensureDirectoryStructure()
        validateConfiguration()
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

    @SuppressWarnings('FactoryMethodName')
    <T> T withSql(@ClosureParams(value = SimpleType, options = ['groovy.sql.Sql']) Closure<T> work) {
        Sql sql = Sql.newInstance(databaseUrl(), 'sa', '', DRIVER)
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
        "jdbc:h2:file:${AppPaths.databaseBasePath()};AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=FALSE"
    }

    private void validateConfiguration() {
        validateDatabaseUrl(databaseUrl())
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
