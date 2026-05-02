package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.PackageScope

import se.alipsa.accounting.domain.AccountingPeriod
import se.alipsa.accounting.domain.AttachmentMetadata
import se.alipsa.accounting.domain.AuditLogEntry
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VatPeriod
import se.alipsa.accounting.domain.Voucher
import se.alipsa.accounting.domain.VoucherLine

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Persists immutable audit entries for business-critical operations.
 */
final class AuditLogService {

  static final String CREATE_VOUCHER = 'CREATE_VOUCHER'
  static final String UPDATE_VOUCHER = 'UPDATE_VOUCHER'
  static final String CANCEL_VOUCHER = 'CANCEL_VOUCHER'
  static final String CORRECTION_VOUCHER = 'CORRECTION_VOUCHER'
  static final String ATTACHMENT_ADDED = 'ATTACHMENT_ADDED'
  static final String ATTACHMENT_RECOVERED = 'ATTACHMENT_RECOVERED'
  static final String LOCK_PERIOD = 'LOCK_PERIOD'
  static final String CLOSE_FISCAL_YEAR = 'CLOSE_FISCAL_YEAR'
  static final String REOPEN_FISCAL_YEAR = 'REOPEN_FISCAL_YEAR'
  static final String VAT_PERIOD_REPORTED = 'VAT_PERIOD_REPORTED'
  static final String VAT_PERIOD_LOCKED = 'VAT_PERIOD_LOCKED'
  static final String IMPORT = 'IMPORT'
  static final String EXPORT = 'EXPORT'
  static final String BACKUP = 'BACKUP'
  static final String RESTORE = 'RESTORE'
  static final String DELETE_FISCAL_YEAR = 'DELETE_FISCAL_YEAR'
  static final String ARCHIVE_COMPANY = 'ARCHIVE_COMPANY'
  static final String UNARCHIVE_COMPANY = 'UNARCHIVE_COMPANY'

  private static final String DEFAULT_ACTOR = 'desktop-app'
  private static final DateTimeFormatter HASH_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS")

  private final DatabaseService databaseService

  AuditLogService() {
    this(DatabaseService.instance)
  }

  AuditLogService(DatabaseService databaseService) {
    this.databaseService = databaseService
  }

  List<AuditLogEntry> listEntriesForVoucher(long voucherId) {
    databaseService.withSql { Sql sql ->
      sql.rows('''
          select id,
                 event_type as eventType,
                 voucher_id as voucherId,
                 attachment_id as attachmentId,
                 fiscal_year_id as fiscalYearId,
                 accounting_period_id as accountingPeriodId,
                 vat_period_id as vatPeriodId,
                 actor,
                 summary,
                 details,
                 previous_hash as previousHash,
                 entry_hash as entryHash,
                 created_at as createdAt
            from audit_log
           where voucher_id = ?
             and archived = false
           order by created_at desc, id desc
      ''', [voucherId]).collect { GroovyRowResult row ->
        mapEntry(row)
      }
    }
  }

  List<AuditLogEntry> listEntries(long companyId, int limit = 200) {
    CompanyService.requireValidCompanyId(companyId)
    int safeLimit = Math.max(1, limit)
    databaseService.withSql { Sql sql ->
      sql.rows('''
          select id,
                 event_type as eventType,
                 voucher_id as voucherId,
                 attachment_id as attachmentId,
                 fiscal_year_id as fiscalYearId,
                 accounting_period_id as accountingPeriodId,
                 vat_period_id as vatPeriodId,
                 actor,
                 summary,
                 details,
                 previous_hash as previousHash,
                 entry_hash as entryHash,
                 created_at as createdAt
            from audit_log
           where company_id = ?
             and archived = false
           order by created_at desc, id desc
           limit ?
      ''', [companyId, safeLimit]).collect { GroovyRowResult row ->
        mapEntry(row)
      }
    }
  }

  List<AuditLogEntry> listAllEntries(int limit = 200) {
    int safeLimit = Math.max(1, limit)
    databaseService.withSql { Sql sql ->
      sql.rows('''
          select id,
                 event_type as eventType,
                 voucher_id as voucherId,
                 attachment_id as attachmentId,
                 fiscal_year_id as fiscalYearId,
                 accounting_period_id as accountingPeriodId,
                 vat_period_id as vatPeriodId,
                 actor,
                 summary,
                 details,
                 previous_hash as previousHash,
                 entry_hash as entryHash,
                 created_at as createdAt
            from audit_log
           where archived = false
           order by created_at desc, id desc
           limit ?
      ''', [safeLimit]).collect { GroovyRowResult row ->
        mapEntry(row)
      }
    }
  }

  List<String> validateIntegrity() {
    databaseService.withSql { Sql sql ->
      List<String> problems = []
      sql.rows('select distinct company_id as companyId from audit_log').each { GroovyRowResult companyRow ->
        long cid = ((Number) companyRow.get('companyId')).longValue()
        problems.addAll(validateIntegrityForCompany(sql, cid))
      }
      problems
    }
  }

  List<String> validateIntegrity(long companyId) {
    CompanyService.requireValidCompanyId(companyId)
    databaseService.withSql { Sql sql ->
      validateIntegrityForCompany(sql, companyId)
    }
  }

  private static List<String> validateIntegrityForCompany(Sql sql, long companyId) {
    List<String> problems = []
    String expectedPreviousHash = null
    String actualLastHash = null
    sql.rows('''
        select id,
               event_type as eventType,
               voucher_id as voucherId,
               attachment_id as attachmentId,
               fiscal_year_id as fiscalYearId,
               accounting_period_id as accountingPeriodId,
               vat_period_id as vatPeriodId,
               actor,
               summary,
               details,
               previous_hash as previousHash,
               entry_hash as entryHash,
               created_at as createdAt
          from audit_log
         where company_id = ?
         order by id
    ''', [companyId]).each { GroovyRowResult row ->
      AuditLogEntry entry = mapEntry(row)
      if (entry.previousHash != expectedPreviousHash) {
        problems << ("Auditlogg ${entry.id} har fel föregående hash." as String)
      }
      String calculated = calculateHash(new AuditEntrySeed(
          eventType: entry.eventType,
          voucherId: entry.voucherId,
          attachmentId: entry.attachmentId,
          fiscalYearId: entry.fiscalYearId,
          accountingPeriodId: entry.accountingPeriodId,
          vatPeriodId: entry.vatPeriodId,
          actor: entry.actor,
          summary: entry.summary,
          details: entry.details,
          previousHash: entry.previousHash,
          createdAt: entry.createdAt
      ))
      if (entry.entryHash != calculated) {
        problems << ("Auditlogg ${entry.id} har ogiltig hash." as String)
      }
      expectedPreviousHash = entry.entryHash
      actualLastHash = entry.entryHash
    }
    AuditChainHead chainHead = loadChainHead(sql, companyId)
    if (chainHead == null) {
      problems << 'Auditloggkedjans huvud saknas.'
    } else if (chainHead.lastEntryHash != actualLastHash) {
      problems << 'Auditloggkedjans huvud pekar inte på sista auditraden.'
    }
    problems
  }

  AuditLogEntry logImport(String summary, String details = null, long companyId = CompanyService.LEGACY_COMPANY_ID) {
    recordStandaloneEvent(IMPORT, summary, details, companyId)
  }

  AuditLogEntry logExport(String summary, String details = null, long companyId = CompanyService.LEGACY_COMPANY_ID) {
    recordStandaloneEvent(EXPORT, summary, details, companyId)
  }

  AuditLogEntry logBackup(String summary, String details = null, long companyId = CompanyService.LEGACY_COMPANY_ID) {
    recordStandaloneEvent(BACKUP, summary, details, companyId)
  }

  AuditLogEntry logRestore(String summary, String details = null, long companyId = CompanyService.LEGACY_COMPANY_ID) {
    recordStandaloneEvent(RESTORE, summary, details, companyId)
  }

  @PackageScope
  AuditLogEntry recordVoucherCreated(Sql sql, Voucher voucher) {
    recordEvent(sql, CREATE_VOUCHER, new AuditReferences(voucherId: voucher.id, fiscalYearId: voucher.fiscalYearId),
        "Verifikation skapad: ${voucher.description}", formatDetails([
            voucherId      : voucher.id,
            fiscalYearId   : voucher.fiscalYearId,
            accountingDate : voucher.accountingDate,
            description    : voucher.description,
            status         : voucher.status?.name(),
            lines          : formatLines(voucher.lines)
        ]))
  }

  @PackageScope
  AuditLogEntry recordVoucherUpdated(Sql sql, Voucher voucher) {
    recordEvent(sql, UPDATE_VOUCHER, new AuditReferences(voucherId: voucher.id, fiscalYearId: voucher.fiscalYearId),
        "Verifikation ändrad: ${voucher.voucherNumber ?: voucher.id}", formatDetails([
            voucherId      : voucher.id,
            voucherNumber  : voucher.voucherNumber,
            fiscalYearId   : voucher.fiscalYearId,
            accountingDate : voucher.accountingDate,
            description    : voucher.description,
            status         : voucher.status?.name(),
            lines          : formatLines(voucher.lines)
        ]))
  }

  @PackageScope
  AuditLogEntry recordVoucherCancelled(Sql sql, Voucher voucher) {
    recordEvent(sql, CANCEL_VOUCHER, new AuditReferences(voucherId: voucher.id, fiscalYearId: voucher.fiscalYearId),
        "Verifikation makulerad: ${voucher.id}", formatDetails([
            voucherId      : voucher.id,
            accountingDate : voucher.accountingDate,
            description    : voucher.description,
            status         : voucher.status?.name(),
            lines          : formatLines(voucher.lines)
        ]))
  }

  @PackageScope
  AuditLogEntry recordCorrectionVoucher(Sql sql, Voucher voucher) {
    recordEvent(sql, CORRECTION_VOUCHER, new AuditReferences(voucherId: voucher.id, fiscalYearId: voucher.fiscalYearId),
        "Korrigeringsverifikation skapad: ${voucher.voucherNumber ?: voucher.id}", formatDetails([
            voucherId         : voucher.id,
            voucherNumber     : voucher.voucherNumber,
            originalVoucherId : voucher.originalVoucherId,
            accountingDate    : voucher.accountingDate,
            status            : voucher.status?.name(),
            lines             : formatLines(voucher.lines)
        ]))
  }

  @PackageScope
  AuditLogEntry recordAttachmentAdded(Sql sql, AttachmentMetadata attachment) {
    recordEvent(sql, ATTACHMENT_ADDED, new AuditReferences(voucherId: attachment.voucherId, attachmentId: attachment.id),
        "Bilaga registrerad: ${attachment.originalFileName}", formatDetails([
            attachmentId   : attachment.id,
            voucherId      : attachment.voucherId,
            contentType    : attachment.contentType,
            checksumSha256 : attachment.checksumSha256,
            fileSize       : attachment.fileSize,
            storagePath    : attachment.storagePath
        ]))
  }

  @PackageScope
  AuditLogEntry recordAttachmentRecovered(Sql sql, AttachmentMetadata attachment) {
    recordEvent(sql, ATTACHMENT_RECOVERED, new AuditReferences(voucherId: attachment.voucherId, attachmentId: attachment.id),
        "Bilaga återställd: ${attachment.originalFileName}", formatDetails([
            attachmentId   : attachment.id,
            voucherId      : attachment.voucherId,
            contentType    : attachment.contentType,
            checksumSha256 : attachment.checksumSha256,
            fileSize       : attachment.fileSize,
            storagePath    : attachment.storagePath
        ]))
  }

  @PackageScope
  AuditLogEntry recordPeriodLocked(Sql sql, AccountingPeriod period) {
    recordEvent(sql, LOCK_PERIOD, new AuditReferences(fiscalYearId: period.fiscalYearId, accountingPeriodId: period.id),
        "Period låst: ${period.periodName}", formatDetails([
            accountingPeriodId : period.id,
            fiscalYearId       : period.fiscalYearId,
            periodName         : period.periodName,
            lockReason         : period.lockReason,
            lockedAt           : period.lockedAt
        ]))
  }

  @PackageScope
  AuditLogEntry recordFiscalYearClosed(Sql sql, FiscalYear fiscalYear) {
    recordEvent(sql, CLOSE_FISCAL_YEAR, new AuditReferences(fiscalYearId: fiscalYear.id),
        "Räkenskapsår stängt: ${fiscalYear.name}", formatDetails([
            fiscalYearId : fiscalYear.id,
            name         : fiscalYear.name,
            startDate    : fiscalYear.startDate,
            endDate      : fiscalYear.endDate,
            closedAt     : fiscalYear.closedAt
        ]))
  }

  @PackageScope
  AuditLogEntry recordFiscalYearReopened(Sql sql, FiscalYear fiscalYear) {
    recordEvent(sql, REOPEN_FISCAL_YEAR, new AuditReferences(fiscalYearId: fiscalYear.id),
        "Räkenskapsår upplåst: ${fiscalYear.name}", formatDetails([
            fiscalYearId : fiscalYear.id,
            name         : fiscalYear.name,
            startDate    : fiscalYear.startDate,
            endDate      : fiscalYear.endDate,
            closed       : fiscalYear.closed
        ]))
  }

  @PackageScope
  AuditLogEntry recordVatPeriodReported(Sql sql, VatPeriod vatPeriod, String reportHash) {
    recordEvent(sql, VAT_PERIOD_REPORTED, new AuditReferences(fiscalYearId: vatPeriod.fiscalYearId, vatPeriodId: vatPeriod.id),
        "Momsperiod rapporterad: ${vatPeriod.periodName}", formatDetails([
            vatPeriodId   : vatPeriod.id,
            fiscalYearId  : vatPeriod.fiscalYearId,
            periodName    : vatPeriod.periodName,
            startDate     : vatPeriod.startDate,
            endDate       : vatPeriod.endDate,
            status        : vatPeriod.status,
            reportHash    : reportHash,
            reportedAt    : vatPeriod.reportedAt
        ]))
  }

  @PackageScope
  AuditLogEntry recordVatPeriodLocked(Sql sql, VatPeriod vatPeriod) {
    recordEvent(sql, VAT_PERIOD_LOCKED, new AuditReferences(voucherId: vatPeriod.transferVoucherId, fiscalYearId: vatPeriod.fiscalYearId, vatPeriodId: vatPeriod.id),
        "Momsperiod låst: ${vatPeriod.periodName}", formatDetails([
            vatPeriodId       : vatPeriod.id,
            fiscalYearId      : vatPeriod.fiscalYearId,
            periodName        : vatPeriod.periodName,
            status            : vatPeriod.status,
            transferVoucherId : vatPeriod.transferVoucherId,
            lockedAt          : vatPeriod.lockedAt
        ]))
  }

  @PackageScope
  AuditLogEntry recordFiscalYearDeleted(Sql sql, long companyId, String fiscalYearName) {
    recordEvent(sql, DELETE_FISCAL_YEAR, AuditReferences.EMPTY,
        "Räkenskapsår raderat: ${fiscalYearName}", null, companyId)
  }

  @PackageScope
  AuditLogEntry recordCompanyArchived(Sql sql, long companyId, String companyName) {
    recordEvent(sql, ARCHIVE_COMPANY, AuditReferences.EMPTY,
        "Företag arkiverat: ${companyName}", null, companyId)
  }

  @PackageScope
  AuditLogEntry recordCompanyUnarchived(Sql sql, long companyId, String companyName) {
    recordEvent(sql, UNARCHIVE_COMPANY, AuditReferences.EMPTY,
        "Företag återställt: ${companyName}", null, companyId)
  }

  @PackageScope
  AuditLogEntry recordEvent(
      Sql sql,
      String eventType,
      AuditReferences references,
      String summary,
      String details,
      Long explicitCompanyId = null
  ) {
    String safeEventType = requireText(eventType, 'Audit event type')
    String safeSummary = requireText(summary, 'Audit summary')
    AuditReferences safeReferences = references ?: AuditReferences.EMPTY
    long companyId = explicitCompanyId ?: resolveCompanyId(sql, safeReferences)
    AuditChainHead chainHead = lockChainHead(sql, companyId)
    AuditEntrySeed seed = new AuditEntrySeed(
        eventType: safeEventType,
        voucherId: safeReferences.voucherId,
        attachmentId: safeReferences.attachmentId,
        fiscalYearId: safeReferences.fiscalYearId,
        accountingPeriodId: safeReferences.accountingPeriodId,
        vatPeriodId: safeReferences.vatPeriodId,
        actor: DEFAULT_ACTOR,
        summary: safeSummary,
        details: details,
        previousHash: chainHead.lastEntryHash,
        createdAt: currentDatabaseTimestamp(sql)
    )
    String entryHash = calculateHash(seed)
    List<List<Object>> keys = sql.executeInsert('''
        insert into audit_log (
            event_type,
            voucher_id,
            attachment_id,
            fiscal_year_id,
            accounting_period_id,
            vat_period_id,
            actor,
            summary,
            details,
            previous_hash,
            entry_hash,
            created_at,
            company_id
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ''', [
        seed.eventType,
        seed.voucherId,
        seed.attachmentId,
        seed.fiscalYearId,
        seed.accountingPeriodId,
        seed.vatPeriodId,
        seed.actor,
        seed.summary,
        seed.details,
        seed.previousHash,
        entryHash,
        Timestamp.valueOf(seed.createdAt),
        companyId
    ])
    long id = ((Number) keys.first().first()).longValue()
    updateChainHead(sql, companyId, entryHash)
    findById(sql, id)
  }

  @PackageScope
  static void rebuildIntegrityChain(Sql sql, long companyId) {
    String previousHash = null
    sql.rows('''
        select id,
               event_type as eventType,
               voucher_id as voucherId,
               attachment_id as attachmentId,
               fiscal_year_id as fiscalYearId,
               accounting_period_id as accountingPeriodId,
               vat_period_id as vatPeriodId,
               actor,
               summary,
               details,
               created_at as createdAt
          from audit_log
         where company_id = ?
         order by id
    ''', [companyId]).each { GroovyRowResult row ->
      String entryHash = calculateHash(new AuditEntrySeed(
          eventType: row.get('eventType') as String,
          voucherId: longOrNull(row.get('voucherId')),
          attachmentId: longOrNull(row.get('attachmentId')),
          fiscalYearId: longOrNull(row.get('fiscalYearId')),
          accountingPeriodId: longOrNull(row.get('accountingPeriodId')),
          vatPeriodId: longOrNull(row.get('vatPeriodId')),
          actor: row.get('actor') as String,
          summary: row.get('summary') as String,
          details: SqlValueMapper.toClob(row.get('details')),
          previousHash: previousHash,
          createdAt: SqlValueMapper.toLocalDateTime(row.get('createdAt'))
      ))
      sql.executeUpdate('''
          update audit_log
             set previous_hash = ?,
                 entry_hash = ?
           where id = ?
      ''', [previousHash, entryHash, row.get('id')])
      previousHash = entryHash
    }
    updateChainHead(sql, companyId, previousHash)
  }

  private AuditLogEntry recordStandaloneEvent(String eventType, String summary, String details, long companyId) {
    databaseService.withTransaction { Sql sql ->
      recordEvent(sql, eventType, AuditReferences.EMPTY, summary, details, companyId)
    }
  }

  private static AuditLogEntry findById(Sql sql, long id) {
    GroovyRowResult row = sql.firstRow('''
        select id,
               event_type as eventType,
               voucher_id as voucherId,
               attachment_id as attachmentId,
               fiscal_year_id as fiscalYearId,
               accounting_period_id as accountingPeriodId,
               vat_period_id as vatPeriodId,
               actor,
               summary,
               details,
               previous_hash as previousHash,
               entry_hash as entryHash,
               created_at as createdAt
          from audit_log
         where id = ?
    ''', [id]) as GroovyRowResult
    row == null ? null : mapEntry(row)
  }

  private static Long resolveCompanyId(Sql sql, AuditReferences references) {
    if (references.voucherId != null) {
      GroovyRowResult row = sql.firstRow(
          'select company_id as companyId from voucher where id = ?',
          [references.voucherId]
      ) as GroovyRowResult
      if (row != null) {
        return ((Number) row.get('companyId')).longValue()
      }
    }
    if (references.fiscalYearId != null) {
      GroovyRowResult row = sql.firstRow(
          'select company_id as companyId from fiscal_year where id = ?',
          [references.fiscalYearId]
      ) as GroovyRowResult
      if (row != null) {
        return ((Number) row.get('companyId')).longValue()
      }
    }
    if (references.attachmentId != null) {
      GroovyRowResult row = sql.firstRow(
          'select company_id as companyId from attachment where id = ?',
          [references.attachmentId]
      ) as GroovyRowResult
      if (row != null) {
        return ((Number) row.get('companyId')).longValue()
      }
    }
    throw new IllegalStateException('Kunde inte avgöra företag från auditreferenserna.')
  }

  private static AuditChainHead lockChainHead(Sql sql, long companyId) {
    GroovyRowResult row = sql.firstRow('''
        select last_entry_hash as lastEntryHash
          from audit_log_chain_head
         where company_id = ?
         for update
    ''', [companyId]) as GroovyRowResult
    if (row == null) {
      throw new IllegalStateException('Kedjehuvudet för audit-loggen saknas.')
    }
    new AuditChainHead(row.get('lastEntryHash') as String)
  }

  private static AuditChainHead loadChainHead(Sql sql, long companyId) {
    GroovyRowResult row = sql.firstRow('''
        select last_entry_hash as lastEntryHash
          from audit_log_chain_head
         where company_id = ?
    ''', [companyId]) as GroovyRowResult
    row == null ? null : new AuditChainHead(row.get('lastEntryHash') as String)
  }

  private static void updateChainHead(Sql sql, long companyId, String entryHash) {
    int updated = sql.executeUpdate('''
        update audit_log_chain_head
           set last_entry_hash = ?,
               updated_at = current_timestamp
         where company_id = ?
    ''', [entryHash, companyId])
    if (updated != 1) {
      throw new IllegalStateException('Kedjehuvudet för audit-loggen kunde inte uppdateras.')
    }
  }

  private static AuditLogEntry mapEntry(GroovyRowResult row) {
    new AuditLogEntry(
        Long.valueOf(row.get('id').toString()),
        row.get('eventType') as String,
        longOrNull(row.get('voucherId')),
        longOrNull(row.get('attachmentId')),
        longOrNull(row.get('fiscalYearId')),
        longOrNull(row.get('accountingPeriodId')),
        longOrNull(row.get('vatPeriodId')),
        row.get('actor') as String,
        row.get('summary') as String,
        SqlValueMapper.toClob(row.get('details')),
        row.get('previousHash') as String,
        row.get('entryHash') as String,
        SqlValueMapper.toLocalDateTime(row.get('createdAt'))
    )
  }

  private static Long longOrNull(Object value) {
    value == null ? null : Long.valueOf(value.toString())
  }

  private static String requireText(String value, String label) {
    String safeValue = value?.trim()
    if (!safeValue) {
      throw new IllegalArgumentException("${label} is required.")
    }
    safeValue
  }

  private static LocalDateTime currentDatabaseTimestamp(Sql sql) {
    GroovyRowResult row = sql.firstRow('select current_timestamp as createdAt') as GroovyRowResult
    SqlValueMapper.toLocalDateTime(row.get('createdAt'))
  }

  private static String calculateHash(AuditEntrySeed seed) {
    StringBuilder payload = new StringBuilder()
    payload.append(seed.previousHash ?: '').append('\n')
    payload.append(seed.eventType).append('|')
    payload.append(seed.voucherId ?: '').append('|')
    payload.append(seed.attachmentId ?: '').append('|')
    payload.append(seed.fiscalYearId ?: '').append('|')
    payload.append(seed.accountingPeriodId ?: '').append('|')
    payload.append(seed.vatPeriodId ?: '').append('|')
    payload.append(seed.actor ?: '').append('|')
    payload.append(seed.summary ?: '').append('|')
    payload.append(seed.details ?: '').append('|')
    payload.append(formatCreatedAt(seed.createdAt))
    MessageDigest digest = MessageDigest.getInstance('SHA-256')
    HexFormat.of().formatHex(digest.digest(payload.toString().getBytes(StandardCharsets.UTF_8)))
  }

  private static String formatCreatedAt(LocalDateTime createdAt) {
    createdAt == null ? '' : createdAt.format(HASH_TIMESTAMP_FORMAT)
  }

  private static String formatLines(List<VoucherLine> lines) {
    if (lines == null || lines.isEmpty()) {
      return ''
    }
    lines.collect { VoucherLine line ->
      "${line.lineIndex}:${line.accountNumber ?: ''}|debit=${line.debitAmount ?: 0}|credit=${line.creditAmount ?: 0}|text=${line.description ?: ''}"
    }.join(';')
  }

  private static String formatDetails(Map<String, Object> details) {
    Map<String, Object> safeDetails = details.findAll { String key, Object value ->
      value != null
    }.sort { Map.Entry<String, Object> entry ->
      entry.key
    } as Map<String, Object>
    safeDetails.collect { String key, Object value ->
      "${key}=${value}"
    }.join('\n')
  }

  private static final class AuditReferences {

    static final AuditReferences EMPTY = new AuditReferences()

    Long voucherId
    Long attachmentId
    Long fiscalYearId
    Long accountingPeriodId
    Long vatPeriodId
  }

  private static final class AuditEntrySeed {

    String eventType
    Long voucherId
    Long attachmentId
    Long fiscalYearId
    Long accountingPeriodId
    Long vatPeriodId
    String actor
    String summary
    String details
    String previousHash
    LocalDateTime createdAt
  }

  private static final class AuditChainHead {

    final String lastEntryHash

    private AuditChainHead(String lastEntryHash) {
      this.lastEntryHash = lastEntryHash
    }
  }
}
