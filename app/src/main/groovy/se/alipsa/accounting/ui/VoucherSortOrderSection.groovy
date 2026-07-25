package se.alipsa.accounting.ui

import se.alipsa.accounting.domain.VoucherSortOrder
import se.alipsa.accounting.service.UserPreferencesService
import se.alipsa.accounting.support.I18n

import java.awt.FlowLayout

import javax.swing.ButtonGroup
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton

/** Settings row letting the user choose how vouchers are sorted in the voucher editor. */
final class VoucherSortOrderSection {

  private final UserPreferencesService userPreferencesService
  private final Runnable onChange
  private final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
  private final JLabel label = new JLabel()
  private final JRadioButton byNumberButton = new JRadioButton()
  private final JRadioButton byDateButton = new JRadioButton()

  VoucherSortOrderSection(UserPreferencesService userPreferencesService, Runnable onChange) {
    this.userPreferencesService = userPreferencesService
    this.onChange = onChange
    ButtonGroup group = new ButtonGroup()
    group.add(byNumberButton)
    group.add(byDateButton)
    selectButtonFor(userPreferencesService.getVoucherSortOrder())
    byNumberButton.addActionListener { switchSortOrder(VoucherSortOrder.BY_VOUCHER_NUMBER) }
    byDateButton.addActionListener { switchSortOrder(VoucherSortOrder.BY_ACCOUNTING_DATE) }
    panel.add(label)
    panel.add(byNumberButton)
    panel.add(byDateButton)
    applyLocale()
  }

  JPanel getPanel() {
    panel
  }

  void applyLocale() {
    label.text = I18n.instance.getString('settings.label.voucherSortOrder')
    byNumberButton.text = I18n.instance.getString('settings.voucherSortOrder.byNumber')
    byDateButton.text = I18n.instance.getString('settings.voucherSortOrder.byDate')
  }

  private void switchSortOrder(VoucherSortOrder sortOrder) {
    userPreferencesService.setVoucherSortOrder(sortOrder)
    onChange?.run()
  }

  private void selectButtonFor(VoucherSortOrder sortOrder) {
    if (sortOrder == VoucherSortOrder.BY_ACCOUNTING_DATE) {
      byDateButton.selected = true
    } else {
      byNumberButton.selected = true
    }
  }
}
