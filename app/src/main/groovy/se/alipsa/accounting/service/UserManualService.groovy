package se.alipsa.accounting.service

import java.nio.charset.StandardCharsets

/**
 * Loads the bundled end-user manual.
 */
final class UserManualService {

  String loadManual() {
    InputStream stream = UserManualService.getResourceAsStream('/docs/user-manual.md')
    if (stream == null) {
      throw new IllegalStateException('Användarmanualen saknas i resurserna.')
    }
    stream.withCloseable { InputStream input ->
      new String(input.readAllBytes(), StandardCharsets.UTF_8)
    }
  }
}
