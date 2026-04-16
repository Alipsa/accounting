package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.TupleConstructor

import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook

import se.alipsa.accounting.domain.report.IncomeStatementRow
import se.alipsa.accounting.domain.report.IncomeStatementRowType
import se.alipsa.accounting.domain.report.ReportArchive
import se.alipsa.accounting.domain.report.ReportResult
import se.alipsa.accounting.domain.report.ReportSelection
import se.alipsa.accounting.domain.report.ReportType

import java.nio.charset.StandardCharsets

/**
 * Exports tabular reports as CSV using the same selection and row order as the UI preview.
 * The export intentionally uses ';' as separator because Swedish Excel defaults expect it.
 */
final class ReportExportService {

  private final ReportDataService reportDataService
  private final ReportArchiveService reportArchiveService
  private final ReportIntegrityService reportIntegrityService
  private final AuditLogService auditLogService
  private final DatabaseService databaseService

  ReportExportService() {
    this(
        new ReportDataService(),
        new ReportArchiveService(),
        new ReportIntegrityService(),
        new AuditLogService(),
        DatabaseService.instance
    )
  }

  ReportExportService(
      ReportDataService reportDataService,
      ReportArchiveService reportArchiveService,
      ReportIntegrityService reportIntegrityService,
      AuditLogService auditLogService,
      DatabaseService databaseService
  ) {
    this.reportDataService = reportDataService
    this.reportArchiveService = reportArchiveService
    this.reportIntegrityService = reportIntegrityService
    this.auditLogService = auditLogService
    this.databaseService = databaseService
  }

  ReportResult preview(ReportSelection selection) {
    reportDataService.generate(selection)
  }

  byte[] renderCsv(ReportResult report) {
    if (!report.reportType.csvSupported) {
      throw new IllegalArgumentException("CSV-export stöds inte för ${report.reportType.displayName}.")
    }
    StringBuilder builder = new StringBuilder('\uFEFF')
    // Swedish locale users commonly open these files in Excel, which expects semicolon-separated UTF-8 with BOM.
    builder.append(report.tableHeaders.collect { String value -> escapeCsv(value) }.join(';')).append('\n')
    report.tableRows.each { List<String> row ->
      builder.append(row.collect { String value -> escapeCsv(value) }.join(';')).append('\n')
    }
    builder.toString().getBytes(StandardCharsets.UTF_8)
  }

  ReportArchive exportCsv(ReportSelection selection) {
    reportIntegrityService.ensureReportingAllowed()
    ReportResult report = preview(selection)
    byte[] csv = renderCsv(report)
    ReportArchive archive = reportArchiveService.archiveReport(
        new ReportSelection(report.reportType, report.fiscalYearId, report.accountingPeriodId, report.startDate, report.endDate),
        'CSV',
        csv
    )
    long companyId = resolveCompanyId(report.fiscalYearId)
    auditLogService.logExport(
        "CSV-rapport exporterad: ${report.reportType.displayName}",
        "archiveId=${archive.id}\nchecksumSha256=${archive.checksumSha256}\nstoragePath=${archive.storagePath}",
        companyId
    )
    archive
  }

  byte[] renderExcel(ReportResult report) {
    XSSFWorkbook workbook = new XSSFWorkbook()
    try {
      if (report.reportType == ReportType.INCOME_STATEMENT) {
        renderIncomeStatementWorkbook(workbook, report)
      } else {
        renderGenericWorkbook(workbook, report)
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream()
      workbook.write(output)
      output.toByteArray()
    } finally {
      workbook.close()
    }
  }

  private static void renderGenericWorkbook(XSSFWorkbook workbook, ReportResult report) {
    def sheet = workbook.createSheet(sheetNameFor(report))
    def headerFont = workbook.createFont()
    headerFont.bold = true

    def headerStyle = workbook.createCellStyle()
    headerStyle.setFont(headerFont)
    headerStyle.fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
    headerStyle.fillPattern = FillPatternType.SOLID_FOREGROUND
    headerStyle.borderBottom = BorderStyle.THIN

    def textStyle = workbook.createCellStyle()

    int rowIndex = 0
    def headerRow = sheet.createRow(rowIndex++)
    report.tableHeaders.eachWithIndex { String header, int columnIndex ->
      def cell = headerRow.createCell(columnIndex)
      cell.setCellValue(header ?: '')
      cell.cellStyle = headerStyle
    }

    report.tableRows.each { List<String> row ->
      def sheetRow = sheet.createRow(rowIndex++)
      row.eachWithIndex { String value, int columnIndex ->
        def cell = sheetRow.createCell(columnIndex)
        cell.setCellValue(value ?: '')
        cell.cellStyle = textStyle
      }
    }

    for (int i = 0; i < report.tableHeaders.size(); i++) {
      sheet.autoSizeColumn(i)
    }
  }

  private void renderIncomeStatementWorkbook(XSSFWorkbook workbook, ReportResult report) {
    def sheet = workbook.createSheet(sheetNameFor(report))
    List<IncomeStatementRow> typedRows = report.templateModel.get('typedRows') as List<IncomeStatementRow>
    String companyName = resolveCompanyName(report.fiscalYearId)
    IncomeStatementStyles styles = createIncomeStatementStyles(workbook)

    int rowIndex = 0
    rowIndex = writeSingleCellRow(sheet, rowIndex, 'Resultatrapport', styles.titleStyle)
    rowIndex = writeSingleCellRow(sheet, rowIndex, companyName, styles.metaStyle)
    rowIndex = writeSingleCellRow(sheet, rowIndex, report.selectionLabel ?: '', styles.metaStyle)
    rowIndex++

    rowIndex = writeIncomeStatementHeaderRow(sheet, rowIndex, report.tableHeaders, styles)
    writeIncomeStatementRows(sheet, rowIndex, report.tableRows, typedRows, styles)

    applyIncomeStatementColumnWidths(sheet)
  }

  private String resolveCompanyName(long fiscalYearId) {
    databaseService.withSql { Sql sql ->
      GroovyRowResult row = sql.firstRow('''
          select c.company_name as companyName
            from fiscal_year fy
            join company c on c.id = fy.company_id
           where fy.id = ?
      ''', [fiscalYearId]) as GroovyRowResult
      row == null ? '' : (row.get('companyName') as String)
    }
  }

  @SuppressWarnings('AbcMetric')
  private static IncomeStatementStyles createIncomeStatementStyles(XSSFWorkbook workbook) {
    def titleFont = workbook.createFont()
    titleFont.fontName = 'Arial'
    titleFont.fontHeightInPoints = 20
    titleFont.bold = true

    def metaFont = workbook.createFont()
    metaFont.fontName = 'Arial'
    metaFont.fontHeightInPoints = 10

    def sectionFont = workbook.createFont()
    sectionFont.fontName = 'Arial'
    sectionFont.fontHeightInPoints = 12
    sectionFont.bold = true

    def boldFont = workbook.createFont()
    boldFont.fontName = 'Arial'
    boldFont.fontHeightInPoints = 10
    boldFont.bold = true

    def textFont = workbook.createFont()
    textFont.fontName = 'Arial'
    textFont.fontHeightInPoints = 10

    short numberFormat = workbook.createDataFormat().getFormat(excelNumberFormat())

    CellStyle titleStyle = workbook.createCellStyle()
    titleStyle.setFont(titleFont)

    CellStyle metaStyle = workbook.createCellStyle()
    metaStyle.setFont(metaFont)

    CellStyle headerStyle = workbook.createCellStyle()
    headerStyle.setFont(boldFont)
    headerStyle.fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
    headerStyle.fillPattern = FillPatternType.SOLID_FOREGROUND
    headerStyle.borderBottom = BorderStyle.THIN

    CellStyle headerNumberStyle = workbook.createCellStyle()
    headerNumberStyle.cloneStyleFrom(headerStyle)
    headerNumberStyle.alignment = HorizontalAlignment.RIGHT

    CellStyle detailTextStyle = workbook.createCellStyle()
    detailTextStyle.setFont(textFont)

    CellStyle detailNumberStyle = workbook.createCellStyle()
    detailNumberStyle.cloneStyleFrom(detailTextStyle)
    detailNumberStyle.alignment = HorizontalAlignment.RIGHT
    detailNumberStyle.dataFormat = numberFormat

    CellStyle boldTextStyle = workbook.createCellStyle()
    boldTextStyle.setFont(boldFont)

    CellStyle boldNumberStyle = workbook.createCellStyle()
    boldNumberStyle.cloneStyleFrom(boldTextStyle)
    boldNumberStyle.alignment = HorizontalAlignment.RIGHT
    boldNumberStyle.dataFormat = numberFormat

    CellStyle sectionTextStyle = workbook.createCellStyle()
    sectionTextStyle.setFont(sectionFont)

    CellStyle sectionNumberStyle = workbook.createCellStyle()
    sectionNumberStyle.cloneStyleFrom(sectionTextStyle)
    sectionNumberStyle.alignment = HorizontalAlignment.RIGHT
    sectionNumberStyle.dataFormat = numberFormat

    new IncomeStatementStyles(
        titleStyle,
        metaStyle,
        headerStyle,
        headerNumberStyle,
        detailTextStyle,
        detailNumberStyle,
        boldTextStyle,
        boldNumberStyle,
        sectionTextStyle,
        sectionNumberStyle
    )
  }

  private static String excelNumberFormat() {
    // Excel interprets ',' and '.' in format codes as locale-specific grouping and decimal
    // separators, so a Swedish regional setting renders "#,##0.00" as "1 000,00".
    '#,##0.00;-#,##0.00'
  }

  private static int writeSingleCellRow(Sheet sheet, int rowIndex, String value, CellStyle style) {
    Row row = sheet.createRow(rowIndex)
    Cell cell = row.createCell(0)
    cell.setCellValue(value ?: '')
    cell.cellStyle = style
    rowIndex + 1
  }

  private static int writeIncomeStatementHeaderRow(Sheet sheet, int rowIndex, List<String> headers, IncomeStatementStyles styles) {
    Row headerRow = sheet.createRow(rowIndex)
    headers.eachWithIndex { String header, int columnIndex ->
      Cell cell = headerRow.createCell(columnIndex)
      cell.setCellValue(header ?: '')
      cell.cellStyle = columnIndex == 1 ? styles.headerNumberStyle : styles.headerStyle
    }
    rowIndex + 1
  }

  private static void writeIncomeStatementRows(
      Sheet sheet,
      int rowIndex,
      List<List<String>> tableRows,
      List<IncomeStatementRow> typedRows,
      IncomeStatementStyles styles
  ) {
    typedRows.eachWithIndex { IncomeStatementRow typedRow, int index ->
      List<String> rowValues = tableRows[index]
      Row row = sheet.createRow(rowIndex + index)
      row.heightInPoints = heightForIncomeRow(typedRow.rowType)

      Cell labelCell = row.createCell(0)
      labelCell.setCellValue(rowValues[0] ?: '')
      labelCell.cellStyle = textStyleForIncomeRow(typedRow.rowType, styles)

      Cell amountCell = row.createCell(1)
      if (typedRow.amount == null) {
        amountCell.setCellValue(rowValues.size() > 1 ? (rowValues[1] ?: '') : '')
      } else {
        amountCell.setCellValue(typedRow.amount.doubleValue())
      }
      amountCell.cellStyle = numberStyleForIncomeRow(typedRow.rowType, styles)
    }
  }

  private static void applyIncomeStatementColumnWidths(Sheet sheet) {
    sheet.setColumnWidth(0, 38 * 256)
    sheet.setColumnWidth(1, 15 * 256)
  }

  private static short heightForIncomeRow(IncomeStatementRowType rowType) {
    switch (rowType) {
      case IncomeStatementRowType.SECTION_HEADER:
        return 24
      case IncomeStatementRowType.GROUP_HEADER:
        return 17
      case IncomeStatementRowType.SECTION_TOTAL:
      case IncomeStatementRowType.RESULT_LINE:
        return 21
      case IncomeStatementRowType.GRAND_TOTAL:
        return 24
      default:
        return 15
    }
  }

  private static CellStyle textStyleForIncomeRow(IncomeStatementRowType rowType, IncomeStatementStyles styles) {
    switch (rowType) {
      case IncomeStatementRowType.SECTION_HEADER:
      case IncomeStatementRowType.GRAND_TOTAL:
        return styles.sectionTextStyle
      case IncomeStatementRowType.GROUP_HEADER:
      case IncomeStatementRowType.SUBTOTAL:
      case IncomeStatementRowType.SECTION_TOTAL:
      case IncomeStatementRowType.RESULT_LINE:
        return styles.boldTextStyle
      default:
        return styles.detailTextStyle
    }
  }

  private static CellStyle numberStyleForIncomeRow(IncomeStatementRowType rowType, IncomeStatementStyles styles) {
    switch (rowType) {
      case IncomeStatementRowType.SECTION_HEADER:
      case IncomeStatementRowType.GRAND_TOTAL:
        return styles.sectionNumberStyle
      case IncomeStatementRowType.GROUP_HEADER:
      case IncomeStatementRowType.SUBTOTAL:
      case IncomeStatementRowType.SECTION_TOTAL:
      case IncomeStatementRowType.RESULT_LINE:
        return styles.boldNumberStyle
      default:
        return styles.detailNumberStyle
    }
  }

  ReportArchive exportExcel(ReportSelection selection) {
    reportIntegrityService.ensureReportingAllowed()
    ReportResult report = preview(selection)
    byte[] xlsx = renderExcel(report)
    ReportArchive archive = reportArchiveService.archiveReport(
        new ReportSelection(report.reportType, report.fiscalYearId, report.accountingPeriodId, report.startDate, report.endDate),
        'XLSX',
        xlsx
    )
    long companyId = resolveCompanyId(report.fiscalYearId)
    auditLogService.logExport(
        "Excel-rapport exporterad: ${report.reportType.displayName}",
        "archiveId=${archive.id}\nchecksumSha256=${archive.checksumSha256}\nstoragePath=${archive.storagePath}",
        companyId
    )
    archive
  }

  private long resolveCompanyId(long fiscalYearId) {
    databaseService.withSql { Sql sql ->
      CompanyService.resolveFromFiscalYear(sql, fiscalYearId)
    }
  }

  private static String escapeCsv(String value) {
    String safeValue = value ?: ''
    if (startsFormula(safeValue)) {
      safeValue = "'${safeValue}"
    }
    if (!safeValue.contains(';') && !safeValue.contains('"') && !safeValue.contains('\n')) {
      return safeValue
    }
    "\"${safeValue.replace('"', '""')}\""
  }

  private static boolean startsFormula(String value) {
    if (!value) {
      return false
    }
    String first = value.substring(0, 1)
    if (first in ['=', '+', '@']) {
      return true
    }
    first == '-' && !(value ==~ /-\d+(\.\d+)?/)
  }

  private static String sheetNameFor(ReportResult report) {
    String safeName = (report.title ?: report.reportType.displayName ?: 'Report')
        .replaceAll(/[\\\/?*\[\]:]/, ' ')
        .trim()
    String normalized = safeName ?: 'Report'
    normalized.length() > 31 ? normalized.substring(0, 31) : normalized
  }

  @TupleConstructor
  private static final class IncomeStatementStyles {

    final CellStyle titleStyle
    final CellStyle metaStyle
    final CellStyle headerStyle
    final CellStyle headerNumberStyle
    final CellStyle detailTextStyle
    final CellStyle detailNumberStyle
    final CellStyle boldTextStyle
    final CellStyle boldNumberStyle
    final CellStyle sectionTextStyle
    final CellStyle sectionNumberStyle
  }
}
