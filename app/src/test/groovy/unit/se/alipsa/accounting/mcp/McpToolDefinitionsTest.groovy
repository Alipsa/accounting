package se.alipsa.accounting.mcp

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

class McpToolDefinitionsTest {

  @Test
  void exportSieOutputPathSchemaDescribesTheAiWorkspaceConfinement() {
    Map<String, Object> exportSieDef = McpToolDefinitions.listTools().find { Map<String, Object> tool ->
      tool.name == 'export_sie'
    } as Map<String, Object>
    assertNotNull(exportSieDef, 'export_sie tool definition must be registered')
    Map<String, Object> inputSchema = exportSieDef.inputSchema as Map<String, Object>
    Map<String, Object> properties = inputSchema.get('properties') as Map<String, Object>
    Map<String, Object> outputPathSchema = properties.output_path as Map<String, Object>
    String description = outputPathSchema.description as String

    assertTrue(description.contains('workspace'),
        "output_path description should mention the AI workspace confinement, was: ${description}")
    assertFalse(description.contains('application SIE export directory'),
        'output_path description must not claim the stale unrestricted default location')
  }
}
