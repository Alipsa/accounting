package se.alipsa.accounting.ui

import se.alipsa.accounting.service.BackupResult
import se.alipsa.accounting.service.BackupService
import se.alipsa.accounting.service.RestoreResult
import se.alipsa.accounting.service.SystemDiagnosticsService
import se.alipsa.accounting.service.SystemDiagnosticsSnapshot
import se.alipsa.accounting.service.SystemDocumentationService
import se.alipsa.accounting.service.UserManualService
import se.alipsa.accounting.support.I18n

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Frame
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
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
final class SystemDocumentationPanel extends JPanel implements PropertyChangeListener {

  private final SystemDocumentationService documentationService
  private final SystemDiagnosticsService diagnosticsService
  private final BackupService backupService
  private final UserManualService userManualService

  private final JTextArea diagnosticsArea = new JTextArea(8, 48)
  private final JTextArea documentationArea = new JTextArea(24, 48)
  private final JTextArea messageArea = new JTextArea(4, 48)
  private JButton refreshButton
  private JButton exportDocsButton
  private JButton backupButton
  private JButton restoreButton
  private JButton manualButton

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
    I18n.instance.addLocaleChangeListener(this)
    buildUi()
    refreshAll()
  }

  @Override
  void propertyChange(PropertyChangeEvent evt) {
    if ('locale' == evt.propertyName) {
      SwingUtilities.invokeLater { applyLocale() }
    }
  }

  private void applyLocale() {
    refreshButton.text = I18n.instance.getString('systemDocumentationPanel.button.refresh')
    exportDocsButton.text = I18n.instance.getString('systemDocumentationPanel.button.exportDocs')
    backupButton.text = I18n.instance.getString('systemDocumentationPanel.button.backup')
    restoreButton.text = I18n.instance.getString('systemDocumentationPanel.button.restore')
    manualButton.text = I18n.instance.getString('systemDocumentationPanel.button.manual')
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
    refreshButton = new JButton(I18n.instance.getString('systemDocumentationPanel.button.refresh'))
    refreshButton.addActionListener { refreshAll() }
    exportDocsButton = new JButton(I18n.instance.getString('systemDocumentationPanel.button.exportDocs'))
    exportDocsButton.addActionListener { exportDocumentation(exportDocsButton) }
    backupButton = new JButton(I18n.instance.getString('systemDocumentationPanel.button.backup'))
    backupButton.addActionListener { createBackup() }
    restoreButton = new JButton(I18n.instance.getString('systemDocumentationPanel.button.restore'))
    restoreButton.addActionListener { restoreBackup() }
    manualButton = new JButton(I18n.instance.getString('systemDocumentationPanel.button.manual'))
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
    diagnosticsArea.text = I18n.instance.getString('systemDocumentationPanel.status.loadingDiagnostics')
    documentationArea.text = I18n.instance.getString('systemDocumentationPanel.status.loadingDocumentation')
    messageArea.foreground = new Color(33, 33, 33)
    messageArea.text = I18n.instance.getString('systemDocumentationPanel.status.fetching')
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
          showInfo(I18n.instance.getString('systemDocumentationPanel.status.updated'))
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
          showError(I18n.instance.getString('systemDocumentationPanel.status.refreshInterrupted'))
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          diagnosticsArea.text = ''
          documentationArea.text = ''
          showError(cause.message ?: I18n.instance.getString('systemDocumentationPanel.status.refreshFailed'))
        } finally {
          refreshButton.enabled = true
        }
      }
    }.execute()
  }

  private String renderDiagnostics() {
    SystemDiagnosticsSnapshot snapshot = diagnosticsService.snapshot()
    [
        I18n.instance.format('systemDocumentationPanel.diagnostics.appHome', snapshot.applicationHome),
        I18n.instance.format('systemDocumentationPanel.diagnostics.dbFile', snapshot.databaseFile),
        I18n.instance.format('systemDocumentationPanel.diagnostics.schemaVersion',
            snapshot.schemaVersion, snapshot.expectedSchemaVersion),
        snapshot.latestBackup == null
            ? I18n.instance.getString('systemDocumentationPanel.diagnostics.latestBackup.none')
            : I18n.instance.format('systemDocumentationPanel.diagnostics.latestBackup',
            snapshot.latestBackup.backupPath.fileName, snapshot.latestBackup.createdAt),
        snapshot.latestSieExportAt == null
            ? I18n.instance.getString('systemDocumentationPanel.diagnostics.latestSieExport.none')
            : I18n.instance.format('systemDocumentationPanel.diagnostics.latestSieExport',
            snapshot.latestSieExportAt),
        snapshot.verificationReport.ok
            ? I18n.instance.getString('systemDocumentationPanel.diagnostics.verification.ok')
            : I18n.instance.format('systemDocumentationPanel.diagnostics.verification.errors',
            snapshot.verificationReport.errors.size() as Object)
    ].join('\n')
  }

  private void exportDocumentation(JButton exportButton) {
    exportButton.enabled = false
    showInfo(I18n.instance.getString('systemDocumentationPanel.status.exportingDocs'))
    new SwingWorker<Path, Void>() {
      @Override
      protected Path doInBackground() {
        documentationService.exportDocumentation()
      }

      @Override
      protected void done() {
        try {
          Path path = get()
          showInfo(I18n.instance.format('systemDocumentationPanel.status.docsExported', path))
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
          showError(I18n.instance.getString('systemDocumentationPanel.status.docsExportInterrupted'))
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          showError(cause.message ?: I18n.instance.getString('systemDocumentationPanel.status.docsExportFailed'))
        } finally {
          exportButton.enabled = true
        }
      }
    }.execute()
  }

  private void createBackup() {
    JFileChooser chooser = new JFileChooser()
    chooser.fileFilter = new FileNameExtensionFilter(
        I18n.instance.getString('systemDocumentationPanel.fileFilter.zip'), 'zip')
    chooser.selectedFile = new File('alipsa-accounting-backup.zip')
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
      return
    }
    Path target = chooser.selectedFile.toPath()
    showInfo(I18n.instance.getString('systemDocumentationPanel.status.creatingBackup'))
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
          showInfo(I18n.instance.format('systemDocumentationPanel.status.backupCreated', result.summary.backupPath))
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
          showError(I18n.instance.getString('systemDocumentationPanel.status.backupInterrupted'))
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          showError(cause.message ?: I18n.instance.getString('systemDocumentationPanel.status.backupFailed'))
        }
      }
    }.execute()
  }

  private void restoreBackup() {
    JFileChooser chooser = new JFileChooser()
    chooser.fileFilter = new FileNameExtensionFilter(
        I18n.instance.getString('systemDocumentationPanel.fileFilter.zip'), 'zip')
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return
    }
    Path backupPath = chooser.selectedFile.toPath()
    int choice = javax.swing.JOptionPane.showConfirmDialog(
        this,
        I18n.instance.getString('systemDocumentationPanel.confirm.restoreMessage'),
        I18n.instance.getString('systemDocumentationPanel.confirm.restoreTitle'),
        javax.swing.JOptionPane.OK_CANCEL_OPTION
    )
    if (choice != javax.swing.JOptionPane.OK_OPTION) {
      return
    }
    showInfo(I18n.instance.getString('systemDocumentationPanel.status.restoring'))
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
          showInfo(I18n.instance.format('systemDocumentationPanel.status.restored', result.backupPath.fileName))
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
          showError(I18n.instance.getString('systemDocumentationPanel.status.restoreInterrupted'))
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          showError(cause.message ?: I18n.instance.getString('systemDocumentationPanel.status.restoreFailed'))
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
