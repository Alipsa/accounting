package se.alipsa.accounting.ui

import se.alipsa.accounting.service.UpdateService
import se.alipsa.accounting.service.UpdateService.UpdateInfo
import se.alipsa.accounting.support.I18n

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Frame
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.logging.Level
import java.util.logging.Logger

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.SwingWorker

/**
 * Dialog for checking, downloading, and applying application updates.
 */
final class UpdateDialog extends JDialog {

  private static final Logger log = Logger.getLogger(UpdateDialog.name)

  private final UpdateService updateService = new UpdateService()
  private final JLabel statusLabel = new JLabel(I18n.instance.getString('updateDialog.status.checking'))
  private final JTextArea detailsArea = new JTextArea(6, 40)
  private final JProgressBar progressBar = new JProgressBar(0, 100)
  private final JButton downloadButton = new JButton(I18n.instance.getString('updateDialog.button.download'))
  private final JButton closeButton = new JButton(I18n.instance.getString('updateDialog.button.close'))
  private UpdateInfo updateInfo

  private UpdateDialog(Frame owner) {
    super(owner, I18n.instance.getString('updateDialog.title'), true)
    buildUi()
    pack()
    setLocationRelativeTo(owner)
    checkForUpdate()
  }

  static void showDialog(Frame owner) {
    new UpdateDialog(owner).setVisible(true)
  }

  private void buildUi() {
    setLayout(new BorderLayout(12, 12))
    ((JPanel) contentPane).setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16))

    statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0))
    add(statusLabel, BorderLayout.NORTH)

    detailsArea.editable = false
    detailsArea.lineWrap = true
    detailsArea.wrapStyleWord = true
    detailsArea.background = background
    add(detailsArea, BorderLayout.CENTER)

    JPanel bottomPanel = new JPanel(new BorderLayout(0, 8))
    progressBar.visible = false
    progressBar.stringPainted = true
    bottomPanel.add(progressBar, BorderLayout.NORTH)

    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT))
    downloadButton.enabled = false
    downloadButton.addActionListener { downloadAndApply() }
    closeButton.addActionListener { dispose() }
    buttonPanel.add(downloadButton)
    buttonPanel.add(closeButton)
    bottomPanel.add(buttonPanel, BorderLayout.SOUTH)

    add(bottomPanel, BorderLayout.SOUTH)
    setResizable(false)
  }

  private void checkForUpdate() {
    new SwingWorker<UpdateInfo, Void>() {
      @Override
      protected UpdateInfo doInBackground() {
        updateService.checkForUpdate()
      }

      @Override
      protected void done() {
        try {
          updateInfo = get()
          displayUpdateInfo()
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
          showError(I18n.instance.getString('updateDialog.error.interrupted'))
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          log.log(Level.WARNING, 'Update check failed.', cause)
          showError(I18n.instance.format('updateDialog.error.checkFailed', cause.message ?: cause.class.simpleName))
        }
      }
    }.execute()
  }

  private void displayUpdateInfo() {
    if (updateInfo.updateAvailable) {
      statusLabel.text = I18n.instance.format('updateDialog.status.available', updateInfo.availableVersion)
      statusLabel.foreground = new Color(22, 101, 52)
      detailsArea.text = I18n.instance.format('updateDialog.details.available',
          updateInfo.currentVersion, updateInfo.availableVersion)
      downloadButton.enabled = updateInfo.downloadUrl != null
      if (updateInfo.downloadUrl == null) {
        detailsArea.text += '\n\n' + I18n.instance.getString('updateDialog.details.noDistZip')
      }
    } else {
      statusLabel.text = I18n.instance.getString('updateDialog.status.upToDate')
      statusLabel.foreground = new Color(22, 101, 52)
      detailsArea.text = I18n.instance.format('updateDialog.details.upToDate', updateInfo.currentVersion)
    }
    pack()
  }

  private void downloadAndApply() {
    Path installDir = updateService.installationJarDirectory()
    if (installDir == null) {
      showError(I18n.instance.getString('updateDialog.error.cannotDetectInstall'))
      return
    }

    int confirmation = JOptionPane.showConfirmDialog(
        this,
        I18n.instance.format('updateDialog.confirm.message', updateInfo.availableVersion),
        I18n.instance.getString('updateDialog.confirm.title'),
        JOptionPane.OK_CANCEL_OPTION
    )
    if (confirmation != JOptionPane.OK_OPTION) {
      return
    }

    downloadButton.enabled = false
    closeButton.enabled = false
    progressBar.visible = true
    progressBar.value = 0
    statusLabel.text = I18n.instance.getString('updateDialog.status.downloading')
    pack()

    new SwingWorker<Path, Integer>() {
      @Override
      protected Path doInBackground() {
        updateService.downloadUpdate(updateInfo, { int percent ->
          SwingUtilities.invokeLater { progressBar.value = percent }
        } as Closure<Void>)
      }

      @Override
      protected void done() {
        try {
          Path downloadedZip = get()
          statusLabel.text = I18n.instance.getString('updateDialog.status.applying')
          progressBar.indeterminate = true
          updateService.applyUpdateAndRestart(downloadedZip)
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt()
          resetAfterError()
          showError(I18n.instance.getString('updateDialog.error.interrupted'))
        } catch (ExecutionException exception) {
          Throwable cause = exception.cause ?: exception
          log.log(Level.WARNING, 'Update download failed.', cause)
          resetAfterError()
          showError(I18n.instance.format('updateDialog.error.downloadFailed',
              cause.message ?: cause.class.simpleName))
        } catch (Exception exception) {
          log.log(Level.WARNING, 'Update apply failed.', exception)
          resetAfterError()
          showError(I18n.instance.format('updateDialog.error.downloadFailed',
              exception.message ?: exception.class.simpleName))
        }
      }
    }.execute()
  }

  private void resetAfterError() {
    downloadButton.enabled = true
    closeButton.enabled = true
    progressBar.visible = false
    progressBar.indeterminate = false
  }

  private void showError(String message) {
    statusLabel.text = I18n.instance.getString('updateDialog.status.error')
    statusLabel.foreground = new Color(153, 27, 27)
    detailsArea.text = message
    pack()
  }
}
