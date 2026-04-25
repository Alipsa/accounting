package se.alipsa.accounting

import static org.junit.jupiter.api.Assertions.*

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class McpHeadlessStartupTest {

  @TempDir
  Path tempDir

  @Test
  void mcpModeStartsAsProcessAndKeepsStdoutJsonRpcClean() {
    Process process = startApplicationProcess()
    String initializeRequest = JsonOutput.toJson([
        jsonrpc: '2.0',
        id     : 1,
        method : 'initialize',
        params : [
            protocolVersion: '2025-11-25',
            capabilities   : [:],
            clientInfo     : [
                name   : 'test-client',
                version: '1'
            ]
        ]
    ])

    process.outputStream.withWriter(StandardCharsets.UTF_8.name()) { Writer writer ->
      writer.write("${initializeRequest}\n")
    }

    boolean exited = process.waitFor(30, TimeUnit.SECONDS)
    if (!exited) {
      process.destroyForcibly()
    }
    assertTrue(exited, 'MCP process should exit after stdin is closed.')
    assertEquals(0, process.exitValue(), process.errorStream.getText(StandardCharsets.UTF_8.name()))

    List<String> stdoutLines = process.inputStream.getText(StandardCharsets.UTF_8.name()).readLines()
    assertEquals(1, stdoutLines.size(), "stdout should contain only one JSON-RPC response: ${stdoutLines}")

    Map<String, Object> response = (Map<String, Object>) new JsonSlurper().parseText(stdoutLines[0])
    assertEquals('2.0', response.jsonrpc)
    assertEquals(1, response.id)
    Map<String, Object> result = (Map<String, Object>) response.result
    assertEquals('2025-11-25', result.protocolVersion)
  }

  private Process startApplicationProcess() {
    String javaExecutable = new File(System.getProperty('java.home'), 'bin/java').absolutePath
    String classpath = System.getProperty('java.class.path')
    List<String> command = [
        javaExecutable,
        '-cp',
        classpath,
        'se.alipsa.accounting.AlipsaAccounting',
        '--mode=mcp',
        "--home=${tempDir.resolve('home').toAbsolutePath()}".toString()
    ]
    new ProcessBuilder(command).start()
  }
}
