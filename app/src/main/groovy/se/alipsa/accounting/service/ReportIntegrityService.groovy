package se.alipsa.accounting.service

import groovy.transform.CompileStatic

import se.alipsa.accounting.domain.AttachmentMetadata

/**
 * Blocks reporting exports when integrity checks reveal critical problems.
 */
@CompileStatic
final class ReportIntegrityService {

  private final VoucherService voucherService
  private final AttachmentService attachmentService
  private final AuditLogService auditLogService

  ReportIntegrityService() {
    this(new VoucherService(), new AttachmentService(), new AuditLogService())
  }

  ReportIntegrityService(
      VoucherService voucherService,
      AttachmentService attachmentService,
      AuditLogService auditLogService
  ) {
    this.voucherService = voucherService
    this.attachmentService = attachmentService
    this.auditLogService = auditLogService
  }

  List<String> listCriticalProblems() {
    List<String> problems = []
    problems.addAll(voucherService.validateIntegrity())
    attachmentService.findIntegrityFailures().each { AttachmentMetadata attachment ->
      problems << ("Bilaga ${attachment.id} har avvikande checksumma eller saknas på disk." as String)
    }
    problems.addAll(auditLogService.validateIntegrity())
    problems
  }

  void ensureOperationAllowed(String operationLabel) {
    String safeOperationLabel = operationLabel?.trim() ?: 'Operationen'
    List<String> problems = listCriticalProblems()
    if (!problems.isEmpty()) {
      String summary = problems.take(5).join('\n')
      if (problems.size() > 5) {
        summary = "${summary}\n... samt ${problems.size() - 5} ytterligare problem."
      }
      throw new IllegalStateException("${safeOperationLabel} blockeras eftersom integritetskontrollerna har fel:\n${summary}")
    }
  }

  void ensureReportingAllowed() {
    ensureOperationAllowed('Rapportexport')
  }
}
