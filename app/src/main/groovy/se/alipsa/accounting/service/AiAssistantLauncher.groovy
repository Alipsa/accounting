package se.alipsa.accounting.service

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
    boolean windows = adapterKind == TerminalAdapterKind.WINDOWS_TERMINAL
    Path script = AiWorkspacePaths.wrapperScript(workspace, client, UUID.randomUUID().toString(), windows)
    List<String> command = TerminalCommandBuilder.commandFor(adapterKind, adapterExecutable, workspace, script)
    Map<String, String> env = [:]
    if (client == AiClient.CODEX) { env.ACCOUNTING_MCP_TOKEN = token }
    String content = windows ? LaunchWrapperScript.windowsContent(workspace, binaryPath, env) :
        LaunchWrapperScript.unixContent(workspace, binaryPath, env)
    secretFileWriter.write(workspace, script, content.getBytes('UTF-8'), SecretFileKind.EXECUTABLE)
    try { processRunner.run(command, workspace) } finally { deleteScript(script) }
  }

  private void deleteScript(Path script) {
    try { fileDeleter.deleteIfExists(script) } catch (Exception cleanup) { log.log(Level.WARNING, "Could not delete ${script}", cleanup) }
  }

  void validatePreflight(TerminalAdapterKind adapterKind) {
    if (adapterKind == TerminalAdapterKind.WINDOWS_TERMINAL) {
      TerminalCommandBuilder.rejectUnsafeWorkspacePathForWindowsTerminal(AppPaths.aiWorkspaceDirectory())
    }
  }
}
