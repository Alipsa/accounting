package se.alipsa.accounting.ui

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

import se.alipsa.accounting.domain.TerminalAdapterKind
import se.alipsa.accounting.service.AiAssistantLauncher
import se.alipsa.accounting.service.AiWorkspacePermissions
import se.alipsa.accounting.service.AiWorkspaceService
import se.alipsa.accounting.service.EnvironmentLookup
import se.alipsa.accounting.service.ExecutableProbe
import se.alipsa.accounting.service.FileDeleter
import se.alipsa.accounting.service.SecretFileKind
import se.alipsa.accounting.service.SecretFileWriter
import se.alipsa.accounting.service.UserPreferencesService
import se.alipsa.accounting.support.AppPaths
import se.alipsa.accounting.support.I18n

import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences

class AiAssistantLauncherSectionTest {

  @TempDir
  Path tempDir

  private String previousWorkspaceHome
  private Preferences preferences
  private Locale previousLocale

  @BeforeEach
  void captureWorkspaceHome() {
    previousWorkspaceHome = System.getProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY)
    System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, tempDir.resolve('home').toString())
    preferences = Preferences.userNodeForPackage(AiAssistantLauncherSectionTest).node(UUID.randomUUID().toString())
    previousLocale = I18n.instance.locale
    I18n.instance.locale = Locale.ENGLISH
  }

  @AfterEach
  void restoreWorkspaceHome() {
    if (previousWorkspaceHome == null) {
      System.clearProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY)
    } else {
      System.setProperty(AppPaths.AI_WORKSPACE_HOME_OVERRIDE_PROPERTY, previousWorkspaceHome)
    }
    I18n.instance.locale = previousLocale
  }

  @Test
  void explicitBinaryDetectionFailureShowsLocalizedFeedback() {
    List<String> errors = []
    ErrorDisplay errorDisplay = { String title, String message -> errors << message } as ErrorDisplay
    AiAssistantLauncherSection section = newSection(errorDisplay)

    section.binaryField.text = ''
    section.detectBinaryButton.doClick()

    assertEquals(1, errors.size())
    assertTrue(errors.first().contains('Could not find'))
  }

  @Test
  void explicitTerminalDetectionFailureShowsLocalizedFeedback() {
    List<String> errors = []
    ErrorDisplay errorDisplay = { String title, String message -> errors << message } as ErrorDisplay
    AiAssistantLauncherSection section = newSection(errorDisplay)

    section.terminalPathField.text = ''
    section.detectTerminalButton.doClick()

    assertEquals(1, errors.size())
    assertTrue(errors.first().contains('Could not find'))
  }

  @Test
  void automaticInitialDetectionStaysSilentWhenNothingIsFound() {
    List<String> errors = []
    ErrorDisplay errorDisplay = { String title, String message -> errors << message } as ErrorDisplay

    newSection(errorDisplay)

    assertTrue(errors.isEmpty())
  }

  // Regression coverage for a real bug: a path detected/typed for one terminal adapter kind
  // (e.g. Windows Terminal) used to keep showing - and being launched through - after switching
  // the dropdown to a different, incompatible kind (e.g. Command Prompt).
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void switchingTerminalKindDoesNotLeakThePreviousKindsPath() {
    ErrorDisplay errorDisplay = { String title, String message -> } as ErrorDisplay
    AiAssistantLauncherSection section = newSection(errorDisplay)

    section.terminalKindCombo.selectedItem = TerminalAdapterKind.WINDOWS_TERMINAL
    section.terminalPathField.text = 'C:\\Tools\\wt.exe'

    section.terminalKindCombo.selectedItem = TerminalAdapterKind.COMMAND_PROMPT

    assertEquals('', section.terminalPathField.text)
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void switchingBackToAnAdapterKindRestoresItsOwnPreviouslyStoredPath() {
    ErrorDisplay errorDisplay = { String title, String message -> } as ErrorDisplay
    AiAssistantLauncherSection section = newSection(errorDisplay)
    new UserPreferencesService(preferences).setTerminalPath(TerminalAdapterKind.COMMAND_PROMPT, 'C:\\WINDOWS\\system32\\cmd.exe')

    section.terminalKindCombo.selectedItem = TerminalAdapterKind.WINDOWS_TERMINAL
    section.terminalKindCombo.selectedItem = TerminalAdapterKind.COMMAND_PROMPT

    assertEquals('C:\\WINDOWS\\system32\\cmd.exe', section.terminalPathField.text)
  }

  // Regression coverage: the "Detect" button used to always search for Windows Terminal first
  // and silently switch the dropdown to it, ignoring whatever adapter kind the user had selected
  // (e.g. Command Prompt) - so it could never actually find cmd.exe on a machine where wt.exe was
  // also installed.
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void detectButtonTargetsTheCurrentlySelectedAdapterKind() {
    List<String> errors = []
    ErrorDisplay errorDisplay = { String title, String message -> errors << message } as ErrorDisplay
    Path cmdExe = tempDir.resolve('bin').resolve('cmd.exe')
    Path wtExe = tempDir.resolve('bin').resolve('wt.exe')
    AiWorkspaceService workspaceService = workspaceServiceThatFinds(
        ['PATH': tempDir.resolve('bin').toString()], { Path path -> path == cmdExe || path == wtExe } as ExecutableProbe)
    AiAssistantLauncherSection section = newSection(errorDisplay, workspaceService)

    section.terminalKindCombo.selectedItem = TerminalAdapterKind.COMMAND_PROMPT
    section.detectTerminalButton.doClick()

    assertEquals(cmdExe.toAbsolutePath().normalize().toString(), section.terminalPathField.text)
    assertTrue(errors.isEmpty())
  }

  private AiAssistantLauncherSection newSection(ErrorDisplay errorDisplay) {
    newSection(errorDisplay, workspaceServiceThatFindsNothing())
  }

  private AiAssistantLauncherSection newSection(ErrorDisplay errorDisplay, AiWorkspaceService workspaceService) {
    UserPreferencesService preferencesService = new UserPreferencesService(preferences)
    AiAssistantLauncher launcher = new AiAssistantLauncher()
    BackgroundTaskRunner runner = { Closure backgroundWork, Closure onDone, Closure onError ->
      try {
        onDone.call(backgroundWork.call())
      } catch (Exception exception) {
        onError.call(exception)
      }
    } as BackgroundTaskRunner
    new AiAssistantLauncherSection(preferencesService, workspaceService, launcher, runner, errorDisplay)
  }

  private AiWorkspaceService workspaceServiceThatFindsNothing() {
    workspaceServiceThatFinds([:], { Path path -> false } as ExecutableProbe)
  }

  private AiWorkspaceService workspaceServiceThatFinds(Map<String, String> environment, ExecutableProbe probe) {
    EnvironmentLookup lookup = { String name -> environment[name] } as EnvironmentLookup
    SecretFileWriter writer = { Path root, Path target, byte[] content, SecretFileKind kind -> } as SecretFileWriter
    FileDeleter deleter = { Path path -> Files.deleteIfExists(path) } as FileDeleter
    new AiWorkspaceService(new AiWorkspacePermissions(), writer, probe, lookup, deleter)
  }

}
