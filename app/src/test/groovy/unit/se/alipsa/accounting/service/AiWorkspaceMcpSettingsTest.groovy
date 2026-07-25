package unit.se.alipsa.accounting.service

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

import groovy.json.JsonSlurper

import org.junit.jupiter.api.Test

import se.alipsa.accounting.service.AiWorkspaceMcpSettings

class AiWorkspaceMcpSettingsTest {

  @Test
  void seedsAllReadOnlyToolsWhenNoSettingsExistYet() {
    String merged = AiWorkspaceMcpSettings.mergeSettingsLocal(null)

    Map parsed = new JsonSlurper().parseText(merged) as Map
    List<String> allow = (parsed.permissions as Map).allow as List<String>
    AiWorkspaceMcpSettings.READ_ONLY_TOOLS.each { String tool ->
      assertTrue(allow.contains("mcp__accounting__${tool}" as String), "missing ${tool}")
    }
    assertTrue((parsed.enabledMcpjsonServers as List<String>).contains('accounting'))
  }

  @Test
  void preservesManuallyApprovedWriteToolsAndUnrelatedRulesAcrossRelaunch() {
    String existing = '''
        {
          "permissions": {
            "allow": [
              "mcp__accounting__close_fiscal_year",
              "mcp__accounting__list_accounts",
              "Bash(pandoc *)"
            ]
          },
          "enabledMcpjsonServers": ["accounting"]
        }
    '''

    String merged = AiWorkspaceMcpSettings.mergeSettingsLocal(existing)

    Map parsed = new JsonSlurper().parseText(merged) as Map
    List<String> allow = (parsed.permissions as Map).allow as List<String>
    assertTrue(allow.contains('mcp__accounting__close_fiscal_year'))
    assertTrue(allow.contains('Bash(pandoc *)'))
    AiWorkspaceMcpSettings.READ_ONLY_TOOLS.each { String tool ->
      assertTrue(allow.contains("mcp__accounting__${tool}" as String), "missing ${tool}")
    }
  }

  @Test
  void doesNotDuplicateAlreadyPresentReadOnlyPermissions() {
    String existing = '{"permissions": {"allow": ["mcp__accounting__list_accounts"]}}'

    String merged = AiWorkspaceMcpSettings.mergeSettingsLocal(existing)

    Map parsed = new JsonSlurper().parseText(merged) as Map
    List<String> allow = (parsed.permissions as Map).allow as List<String>
    assertEquals(1, allow.count { String rule -> rule == 'mcp__accounting__list_accounts' })
  }

  @Test
  void doesNotAllowAnyWriteOrDestructiveTool() {
    String merged = AiWorkspaceMcpSettings.mergeSettingsLocal(null)

    Map parsed = new JsonSlurper().parseText(merged) as Map
    List<String> allow = (parsed.permissions as Map).allow as List<String>
    ['import_sie', 'export_sie', 'book_vat_transfer', 'create_correction_voucher',
     'save_accounting_instruction', 'close_fiscal_year', 'set_active_voucher_draft'].each { String tool ->
      assertFalse(allow.contains("mcp__accounting__${tool}" as String), "should not pre-allow ${tool}")
    }
  }
}
