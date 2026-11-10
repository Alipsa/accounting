package se.alipsa.accounting.service

/**
 * Test-only helper for pointing {@link DatabaseService} at a private in-memory H2 instance
 * instead of a real file, so integration tests avoid disk I/O for their (throwaway) database.
 * Must be paired with {@code AppPaths.ALLOW_IN_MEMORY_DATABASE_PROPERTY=true}, since
 * DatabaseService.validateDatabaseUrl otherwise rejects in-memory URLs outright.
 */
class TestDatabaseUrls {

  private TestDatabaseUrls() {
  }

  /** A fresh, uniquely-named in-memory H2 URL so concurrently-running tests never share state. */
  static String uniqueInMemoryUrl(String label) {
    "jdbc:h2:mem:${label}-${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
  }

  /** Restores a system property to its pre-test value, clearing it if it wasn't set before. */
  static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name)
      return
    }
    System.setProperty(name, value)
  }
}
