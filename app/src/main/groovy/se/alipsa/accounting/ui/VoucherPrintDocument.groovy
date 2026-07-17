package se.alipsa.accounting.ui

import groovy.transform.PackageScope

import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine
import se.alipsa.accounting.support.AmountFormatter
import se.alipsa.accounting.support.I18n

/**
 * Builds the printable HTML representation of a voucher.
 */
class VoucherPrintDocument {

  @PackageScope
  static String buildHtml(Voucher voucher, Locale locale) {
    Map<String, String> values = printableVoucherValues(voucher, locale)
    Map<String, String> labels = printableVoucherLabels()
    String rows = buildRows(voucher, locale)
    buildDocument(values, labels, rows)
  }

  private static Map<String, String> printableVoucherValues(Voucher voucher, Locale locale) {
    [
        voucherNumber: escapeHtml(voucher.voucherNumber ?: String.valueOf(voucher.id)),
        accountingDate: escapeHtml(voucher.accountingDate?.toString() ?: ''),
        description   : escapeHtml(voucher.description ?: ''),
        series        : escapeHtml(voucher.seriesCode ?: ''),
        totalDebit    : escapeHtml(AmountFormatter.format(voucher.debitTotal(), locale)),
        totalCredit   : escapeHtml(AmountFormatter.format(voucher.creditTotal(), locale)),
    ]
  }

  private static Map<String, String> printableVoucherLabels() {
    [
        title             : escapeHtml(I18n.instance.getString('voucherPanel.print.title')),
        date              : escapeHtml(I18n.instance.getString('voucherPanel.label.date')),
        series            : escapeHtml(I18n.instance.getString('voucherPanel.label.series')),
        description       : escapeHtml(I18n.instance.getString('voucherPanel.label.description')),
        account           : escapeHtml(I18n.instance.getString('voucherPanel.table.account')),
        accountDescription: escapeHtml(I18n.instance.getString('voucherPanel.table.accountDescription')),
        text              : escapeHtml(I18n.instance.getString('voucherPanel.table.text')),
        debit             : escapeHtml(I18n.instance.getString('voucherPanel.table.debit')),
        credit            : escapeHtml(I18n.instance.getString('voucherPanel.table.credit')),
        total             : escapeHtml(I18n.instance.getString('voucherPanel.print.total')),
    ]
  }

  private static String buildRows(Voucher voucher, Locale locale) {
    StringBuilder rows = new StringBuilder()
    voucher.lines.each { VoucherLine line ->
      rows.append('<tr>')
      rows.append("<td>${escapeHtml(line.accountNumber ?: '')}</td>")
      rows.append("<td>${escapeHtml(line.accountName ?: '')}</td>")
      rows.append("<td>${escapeHtml(line.description ?: '')}</td>")
      rows.append("<td class=\"amount\">${escapeHtml(AmountFormatter.formatOrEmpty(line.debitAmount, locale))}</td>")
      rows.append("<td class=\"amount\">${escapeHtml(AmountFormatter.formatOrEmpty(line.creditAmount, locale))}</td>")
      rows.append('</tr>')
    }
    rows.toString()
  }

  private static String buildDocument(Map<String, String> values, Map<String, String> labels, String rows) {
    """
        <html>
        <head>
          <style>
            body { font-family: sans-serif; font-size: 10pt; color: #111; }
            h1 { font-size: 18pt; margin: 0 0 12pt 0; }
            .meta { margin-bottom: 14pt; }
            .meta div { margin: 2pt 0; }
            table { border-collapse: collapse; width: 100%; }
            th, td { border-bottom: 1px solid #ddd; padding: 4pt 5pt; text-align: left; vertical-align: top; }
            th { background: #f2f2f2; font-weight: bold; }
            .amount { text-align: right; white-space: nowrap; }
            tfoot td { border-top: 2px solid #333; border-bottom: 0; font-weight: bold; }
          </style>
        </head>
        <body>
          <h1>${labels['title']} ${values['voucherNumber']}</h1>
          <div class="meta">
            <div><strong>${labels['date']}:</strong> ${values['accountingDate']}</div>
            <div><strong>${labels['series']}:</strong> ${values['series']}</div>
            <div><strong>${labels['description']}:</strong> ${values['description']}</div>
          </div>
          <table>
            <thead>
              <tr>
                <th>${labels['account']}</th>
                <th>${labels['accountDescription']}</th>
                <th>${labels['text']}</th>
                <th class="amount">${labels['debit']}</th>
                <th class="amount">${labels['credit']}</th>
              </tr>
            </thead>
            <tbody>
              ${rows}
            </tbody>
            <tfoot>
              <tr>
                <td colspan="3">${labels['total']}</td>
                <td class="amount">${values['totalDebit']}</td>
                <td class="amount">${values['totalCredit']}</td>
              </tr>
            </tfoot>
          </table>
        </body>
        </html>
    """.stripIndent().trim()
  }

  private static String escapeHtml(String text) {
    if (text == null) {
      return ''
    }
    text
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
  }
}
