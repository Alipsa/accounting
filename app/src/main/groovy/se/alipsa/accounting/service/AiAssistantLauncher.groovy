package se.alipsa.accounting.service

import groovy.transform.PackageScope

import se.alipsa.accounting.domain.AiClient
import se.alipsa.accounting.domain.TerminalAdapterKind
import se.alipsa.accounting.support.AppPaths

import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

/** Writes a unique launch wrapper and opens it in the configured terminal. */
final class AiAssistantLauncher {

  private static final Logger log = Logger.getLogger(AiAssistantLauncher.name)

  @PackageScope
  static final String ASSISTANT_SESSION_NAME = 'Alipsa Accounting AI Assistant'

  private final AiWorkspacePermissions permissions
  private final SecretFileWriter secretFileWriter
  private final ExecutableProbe executableProbe
  private final ProcessRunner processRunner
  private final FileDeleter fileDeleter

  AiAssistantLauncher() {
    this(new AiWorkspacePermissions(), new AtomicSecretFileWriter(), new FileSystemExecutableProbe(),
        { List<String> command, Path dir -> new ProcessBuilder(command).directory(dir.toFile()).start() } as ProcessRunner,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)
  }

  AiAssistantLauncher(AiWorkspacePermissions permissions, SecretFileWriter secretFileWriter, ExecutableProbe executableProbe,
      ProcessRunner processRunner, FileDeleter fileDeleter) {
    this.permissions = permissions
    this.secretFileWriter = secretFileWriter
    this.executableProbe = executableProbe
    this.processRunner = processRunner
    this.fileDeleter = fileDeleter
  }

  void launch(AiClient client, Path binaryPath, TerminalAdapterKind adapterKind, Path adapterExecutable, String token) {
    if (!executableProbe.isExecutableFile(binaryPath) || !executableProbe.isExecutableFile(adapterExecutable)) {
      throw new IllegalArgumentException('Configured AI CLI and terminal paths must be executable files.')
    }
    AppPaths.ensureAiWorkspaceHome()
    Path workspace = AppPaths.aiWorkspaceDirectory()
    permissions.ensureDirectory(workspace, workspace)
    boolean windows = isWindowsAdapter(adapterKind)
    Path script = AiWorkspacePaths.wrapperScript(workspace, client, UUID.randomUUID().toString(), windows)
    List<String> command = TerminalCommandBuilder.commandFor(adapterKind, adapterExecutable, workspace, script)
    Map<String, String> env = [:]
    if (client == AiClient.CODEX) { env.ACCOUNTING_MCP_TOKEN = token }
    List<String> arguments = client == AiClient.CLAUDE ? ['--name', ASSISTANT_SESSION_NAME] : []
    String content = windows ? LaunchWrapperScript.windowsContent(workspace, binaryPath, env, arguments) :
        LaunchWrapperScript.unixContent(workspace, binaryPath, env, arguments)
    secretFileWriter.write(workspace, script, content.getBytes('UTF-8'), SecretFileKind.EXECUTABLE)
    log.info("Launching ${client} via ${adapterKind}: ${command} (workspace=${workspace})")
    try {
      processRunner.run(command, workspace)
    } catch (Exception exception) {
      // ProcessBuilder only returns after it has handed the wrapper to the terminal.  If that
      // hand-off itself fails, the script was never usable and can be removed immediately.
      log.log(Level.WARNING, "Could not hand the AI assistant launch off to ${adapterKind}.", exception)
      fileDeleter.deleteIfExists(script)
      throw exception
    }
    log.info("Handed the AI assistant launch off to ${adapterKind}.")
    // A terminal may not open its command until after ProcessBuilder returns. Keep this
    // token-bearing wrapper until the normal workspace purge at application exit/startup.
  }

  void validatePreflight(TerminalAdapterKind adapterKind) {
    if (isWindowsAdapter(adapterKind)) {
      TerminalCommandBuilder.rejectUnsafeWorkspacePathForWindows(AppPaths.aiWorkspaceDirectory())
    }
  }

  private static boolean isWindowsAdapter(TerminalAdapterKind kind) {
    kind == TerminalAdapterKind.WINDOWS_TERMINAL || kind == TerminalAdapterKind.COMMAND_PROMPT
  }
}
