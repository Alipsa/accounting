package se.alipsa.accounting.service

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.CompileStatic
import groovy.transform.PackageScope

import se.alipsa.accounting.domain.AccountingPeriod
import se.alipsa.accounting.domain.AttachmentMetadata
import se.alipsa.accounting.domain.AuditLogEntry
import se.alipsa.accounting.domain.FiscalYear
import se.alipsa.accounting.domain.VatPeriod
import se.alipsa.accounting.domain.Voucher

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Clob
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Persists immutable audit entries for business-critical operations.
 */
@CompileStatic
final class AuditLogService {

  static final String CREATE_VOUCHER = 'CREATE_VOUCHER'
  static final String BOOK_VOUCHER = 'BOOK_VOUCHER'
  static final String CANCEL_VOUCHER = 'CANCEL_VOUCHER'
  static final String CORRECTION_VOUCHER = 'CORRECTION_VOUCHER'
  static final String ATTACHMENT_ADDED = 'ATTACHMENT_ADDED'
  static final String LOCK_PERIOD = 'LOCK_PERIOD'
  static final String CLOSE_FISCAL_YEAR = 'CLOSE_FISCAL_YEAR'
  static final String VAT_PERIOD_REPORTED = 'VAT_PERIOD_REPORTED'
  static final String VAT_PERIOD_LOCKED = 'VAT_PERIOD_LOCKED'
  static final String IMPORT = 'IMPORT'
  static final String EXPORT = 'EXPORT'
  static final String BACKUP = 'BACKUP'
  static final String RESTORE = 'RESTORE'

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
                 actor,
                 summary,
                 details,
                 previous_hash as previousHash,
                 entry_hash as entryHash,
                 created_at as createdAt
            from audit_log
           where voucher_id = ?
           order by created_at desc, id desc
      ''', [voucherId]).collect { GroovyRowResult row ->
        mapEntry(row)
      }
    }
  }

  List<AuditLogEntry> listEntries(int limit = 200) {
    int safeLimit = Math.max(1, limit)
    databaseService.withSql { Sql sql ->
      sql.rows('''
          select id,
                 event_type as eventType,
                 voucher_id as voucherId,
                 attachment_id as attachmentId,
                 fiscal_year_id as fiscalYearId,
                 accounting_period_id as accountingPeriodId,
                 actor,
                 summary,
                 details,
                 previous_hash as previousHash,
                 entry_hash as entryHash,
                 created_at as createdAt
            from audit_log
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
      String expectedPreviousHash = null
      String actualLastHash = null
      sql.rows('''
          select id,
                 event_type as eventType,
                 voucher_id as voucherId,
                 attachment_id as attachmentId,
                 fiscal_year_id as fiscalYearId,
                 accounting_period_id as accountingPeriodId,
                 actor,
                 summary,
                 details,
                 previous_hash as previousHash,
                 entry_hash as entryHash,
                 created_at as createdAt
            from audit_log
           order by id
      ''').each { GroovyRowResult row ->
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
      AuditChainHead chainHead = loadChainHead(sql)
      if (chainHead == null) {
        problems << 'Auditloggkedjans huvud saknas.'
      } else if (chainHead.lastEntryHash != actualLastHash) {
        problems << 'Auditloggkedjans huvud pekar inte på sista auditraden.'
      }
      problems
    }
  }

  AuditLogEntry logImport(String summary, String details = null) {
    recordStandaloneEvent(IMPORT, summary, details)
  }

  AuditLogEntry logExport(String summary, String details = null) {
    recordStandaloneEvent(EXPORT, summary, details)
  }

  AuditLogEntry logBackup(String summary, String details = null) {
    recordStandaloneEvent(BACKUP, summary, details)
  }

  AuditLogEntry logRestore(String summary, String details = null) {
    recordStandaloneEvent(RESTORE, summary, details)
  }

  @PackageScope
  AuditLogEntry recordVoucherCreated(Sql sql, Voucher voucher) {
    recordEvent(sql, CREATE_VOUCHER, new AuditReferences(voucherId: voucher.id, fiscalYearId: voucher.fiscalYearId),
        "Verifikation skapad: ${voucher.description}", formatDetails([
            voucherId      : voucher.id,
            fiscalYearId   : voucher.fiscalYearId,
            accountingDate : voucher.accountingDate,
            description    : voucher.description,
            status         : voucher.status?.name()
        ]))
  }

  @PackageScope
  AuditLogEntry recordVoucherBooked(Sql sql, Voucher voucher) {
    recordEvent(sql, BOOK_VOUCHER, new AuditReferences(voucherId: voucher.id, fiscalYearId: voucher.fiscalYearId),
        "Verifikation bokförd: ${voucher.voucherNumber ?: voucher.id}", formatDetails([
            voucherId      : voucher.id,
            voucherNumber  : voucher.voucherNumber,
            runningNumber  : voucher.runningNumber,
            accountingDate : voucher.accountingDate,
            status         : voucher.status?.name(),
            contentHash    : voucher.contentHash
        ]))
  }

  @PackageScope
  AuditLogEntry recordVoucherCancelled(Sql sql, Voucher voucher) {
    recordEvent(sql, CANCEL_VOUCHER, new AuditReferences(voucherId: voucher.id, fiscalYearId: voucher.fiscalYearId),
        "Verifikation makulerad: ${voucher.id}", formatDetails([
            voucherId      : voucher.id,
            accountingDate : voucher.accountingDate,
            description    : voucher.description,
            status         : voucher.status?.name()
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
            contentHash       : voucher.contentHash
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
  AuditLogEntry recordVatPeriodReported(Sql sql, VatPeriod vatPeriod, String reportHash) {
    recordEvent(sql, VAT_PERIOD_REPORTED, new AuditReferences(fiscalYearId: vatPeriod.fiscalYearId),
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
    recordEvent(sql, VAT_PERIOD_LOCKED, new AuditReferences(voucherId: vatPeriod.transferVoucherId, fiscalYearId: vatPeriod.fiscalYearId),
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
  AuditLogEntry recordEvent(
      Sql sql,
      String eventType,
      AuditReferences references,
      String summary,
      String details
  ) {
    String safeEventType = requireText(eventType, 'Audit event type')
    String safeSummary = requireText(summary, 'Audit summary')
    AuditReferences safeReferences = references ?: AuditReferences.EMPTY
    AuditChainHead chainHead = lockChainHead(sql)
    AuditEntrySeed seed = new AuditEntrySeed(
        eventType: safeEventType,
        voucherId: safeReferences.voucherId,
        attachmentId: safeReferences.attachmentId,
        fiscalYearId: safeReferences.fiscalYearId,
        accountingPeriodId: safeReferences.accountingPeriodId,
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
            actor,
            summary,
            details,
            previous_hash,
            entry_hash,
            created_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ''', [
        seed.eventType,
        seed.voucherId,
        seed.attachmentId,
        seed.fiscalYearId,
        seed.accountingPeriodId,
        seed.actor,
        seed.summary,
        seed.details,
        seed.previousHash,
        entryHash,
        Timestamp.valueOf(seed.createdAt)
    ])
    long id = ((Number) keys.first().first()).longValue()
    updateChainHead(sql, entryHash)
    findById(sql, id)
  }

  private AuditLogEntry recordStandaloneEvent(String eventType, String summary, String details) {
    databaseService.withTransaction { Sql sql ->
      recordEvent(sql, eventType, AuditReferences.EMPTY, summary, details)
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

  private static AuditChainHead lockChainHead(Sql sql) {
    GroovyRowResult row = sql.firstRow('''
        select last_entry_hash as lastEntryHash
          from audit_log_chain_head
         where id = 1
         for update
    ''') as GroovyRowResult
    if (row == null) {
      throw new IllegalStateException('Kedjehuvudet för audit-loggen saknas.')
    }
    new AuditChainHead(row.get('lastEntryHash') as String)
  }

  private static AuditChainHead loadChainHead(Sql sql) {
    GroovyRowResult row = sql.firstRow('''
        select last_entry_hash as lastEntryHash
          from audit_log_chain_head
         where id = 1
    ''') as GroovyRowResult
    row == null ? null : new AuditChainHead(row.get('lastEntryHash') as String)
  }

  private static void updateChainHead(Sql sql, String entryHash) {
    int updated = sql.executeUpdate('''
        update audit_log_chain_head
           set last_entry_hash = ?,
               updated_at = current_timestamp
         where id = 1
    ''', [entryHash])
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
        row.get('actor') as String,
        row.get('summary') as String,
        readText(row.get('details')),
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

  private static String readText(Object value) {
    if (value == null) {
      return null
    }
    if (value instanceof Clob) {
      Clob clob = (Clob) value
      return clob.getSubString(1L, (int) clob.length())
    }
    value.toString()
  }

  private static final class AuditReferences {

    static final AuditReferences EMPTY = new AuditReferences()

    Long voucherId
    Long attachmentId
    Long fiscalYearId
    Long accountingPeriodId
  }

  private static final class AuditEntrySeed {

    String eventType
    Long voucherId
    Long attachmentId
    Long fiscalYearId
    Long accountingPeriodId
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
