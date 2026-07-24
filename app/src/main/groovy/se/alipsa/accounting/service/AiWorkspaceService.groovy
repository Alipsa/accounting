package se.alipsa.accounting.service

import se.alipsa.accounting.domain.AiClient
import se.alipsa.accounting.domain.TerminalAdapterKind
import se.alipsa.accounting.support.AppPaths

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
  private final FileDeleter fileDeleter

  AiWorkspaceService() {
    this(new AiWorkspacePermissions(), new AtomicSecretFileWriter(), new FileSystemExecutableProbe(),
        { String name -> System.getenv(name) } as EnvironmentLookup,
        { Path path -> Files.deleteIfExists(path) } as FileDeleter)
  }

  AiWorkspaceService(AiWorkspacePermissions permissions, SecretFileWriter secretFileWriter, ExecutableProbe executableProbe,
      EnvironmentLookup environmentLookup, FileDeleter fileDeleter) {
    this.permissions = permissions
    this.secretFileWriter = secretFileWriter
    this.executableProbe = executableProbe
    this.pathBinaryResolver = new PathBinaryResolver(environmentLookup, executableProbe)
    this.fileDeleter = fileDeleter
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

  Path detectBinaryPath(AiClient client) { pathBinaryResolver.resolve(client.binaryName) }

  Tuple2<TerminalAdapterKind, Path> detectTerminalAdapter() {
    for (TerminalAdapterKind kind : TerminalAdapterKind.forCurrentOs()) {
      Path resolved = pathBinaryResolver.resolve(kind.defaultBinaryName)
      if (resolved != null) { return new Tuple2<TerminalAdapterKind, Path>(kind, resolved) }
    }
    null
  }

  boolean isValidExecutable(Path candidate) { candidate != null && executableProbe.isExecutableFile(candidate) }
}
