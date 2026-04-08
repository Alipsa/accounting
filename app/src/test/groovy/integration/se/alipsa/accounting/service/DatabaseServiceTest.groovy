package se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

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

        int version = databaseService.withSql { Sql sql ->
            GroovyRowResult row = sql.firstRow(
                'select coalesce(max(version), 0) as version from schema_version'
            ) as GroovyRowResult
            ((Number) row.version).intValue()
        }

        assertEquals(1, version)
        assertTrue(tempDir.resolve('data').resolve('accounting.mv.db').toFile().exists())
    }

    @Test
    void unsafeDatabaseUrlIsRejected() {
        System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.toString())
        System.setProperty(AppPaths.DATABASE_URL_PROPERTY, 'jdbc:h2:file:/tmp/accounting;AUTO_SERVER=TRUE')

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
