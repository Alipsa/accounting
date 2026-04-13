package se.alipsa.accounting.ui

import se.alipsa.accounting.service.UserManualService
import se.alipsa.accounting.support.I18n

import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Frame

import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Displays the bundled end-user manual.
 */
final class UserManualDialog extends JDialog {

  UserManualDialog(Frame owner, UserManualService userManualService) {
    super(owner, I18n.instance.getString('userManualDialog.title'), true)
    setLayout(new BorderLayout(12, 12))
    ((JPanel) contentPane).setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12))

    JTextArea textArea = new JTextArea(28, 72)
    textArea.editable = false
    textArea.lineWrap = true
    textArea.wrapStyleWord = true
    textArea.text = userManualService.loadManual()

    JButton closeButton = new JButton(I18n.instance.getString('userManualDialog.button.close'))
    closeButton.addActionListener { dispose() }
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT))
    actions.add(closeButton)

    add(new JScrollPane(textArea), BorderLayout.CENTER)
    add(actions, BorderLayout.SOUTH)

    pack()
    setLocationRelativeTo(owner)
  }

  static void showDialog(Frame owner, UserManualService userManualService) {
    new UserManualDialog(owner, userManualService).visible = true
  }
}
