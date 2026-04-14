package se.alipsa.accounting.service

import se.alipsa.accounting.domain.AttachmentMetadata

/**
 * Blocks reporting exports when integrity checks reveal critical problems.
 */
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
    attachmentService.findAllIntegrityFailures().each { AttachmentMetadata attachment ->
      problems << ("Bilaga ${attachment.id} har avvikande checksumma eller saknas på disk." as String)
    }
    problems.addAll(auditLogService.validateIntegrity())
    problems
  }

  List<String> listCriticalProblems(long companyId) {
    CompanyService.requireValidCompanyId(companyId)
    List<String> problems = []
    problems.addAll(voucherService.validateIntegrity(companyId))
    attachmentService.findIntegrityFailures(companyId).each { AttachmentMetadata attachment ->
      problems << ("Bilaga ${attachment.id} har avvikande checksumma eller saknas på disk." as String)
    }
    problems.addAll(auditLogService.validateIntegrity(companyId))
    problems
  }

  void ensureOperationAllowed(String operationLabel) {
    String safeOperationLabel = operationLabel?.trim() ?: 'Operationen'
    List<String> problems = listCriticalProblems()
    blockIfProblems(safeOperationLabel, problems)
  }

  void ensureOperationAllowed(long companyId, String operationLabel) {
    String safeOperationLabel = operationLabel?.trim() ?: 'Operationen'
    List<String> problems = listCriticalProblems(companyId)
    blockIfProblems(safeOperationLabel, problems)
  }

  void ensureReportingAllowed() {
    ensureOperationAllowed('Rapportexport')
  }

  void ensureReportingAllowed(long companyId) {
    ensureOperationAllowed(companyId, 'Rapportexport')
  }

  private static void blockIfProblems(String operationLabel, List<String> problems) {
    if (!problems.isEmpty()) {
      String summary = problems.take(5).join('\n')
      if (problems.size() > 5) {
        summary = "${summary}\n... samt ${problems.size() - 5} ytterligare problem."
      }
      throw new IllegalStateException("${operationLabel} blockeras eftersom integritetskontrollerna har fel:\n${summary}")
    }
  }
}
