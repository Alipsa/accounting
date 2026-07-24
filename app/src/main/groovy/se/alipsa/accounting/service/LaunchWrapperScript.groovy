package se.alipsa.accounting.service

import se.alipsa.accounting.support.ProcessArgumentEscaping

import java.nio.file.Path

/** Renders per-launch Unix and Windows wrapper scripts. */
final class LaunchWrapperScript {

  private LaunchWrapperScript() {
  }

  static String unixContent(Path workspace, Path binaryPath, Map<String, String> envVars) {
    StringBuilder content = new StringBuilder('#!/bin/sh\n')
    envVars.each { String name, String value ->
      content << "export ${name}=${ProcessArgumentEscaping.shellQuoteSingle(value)}\n"
    }
    content << "cd ${ProcessArgumentEscaping.shellQuoteSingle(workspace.toString())}\n"
    content << "exec ${ProcessArgumentEscaping.shellQuoteSingle(binaryPath.toString())}\n"
    content.toString()
  }

  static String windowsContent(Path workspace, Path binaryPath, Map<String, String> envVars) {
    StringBuilder content = new StringBuilder('@echo off\r\n')
    content << 'setlocal DisableDelayedExpansion\r\n'
    envVars.each { String name, String value ->
      content << "set \"${name}=${ProcessArgumentEscaping.escapeForCmdScript(value)}\"\r\n"
    }
    content << "cd /d \"${ProcessArgumentEscaping.escapeForCmdScript(workspace.toString())}\"\r\n"
    content << "\"${ProcessArgumentEscaping.escapeForCmdScript(binaryPath.toString())}\"\r\n"
    content.toString()
  }
}
