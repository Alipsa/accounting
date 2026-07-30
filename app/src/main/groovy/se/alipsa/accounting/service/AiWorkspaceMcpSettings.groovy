package se.alipsa.accounting.service

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Seeds a Claude Code workspace's settings.local.json with permission rules that
 * pre-approve the read-only accounting MCP tools, so the assistant is not prompted
 * for consent on every lookup. Write/import/export tools are deliberately left out
 * so they still require explicit approval.
 */
final class AiWorkspaceMcpSettings {

  static final String MCP_SERVER_NAME = 'accounting'

  static final List<String> READ_ONLY_TOOLS = [
      'get_active_context',
      'list_companies',
      'get_company_info',
      'list_fiscal_years',
      'list_accounts',
      'list_accounting_instructions',
      'list_vouchers',
      'get_trial_balance',
      'get_general_ledger',
      'list_vat_periods',
      'get_vat_report',
      'preview_voucher',
      'get_active_voucher_draft',
      'preview_year_end',
      'preview_sie_import',
      'list_import_jobs'
  ].asImmutable()

  private AiWorkspaceMcpSettings() {
  }

  static String mergeSettingsLocal(String existingJson) {
    Map<String, Object> settings = parseOrEmpty(existingJson)

    Map<String, Object> permissions = settings.permissions instanceof Map
        ? new LinkedHashMap<String, Object>(settings.permissions as Map<String, Object>)
        : [:]
    List<String> allow = permissions.allow instanceof List
        ? new ArrayList<String>(permissions.allow as List<String>)
        : []
    READ_ONLY_TOOLS.each { String tool ->
      String rule = "mcp__${MCP_SERVER_NAME}__${tool}" as String
      if (!allow.contains(rule)) {
        allow << rule
      }
    }
    permissions.allow = allow
    settings.permissions = permissions

    List<String> enabledServers = settings.enabledMcpjsonServers instanceof List
        ? new ArrayList<String>(settings.enabledMcpjsonServers as List<String>)
        : []
    if (!enabledServers.contains(MCP_SERVER_NAME)) {
      enabledServers << MCP_SERVER_NAME
    }
    settings.enabledMcpjsonServers = enabledServers

    JsonOutput.prettyPrint(JsonOutput.toJson(settings)) + '\n'
  }

  private static Map<String, Object> parseOrEmpty(String json) {
    if (!json?.trim()) {
      return [:]
    }
    Object parsed = new JsonSlurper().parseText(json)
    parsed instanceof Map ? parsed as Map<String, Object> : [:]
  }
}
