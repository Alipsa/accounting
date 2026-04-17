package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.Account
import se.alipsa.accounting.service.AccountService
import se.alipsa.accounting.support.I18n

import java.awt.BorderLayout
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.function.Consumer

import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.JWindow
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Non-focusable popup list for searching accounts by name or number.
 * Uses a JWindow instead of JPopupMenu to avoid stealing focus from the editor.
 */
final class AccountLookupPopup {

  private static final int DEBOUNCE_MILLIS = 200
  private static final int MAX_VISIBLE_ROWS = 10

  private final AccountService accountService
  private final long companyId
  private final Consumer<Account> onSelect
  private final DefaultListModel<String> listModel = new DefaultListModel<>()
  private final JList<String> resultList = new JList<>(listModel)
  private final Timer debounceTimer
  private List<Account> currentResults = []
  private JTextField activeEditor
  private JWindow popupWindow

  AccountLookupPopup(AccountService accountService, long companyId, Consumer<Account> onSelect) {
    this.accountService = accountService
    this.companyId = companyId
    this.onSelect = onSelect
    resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION
    resultList.focusable = false
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
    debounceTimer = new Timer(DEBOUNCE_MILLIS, { search() })
    debounceTimer.repeats = false
  }

  void attachToEditor(JTextField editor) {
    if (editor == activeEditor) {
      return
    }
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
        if (!isShowing()) {
          return
        }
        if (event.keyCode == KeyEvent.VK_DOWN) {
          int next = resultList.selectedIndex + 1
          if (next < listModel.size()) {
            resultList.selectedIndex = next
          } else if (listModel.size() > 0) {
            resultList.selectedIndex = 0
          }
          event.consume()
        } else if (event.keyCode == KeyEvent.VK_UP) {
          int prev = resultList.selectedIndex - 1
          if (prev >= 0) {
            resultList.selectedIndex = prev
          } else if (listModel.size() > 0) {
            resultList.selectedIndex = listModel.size() - 1
          }
          event.consume()
        } else if (event.keyCode == KeyEvent.VK_ESCAPE) {
          hide()
        } else if (event.keyCode == KeyEvent.VK_ENTER && resultList.selectedIndex >= 0) {
          selectCurrent()
          event.consume()
        }
      }
    })
  }

  void hide() {
    if (popupWindow != null) {
      popupWindow.visible = false
    }
    debounceTimer.stop()
  }

  private boolean isShowing() {
    popupWindow != null && popupWindow.visible
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
    if (query.matches('[0-9]{4,}')) {
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
      resultList.clearSelection()
    }
    resultList.visibleRowCount = Math.min(listModel.size(), MAX_VISIBLE_ROWS)
    showPopup()
  }

  private void showPopup() {
    if (activeEditor == null || !activeEditor.showing) {
      return
    }
    if (popupWindow == null) {
      popupWindow = new JWindow(SwingUtilities.getWindowAncestor(activeEditor))
      popupWindow.focusableWindowState = false
      JScrollPane scrollPane = new JScrollPane(resultList)
      scrollPane.border = BorderFactory.createLineBorder(resultList.foreground)
      popupWindow.contentPane.layout = new BorderLayout()
      popupWindow.contentPane.add(scrollPane, BorderLayout.CENTER)
    }
    Point location = activeEditor.locationOnScreen
    popupWindow.pack()
    int width = Math.max(popupWindow.width, activeEditor.width)
    popupWindow.setSize(width, popupWindow.height)
    popupWindow.setLocation(location.x as int, (location.y + activeEditor.height) as int)
    popupWindow.visible = true
  }

  private void selectCurrent() {
    int index = resultList.selectedIndex
    if (index >= 0 && index < currentResults.size()) {
      Account selected = currentResults[index]
      hide()
      onSelect.accept(selected)
    }
  }

}
