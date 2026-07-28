package se.alipsa.accounting.service

import se.alipsa.accounting.support.ProcessArgumentEscaping

import java.nio.file.Path

/** Renders per-launch Unix and Windows wrapper scripts. */
final class LaunchWrapperScript {

  private LaunchWrapperScript() {
  }

  static String unixContent(Path workspace, Path binaryPath, Map<String, String> envVars, List<String> arguments = []) {
    StringBuilder content = new StringBuilder('#!/bin/sh\n')
    envVars.each { String name, String value ->
      content << "export ${name}=${ProcessArgumentEscaping.shellQuoteSingle(value)}\n"
    }
    content << "cd ${ProcessArgumentEscaping.shellQuoteSingle(workspace.toString())}\n"
    // Deliberately not "exec": exec replaces this shell process with the binary, so there would be
    // no script left to inspect the exit status or pause afterwards (see below) - a binary that
    // fails to start would just make the window flash and disappear, same failure mode the Windows
    // wrapper below already had to fix. This matters most for Git Bash, which - despite this POSIX
    // script - runs inside a Windows console window opened via "start" and closes just as abruptly.
    content << "${ProcessArgumentEscaping.shellQuoteSingle(binaryPath.toString())}"
    arguments.each { String argument -> content << " ${ProcessArgumentEscaping.shellQuoteSingle(argument)}" }
    content << '\n'
    content << 'status=$?\n'
    content << 'if [ "$status" -ne 0 ]; then\n'
    content << '  echo\n'
    content << '  echo "Exit code: $status"\n'
    content << '  printf "Press Enter to continue... "\n'
    content << '  read -r _dummy\n'
    content << 'fi\n'
    content << 'exit "$status"\n'
    content.toString()
  }

  static String windowsContent(Path workspace, Path binaryPath, Map<String, String> envVars, List<String> arguments = []) {
    StringBuilder content = new StringBuilder('@echo off\r\n')
    content << 'setlocal DisableDelayedExpansion\r\n'
    envVars.each { String name, String value ->
      content << "set \"${name}=${ProcessArgumentEscaping.escapeForCmdScript(value)}\"\r\n"
    }
    content << "cd /d \"${ProcessArgumentEscaping.escapeForCmdScript(workspace.toString())}\"\r\n"
    content << "\"${ProcessArgumentEscaping.escapeForCmdScript(binaryPath.toString())}\""
    arguments.each { String argument -> content << " \"${ProcessArgumentEscaping.escapeForCmdScript(argument)}\"" }
    content << '\r\n'
    // The console window this script runs in closes the instant the script finishes. Without this,
    // a binary that fails to start (missing dependency, bad argument, ...) makes the window flash
    // and disappear before anyone can read why - the failure looks like the launch button did nothing.
    content << 'set "ACCOUNTING_LAUNCH_EXIT=%ERRORLEVEL%"\r\n'
    content << 'if not "%ACCOUNTING_LAUNCH_EXIT%"=="0" (\r\n'
    content << '  echo.\r\n'
    content << '  echo Exit code: %ACCOUNTING_LAUNCH_EXIT%\r\n'
    content << '  pause\r\n'
    content << ')\r\n'
    content.toString()
  }
}
