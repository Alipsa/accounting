package se.alipsa.accounting.ui

import se.alipsa.accounting.service.BackupResult
import se.alipsa.accounting.service.BackupService
import se.alipsa.accounting.service.RestoreResult
import se.alipsa.accounting.service.SystemDiagnosticsService
import se.alipsa.accounting.service.SystemDiagnosticsSnapshot
import se.alipsa.accounting.service.SystemDocumentationService
import se.alipsa.accounting.service.UserManualService

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Frame
import java.nio.file.Path
import java.util.concurrent.ExecutionException

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Shows diagnostics, system documentation and backup/restore actions.
 */
final class SystemDocumentationPanel extends JPanel {

  private final SystemDocumentationService documentationService
  private final SystemDiagnosticsService diagnosticsService
  private final BackupService backupService
  private final UserManualService userManualService

  private final JTextArea diagnosticsArea = new JTextArea(8, 48)
  private final JTextArea documentationArea = new JTextArea(24, 48)
  private final JTextArea messageArea = new JTextArea(4, 48)
  private final JButton refreshButton = new JButton('Uppdatera')

  SystemDocumentationPanel(
      SystemDocumentationService documentationService,
      SystemDiagnosticsService diagnosticsService,
      BackupService backupService,
      UserManualService userManualService
  ) {
    this.documentationService = documentationService
    this.diagnosticsService = diagnosticsService
    this.backupService = backupService
    this.userManualService = userManualService
    buildUi()
    refreshAll()
  }

  private void buildUi() {
    setLayout(new BorderLayout(12, 12))
    setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16))

    diagnosticsArea.editable = false
    diagnosticsArea.lineWrap = true
    diagnosticsArea.wrapStyleWord = true
    diagnosticsArea.background = background

    documentationArea.editable = false
    documentationArea.lineWrap = true
    documentationArea.wrapStyleWord = true

    messageArea.editable = false
    messageArea.lineWrap = true
    messageArea.wrapStyleWord = true
    messageArea.background = background

    add(buildActions(), BorderLayout.NORTH)
    JSplitPane splitPane = new JSplitPane(
        JSplitPane.VERTICAL_SPLIT,
        new JScrollPane(diagnosticsArea),
        new JScrollPane(documentationArea)
    )
    splitPane.resizeWeight = 0.25d
    add(splitPane, BorderLayout.CENTER)
    add(new JScrollPane(messageArea), BorderLayout.SOUTH)
  }

  private JPanel buildActions() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    refreshButton.addActionListener { refreshAll() }
    JButton exportDocsButton = new JButton('Exportera dokumentation')
    exportDocsButton.addActionListener { exportDocumentation(exportDocsButton) }
    JButton backupButton = new JButton('Skapa backup...')
    backupButton.addActionListener { createBackup() }
    JButton restoreButton = new JButton('Återställ backup...')
    restoreButton.addActionListener { restoreBackup() }
    JButton manualButton = new JButton('Öppna manual')
    manualButton.addActionListener { UserManualDialog.showDialog(ownerFrame(), userManualService) }
    panel.add(refreshButton)
    panel.add(exportDocsButton)
    panel.add(backupButton)
    panel.add(restoreButton)
    panel.add(manualButton)
    panel
  }

  private void refreshAll() {
    refreshButton.enabled = false
    diagnosticsArea.text = 'Laddar diagnostik...'
    documentationArea.text = 'Laddar systemdokumentation...'
    messageArea.foreground = new Color(33, 33, 33)
    messageArea.text = 'Hämtar systemdiagnostik och dokumentation...'
    new SwingWorker<RefreshPayload, Void>() {
      @Override
      protected RefreshPayload doInBackground() {
        new RefreshPayload(renderDiagnostics(), documentationService.renderDocumentation())
      }

      @Override
      protected void done() {
        try {
          RefreshPayload payload = get()
          diagnosticsArea.text = payload.diagnostics
          documentationArea.text = payload.documentation
          showInfo('Systemdiagnostik och dokumentation uppdaterad.')
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
          showError('Uppdateringen av systeminformationen avbröts.')
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          diagnosticsArea.text = ''
          documentationArea.text = ''
          showError(cause.message ?: 'Systeminformationen kunde inte hämtas.')
        } finally {
          refreshButton.enabled = true
        }
      }
    }.execute()
  }

  private String renderDiagnostics() {
    SystemDiagnosticsSnapshot snapshot = diagnosticsService.snapshot()
    [
        "Applikationskatalog: ${snapshot.applicationHome}",
        "Databasfil: ${snapshot.databaseFile}",
        "Schema-version: ${snapshot.schemaVersion}/${snapshot.expectedSchemaVersion}",
        snapshot.latestBackup == null
            ? 'Senaste backup: ingen'
            : "Senaste backup: ${snapshot.latestBackup.backupPath.fileName} ${snapshot.latestBackup.createdAt}",
        snapshot.latestSieExportAt == null
            ? 'Senaste SIE-export: ingen'
            : "Senaste SIE-export: ${snapshot.latestSieExportAt}",
        snapshot.verificationReport.ok
            ? 'Startup-verifiering: OK'
            : "Startup-verifiering: ${snapshot.verificationReport.errors.size()} fel"
    ].join('\n')
  }

  private void exportDocumentation(JButton exportButton) {
    exportButton.enabled = false
    showInfo('Exporterar systemdokumentation...')
    new SwingWorker<Path, Void>() {
      @Override
      protected Path doInBackground() {
        documentationService.exportDocumentation()
      }

      @Override
      protected void done() {
        try {
          Path path = get()
          showInfo("Systemdokumentationen exporterades till ${path}.")
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
          showError('Exporten av systemdokumentation avbröts.')
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          showError(cause.message ?: 'Systemdokumentationen kunde inte exporteras.')
        } finally {
          exportButton.enabled = true
        }
      }
    }.execute()
  }

  private void createBackup() {
    JFileChooser chooser = new JFileChooser()
    chooser.fileFilter = new FileNameExtensionFilter('ZIP-backup (*.zip)', 'zip')
    chooser.selectedFile = new File('alipsa-accounting-backup.zip')
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
      return
    }
    Path target = chooser.selectedFile.toPath()
    showInfo('Skapar backup...')
    new SwingWorker<BackupResult, Void>() {
      @Override
      protected BackupResult doInBackground() {
        backupService.createBackup(target)
      }

      @Override
      protected void done() {
        try {
          BackupResult result = get()
          refreshAll()
          showInfo("Backup skapad: ${result.summary.backupPath}")
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
          showError('Backup avbröts.')
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          showError(cause.message ?: 'Backup kunde inte skapas.')
        }
      }
    }.execute()
  }

  private void restoreBackup() {
    JFileChooser chooser = new JFileChooser()
    chooser.fileFilter = new FileNameExtensionFilter('ZIP-backup (*.zip)', 'zip')
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return
    }
    Path backupPath = chooser.selectedFile.toPath()
    int choice = javax.swing.JOptionPane.showConfirmDialog(
        this,
        'Återställning ersätter nuvarande databas, bilagor och rapportarkiv. Fortsätt?',
        'Bekräfta återställning',
        javax.swing.JOptionPane.OK_CANCEL_OPTION
    )
    if (choice != javax.swing.JOptionPane.OK_OPTION) {
      return
    }
    showInfo('Återställer backup...')
    new SwingWorker<RestoreResult, Void>() {
      @Override
      protected RestoreResult doInBackground() {
        backupService.restoreBackup(backupPath)
      }

      @Override
      protected void done() {
        try {
          RestoreResult result = get()
          refreshAll()
          showInfo("Backup återställd från ${result.backupPath.fileName}.")
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
          showError('Återställning avbröts.')
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          showError(cause.message ?: 'Backup kunde inte återställas.')
        }
      }
    }.execute()
  }

  private Frame ownerFrame() {
    Object window = SwingUtilities.getWindowAncestor(this)
    window instanceof Frame ? (Frame) window : null
  }

  private void showInfo(String text) {
    messageArea.foreground = new Color(22, 101, 52)
    messageArea.text = text
  }

  private void showError(String text) {
    messageArea.foreground = new Color(153, 27, 27)
    messageArea.text = text
  }

  private static final class RefreshPayload {

    final String diagnostics
    final String documentation

    RefreshPayload(String diagnostics, String documentation) {
      this.diagnostics = diagnostics
      this.documentation = documentation
    }
  }
}
