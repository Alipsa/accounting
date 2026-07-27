package se.alipsa.accounting.support.sru

import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory

import java.nio.file.Files
import java.nio.file.Path

/**
 * One-time conversion of a BAS SRU kopplingstabell spreadsheet (bas.se/kontoplaner/sru/, see
 * docs/SRU/SOURCES.md) into the bundled CSV lookup table SruSuggestionService reads at runtime.
 * Not a build step - rerun manually via #main when BAS publishes an updated table.
 */
final class BasSruTableConverter {

  static final class ConversionResult {

    final List<List<String>> rows
    final List<UnparsedRow> unparsed

    ConversionResult(List<List<String>> rows, List<UnparsedRow> unparsed) {
      this.rows = rows
      this.unparsed = unparsed
    }
  }

  static final class UnparsedRow {

    final String sourceFile
    final String fieldCode
    final String rawValue

    UnparsedRow(String sourceFile, String fieldCode, String rawValue) {
      this.sourceFile = sourceFile
      this.fieldCode = fieldCode
      this.rawValue = rawValue
    }
  }

  private BasSruTableConverter() {
  }

  static ConversionResult convert(Path xlsxPath) {
    DataFormatter formatter = new DataFormatter(Locale.ROOT)
    List<List<String>> rows = []
    List<UnparsedRow> unparsed = []
    String lastFieldCode = null
    String sourceFile = xlsxPath.fileName as String

    Files.newInputStream(xlsxPath).withCloseable { InputStream input ->
      Workbook workbook = WorkbookFactory.create(input)
      try {
        Sheet sheet = workbook.getSheetAt(0)
        for (Row row : sheet) {
          String fieldCodeCell = formatter.formatCellValue(row.getCell(0)).trim()
          // Column-2 Benämning is blank on NE_K1's continuation rows (see lastFieldCode below);
          // that's fine, description is informational only, never used for suggestion matching.
          String description = formatter.formatCellValue(row.getCell(2)).trim().replaceAll(/[\r\n]+/, ' ')
          String accountsCell = formatter.formatCellValue(row.getCell(3)).trim()

          String fieldCode
          if (fieldCodeCell ==~ /\d+/) {
            fieldCode = fieldCodeCell
            lastFieldCode = fieldCode
          } else if (accountsCell && lastFieldCode != null) {
            // NE_K1 lists several accounts per field code across consecutive rows without
            // repeating the field code - only its layout does this (verified: 0 such rows in
            // INK2/INK4/NE_EJ_K1, 60 in NE_K1).
            fieldCode = lastFieldCode
          } else {
            continue
          }

          List<BasAccountRangeParser.Segment> segments = BasAccountRangeParser.parseCell(accountsCell)
          if (segments == null) {
            unparsed << new UnparsedRow(sourceFile, fieldCode, accountsCell)
            continue
          }
          segments.each { BasAccountRangeParser.Segment segment ->
            segment.accounts.each { Integer account ->
              rows << [fieldCode, account.toString(), segment.signCondition, description]
            }
          }
        }
      } finally {
        workbook.close()
      }
    }
    rows.sort { List<String> r -> [r[1] as Integer, r[0] as Integer] }
    new ConversionResult(rows, unparsed)
  }

  static void writeCsv(ConversionResult result, Path csvPath) {
    List<String> lines = ['field_code,account_number,sign_condition,description']
    result.rows.each { List<String> row -> lines << row.join(',') }
    Files.createDirectories(csvPath.parent)
    Files.write(csvPath, lines.join('\n').getBytes('UTF-8'))
  }

  static void main(String[] args) {
    if (args.length != 2) {
      System.err.println('Usage: BasSruTableConverter <input.xlsx> <output.csv>')
      System.exit(1)
      return
    }
    ConversionResult result = convert(Path.of(args[0]))
    writeCsv(result, Path.of(args[1]))
    println "${result.rows.size()} rows written to ${args[1]}"
    result.unparsed.each { UnparsedRow row ->
      println "UNPARSED [${row.sourceFile}] field ${row.fieldCode}: ${row.rawValue}"
    }
  }
}
