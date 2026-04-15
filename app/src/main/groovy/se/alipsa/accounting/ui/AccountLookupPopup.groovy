package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.support.I18n

import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.function.Consumer

import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Popup list for searching accounts by name or number.
 * Shown below a JTable cell during editing of the account description column.
 */
final class AccountLookupPopup {

  private static final int DEBOUNCE_MILLIS = 200
  private static final int MAX_VISIBLE_ROWS = 10

  private final AccountService accountService
  private final long companyId
  private final Consumer<Account> onSelect
  private final JPopupMenu popup = new JPopupMenu()
  private final DefaultListModel<String> listModel = new DefaultListModel<>()
  private final JList<String> resultList = new JList<>(listModel)
  private final Timer debounceTimer
  private List<Account> currentResults = []
  private JTextField activeEditor

  AccountLookupPopup(AccountService accountService, long companyId, Consumer<Account> onSelect) {
    this.accountService = accountService
    this.companyId = companyId
    this.onSelect = onSelect
    resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION
    resultList.addMouseListener(new MouseAdapter() {
      @Override
      void mouseClicked(MouseEvent event) {
        int index = resultList.locationToIndex(event.point)
        if (index >= 0) {
          resultList.selectedIndex = index
          selectCurrent()
        }
      }
    })
    resultList.addKeyListener(new KeyAdapter() {
      @Override
      void keyPressed(KeyEvent event) {
        if (event.keyCode == KeyEvent.VK_ENTER) {
          selectCurrent()
          event.consume()
        } else if (event.keyCode == KeyEvent.VK_ESCAPE) {
          hide()
          activeEditor?.requestFocusInWindow()
          event.consume()
        }
      }
    })
    JScrollPane scrollPane = new JScrollPane(resultList)
    popup.layout = new BorderLayout()
    popup.add(scrollPane, BorderLayout.CENTER)
    debounceTimer = new Timer(DEBOUNCE_MILLIS, { search() })
    debounceTimer.repeats = false
  }

  void attachToEditor(JTextField editor) {
    activeEditor = editor
    editor.document.addDocumentListener(new DocumentListener() {
      @Override
      void insertUpdate(DocumentEvent event) { scheduleSearch() }
      @Override
      void removeUpdate(DocumentEvent event) { scheduleSearch() }
      @Override
      void changedUpdate(DocumentEvent event) { scheduleSearch() }
    })
    editor.addKeyListener(new KeyAdapter() {
      @Override
      void keyPressed(KeyEvent event) {
        if (event.keyCode == KeyEvent.VK_DOWN && popup.visible) {
          if (resultList.selectedIndex < 0 && listModel.size() > 0) {
            resultList.selectedIndex = 0
          }
          resultList.requestFocusInWindow()
          event.consume()
        } else if (event.keyCode == KeyEvent.VK_UP && popup.visible) {
          if (resultList.selectedIndex < 0 && listModel.size() > 0) {
            resultList.selectedIndex = listModel.size() - 1
          }
          resultList.requestFocusInWindow()
          event.consume()
        } else if (event.keyCode == KeyEvent.VK_ESCAPE) {
          hide()
        } else if (event.keyCode == KeyEvent.VK_ENTER && popup.visible && currentResults.size() == 1) {
          resultList.selectedIndex = 0
          selectCurrent()
          event.consume()
        }
      }
    })
  }

  void hide() {
    popup.visible = false
    debounceTimer.stop()
  }

  private void scheduleSearch() {
    debounceTimer.restart()
  }

  private void search() {
    String query = activeEditor?.text?.trim()
    if (!query || query.length() < 1) {
      hide()
      return
    }
    currentResults = accountService.searchAccounts(companyId, query, null, true, false)
    listModel.clear()
    if (currentResults.isEmpty()) {
      listModel.addElement(I18n.instance.getString('voucherPanel.lookup.noMatches'))
      resultList.enabled = false
    } else {
      resultList.enabled = true
      currentResults.each { Account account ->
        listModel.addElement("${account.accountNumber} \u2014 ${account.accountName}" as String)
      }
    }
    resultList.visibleRowCount = Math.min(listModel.size(), MAX_VISIBLE_ROWS)
    popup.pack()
    if (activeEditor != null) {
      popup.show(activeEditor, 0, activeEditor.height)
    }
  }

  private void selectCurrent() {
    int index = resultList.selectedIndex
    if (index >= 0 && index < currentResults.size()) {
      Account selected = currentResults[index]
      onSelect.accept(selected)
      hide()
    }
  }
}
