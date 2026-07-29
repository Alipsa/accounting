package se.alipsa.accounting.service

import se.alipsa.accounting.domain.AiClient
import se.alipsa.accounting.domain.TerminalAdapterKind
import se.alipsa.accounting.support.AppPaths
import se.alipsa.accounting.support.GitBashLocator

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

/** Owns workspace creation, client files, executable detection, and cleanup. */
final class AiWorkspaceService {

  private static final Logger log = Logger.getLogger(AiWorkspaceService.name)
  private final AiWorkspacePermissions permissions
  private final SecretFileWriter secretFileWriter
  private final ExecutableProbe executableProbe
  private final PathBinaryResolver pathBinaryResolver
  private final EnvironmentLookup environmentLookup
  private final FileDeleter fileDeleter
  private final GitBashLocator gitBashLocator

  AiWorkspaceService() {
    this(new AiWorkspacePermissions(), new AtomicSecretFileWriter(), new FileSystemExecutableProbe(),
        { String name -> System.getenv(name) } as EnvironmentLookup,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter, new GitBashLocator())
  }

  AiWorkspaceService(AiWorkspacePermissions permissions, SecretFileWriter secretFileWriter, ExecutableProbe executableProbe,
      EnvironmentLookup environmentLookup, FileDeleter fileDeleter) {
    this(permissions, secretFileWriter, executableProbe, environmentLookup, fileDeleter, new GitBashLocator())
  }

  AiWorkspaceService(AiWorkspacePermissions permissions, SecretFileWriter secretFileWriter, ExecutableProbe executableProbe,
      EnvironmentLookup environmentLookup, FileDeleter fileDeleter, GitBashLocator gitBashLocator) {
    this.permissions = permissions
    this.secretFileWriter = secretFileWriter
    this.executableProbe = executableProbe
    this.pathBinaryResolver = new PathBinaryResolver(environmentLookup, executableProbe)
    this.environmentLookup = environmentLookup
    this.fileDeleter = fileDeleter
    this.gitBashLocator = gitBashLocator
  }

  void ensureWorkspace() {
    AppPaths.ensureAiWorkspaceHome()
    Path workspace = AppPaths.aiWorkspaceDirectory()
    permissions.ensureDirectory(workspace, workspace)
  }

  void refreshClientFiles(AiClient client, String endpoint, String token) {
    ensureWorkspace()
    Path workspace = AppPaths.aiWorkspaceDirectory()
    Path config = AiWorkspacePaths.configFile(workspace, client)
    writeDataFile(workspace, config, AiClientConfigWriter.configContent(client, endpoint, token).getBytes('UTF-8'))
    byte[] skill = resourceBytes('/accounting-mcp.md')
    byte[] profile = resourceBytes('/assistant-profile.md')
    Path instructions = AiWorkspacePaths.instructionsFile(workspace, client)
    if (client == AiClient.CLAUDE) {
      writeDataFile(workspace, instructions, skill)
      writeDataFile(workspace, AiWorkspacePaths.assistantProfileFile(workspace, client), profile)
      Path settingsLocal = AiWorkspacePaths.settingsLocalFile(workspace)
      permissions.verifyNoSymlinksInPath(workspace, settingsLocal)
      String existingSettings = Files.exists(settingsLocal) ? Files.readString(settingsLocal) : null
      String mergedSettings = AiWorkspaceMcpSettings.mergeSettingsLocal(existingSettings)
      writeDataFile(workspace, settingsLocal, mergedSettings.getBytes('UTF-8'))
    } else {
      writeDataFile(workspace, instructions, (new String(profile, 'UTF-8') + '\n\n' + new String(skill, 'UTF-8')).getBytes('UTF-8'))
    }
  }

  private void writeDataFile(Path workspace, Path file, byte[] content) {
    permissions.verifyNoSymlinksInPath(workspace, file)
    permissions.ensureDirectory(workspace, file.parent)
    permissions.verifyNoSymlinksInPath(workspace, file)
    secretFileWriter.write(workspace, file, content, SecretFileKind.DATA)
  }

  private static byte[] resourceBytes(String name) {
    InputStream stream = AiWorkspaceService.getResourceAsStream(name)
    if (stream == null) { throw new IllegalStateException("The ${name} resource is missing from the classpath.") }
    try { stream.bytes } finally { stream.close() }
  }

  PurgeResult purgeAllSecrets() {
    Path workspace = AppPaths.aiWorkspaceDirectory()
    List<Path> removed = []
    List<Path> failed = []
    if (Files.isSymbolicLink(workspace)) { failed << workspace; return new PurgeResult(removed, failed) }
    if (Files.isDirectory(workspace)) {
      AiClient.values().each { AiClient client -> deleteIfSafe(workspace, AiWorkspacePaths.configFile(workspace, client), removed, failed) }
      try {
        Files.newDirectoryStream(workspace, '.launch-*').withCloseable { stream ->
          stream.each { Path wrapper -> deleteIfSafe(workspace, wrapper, removed, failed) }
        }
      } catch (Exception exception) {
        log.log(Level.WARNING, "Could not list wrapper scripts in ${workspace}", exception)
        failed << workspace.resolve('.launch-*')
      }
    }
    new PurgeResult(removed, failed)
  }

  private void deleteIfSafe(Path root, Path path, List<Path> removed, List<Path> failed) {
    try {
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { return }
      permissions.verifyNoSymlinksInPath(root, path)
      if (fileDeleter.deleteIfExists(path)) { removed << path }
    } catch (Exception exception) {
      log.log(Level.WARNING, "Could not delete ${path}", exception)
      failed << path
    }
  }

  Path detectBinaryPath(AiClient client) {
    Path fromPath = pathBinaryResolver.resolve(client.binaryName)
    if (fromPath != null) { return fromPath }
    if (client == AiClient.KIMI) {
      Path kimiDefault = Path.of(System.getProperty('user.home'), '.kimi-code', 'bin', client.binaryName)
      if (executableProbe.isExecutableFile(kimiDefault)) { return kimiDefault.toAbsolutePath().normalize() }
    }
    null
  }

  Tuple2<TerminalAdapterKind, Path> detectTerminalAdapter() {
    List<TerminalAdapterKind> kinds = TerminalAdapterKind.forCurrentOs()
    if (kinds.contains(TerminalAdapterKind.WINDOWS_TERMINAL)) {
      Path wt = detectTerminalPath(TerminalAdapterKind.WINDOWS_TERMINAL)
      if (wt != null) { return new Tuple2<TerminalAdapterKind, Path>(TerminalAdapterKind.WINDOWS_TERMINAL, wt) }
      Path cmd = detectTerminalPath(TerminalAdapterKind.COMMAND_PROMPT)
      if (cmd != null) { return new Tuple2<TerminalAdapterKind, Path>(TerminalAdapterKind.COMMAND_PROMPT, cmd) }
      return null
    }
    for (TerminalAdapterKind kind : kinds) {
      Path resolved = detectTerminalPath(kind)
      if (resolved != null) { return new Tuple2<TerminalAdapterKind, Path>(kind, resolved) }
    }
    null
  }

  /**
   * Looks up the path for one specific terminal adapter kind, regardless of what else is
   * available. Used when the caller (e.g. an explicit "Detect" click, or restoring the field for
   * whichever kind is currently selected) already knows which kind it wants, as opposed to
   * {@link #detectTerminalAdapter()}'s "find whatever is best available" search.
   */
  Path detectTerminalPath(TerminalAdapterKind kind) {
    if (kind == TerminalAdapterKind.WINDOWS_TERMINAL) {
      return detectWindowsTerminal()
    }
    if (kind == TerminalAdapterKind.GIT_BASH) {
      return detectGitBash()
    }
    pathBinaryResolver.resolve(kind.defaultBinaryName)
  }

  private Path detectWindowsTerminal() {
    Path fromPath = pathBinaryResolver.resolve(TerminalAdapterKind.WINDOWS_TERMINAL.defaultBinaryName)
    if (fromPath != null) { return fromPath }
    Path localAppData = localAppDataPath()
    if (localAppData != null) {
      Path alias = localAppData.resolve('Microsoft').resolve('WindowsApps').resolve('wt.exe')
      if (executableProbe.isExecutableFile(alias)) {
        return alias.toAbsolutePath().normalize()
      }
    }
    null
  }

  private Path detectGitBash() {
    Path fromRegistry = gitBashLocator.findBash()
    if (fromRegistry != null && executableProbe.isExecutableFile(fromRegistry)) {
      return fromRegistry
    }
    Path fromPath = pathBinaryResolver.resolve(TerminalAdapterKind.GIT_BASH.defaultBinaryName)
    if (fromPath != null) { return fromPath }
    // git-bash.exe itself is usually not on PATH, but bash.exe is. Derive the Git install root
    // from bash.exe's location (C:\Program Files\Git\bin\bash.exe -> C:\Program Files\Git).
    Path bash = pathBinaryResolver.resolve('bash.exe')
    if (bash != null) {
      Path gitRoot = bash.parent?.parent
      if (gitRoot != null) {
        Path candidate = gitRoot.resolve(TerminalAdapterKind.GIT_BASH.defaultBinaryName)
        if (executableProbe.isExecutableFile(candidate)) {
          return candidate.toAbsolutePath().normalize()
        }
      }
    }
    null
  }

  private Path localAppDataPath() {
    String localAppData = environmentLookup.getenv('LOCALAPPDATA')
    localAppData ? Path.of(localAppData) : null
  }

  boolean isValidExecutable(Path candidate) { candidate != null && executableProbe.isExecutableFile(candidate) }
}
