package com.msfg.los.disclosures.service;

import com.msfg.los.disclosures.domain.DeliveryMethod;
import com.msfg.los.disclosures.domain.DisclosureEvent;
import com.msfg.los.disclosures.domain.DisclosureEventType;
import com.msfg.los.disclosures.domain.DisclosureIssuance;
import com.msfg.los.disclosures.domain.DisclosureKind;
import com.msfg.los.disclosures.domain.DisclosureStatus;
import com.msfg.los.disclosures.domain.DisclosureCostRow;
import com.msfg.los.disclosures.domain.ReceivedBasis;
import com.msfg.los.disclosures.domain.ResetReason;
import com.msfg.los.disclosures.repo.DisclosureEventRepository;
import com.msfg.los.disclosures.repo.DisclosureIssuanceRepository;
import com.msfg.los.disclosures.service.DisclosureAssemblyService.AssemblyResult;
import com.msfg.los.disclosures.tolerance.ToleranceComparison;
import com.msfg.los.disclosures.tolerance.TolerancePolicy;
import com.msfg.los.disclosures.web.dto.IssueDisclosureRequest;
import com.msfg.los.disclosures.web.dto.TimingResponse;
import com.msfg.los.disclosures.web.dto.ToleranceResponse;
import com.msfg.los.documents.domain.Document;
import com.msfg.los.documents.domain.DocumentType;
import com.msfg.los.documents.service.DocumentService;
import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issues a disclosure (LE or CD) for a loan: assembles the figures, runs them through the vendor
 * port to generate the regulated form + APR, stores the rendered form as a real loan document,
 * computes TRID receipt/consummation timing, and persists a versioned {@link DisclosureIssuance}
 * plus an append-only {@link DisclosureEvent} audit row.
 *
 * <p>{@link #issue} is kind-generic so the same path serves both LE issuance (wired now) and CD
 * issuance + the revised-LE reset flow (Task 10). Only the LE endpoint is exposed in this task.
 */
@Service
public class DisclosureIssuanceService {

    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final DisclosureAssemblyService assemblyService;
    private final DisclosureVendorPort vendorPort;
    private final DocumentService documentService;
    private final DisclosureTimingService timingService;
    private final DisclosureIssuanceRepository issuanceRepo;
    private final DisclosureEventRepository eventRepo;
    private final CurrentUser currentUser;
    private final TenantContext tenantContext;
    private final TolerancePolicy tolerancePolicy;
    private final ResetDetector resetDetector;
    private final DisclosureIssuanceErrorRecorder errorRecorder;
    private final RevisedLeClockService revisedLeClockService;

    /** "Successfully issued" statuses — excludes ERROR rows from prior-CD and baseline-LE lookups. */
    private static final List<DisclosureStatus> ISSUED_STATUSES =
            List.of(DisclosureStatus.SENT, DisclosureStatus.RECEIVED);

    public DisclosureIssuanceService(LoanService loanService,
                                     LoanAccessGuard accessGuard,
                                     DisclosureAssemblyService assemblyService,
                                     DisclosureVendorPort vendorPort,
                                     DocumentService documentService,
                                     DisclosureTimingService timingService,
                                     DisclosureIssuanceRepository issuanceRepo,
                                     DisclosureEventRepository eventRepo,
                                     CurrentUser currentUser,
                                     TenantContext tenantContext,
                                     TolerancePolicy tolerancePolicy,
                                     ResetDetector resetDetector,
                                     DisclosureIssuanceErrorRecorder errorRecorder,
                                     RevisedLeClockService revisedLeClockService) {
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.assemblyService = assemblyService;
        this.vendorPort = vendorPort;
        this.documentService = documentService;
        this.timingService = timingService;
        this.issuanceRepo = issuanceRepo;
        this.eventRepo = eventRepo;
        this.currentUser = currentUser;
        this.tenantContext = tenantContext;
        this.tolerancePolicy = tolerancePolicy;
        this.resetDetector = resetDetector;
        this.errorRecorder = errorRecorder;
        this.revisedLeClockService = revisedLeClockService;
    }

    private UUID org() {
        return tenantContext.orgId().orElseThrow(() -> new NotFoundException("Tenant", "current"));
    }

    @Transactional
    public DisclosureIssuance issue(UUID loanId, DisclosureKind kind, IssueDisclosureRequest req) {
        // Guard FIRST: 404 cross-tenant / 403 by role before anything is generated or persisted.
        accessGuard.assertCanAccess(loanService.get(loanId));

        AssemblyResult ar = assemblyService.assemble(loanId, kind);

        // Effective prepayment penalty: request override (no loan field carries it yet) else assembled.
        boolean effectivePrepay = (req != null && req.prepaymentPenalty() != null)
                ? req.prepaymentPenalty()
                : ar.request().prepaymentPenalty();

        // For a CD, capture the last SUCCESSFULLY-ISSUED prior CD BEFORE persisting the new one so
        // reset detection compares against the last good CD, not the row we are about to save and
        // NOT a poisoned ERROR row (apr=null) that would NPE the comparison. The version counter
        // below still walks every row (including ERROR), so disclosure_version stays gap-tolerant.
        DisclosureIssuance priorCd = kind == DisclosureKind.CLOSING_DISCLOSURE
                ? issuanceRepo.findTopByLoanIdAndKindAndStatusInOrderByDisclosureVersionDesc(
                        loanId, DisclosureKind.CLOSING_DISCLOSURE, ISSUED_STATUSES).orElse(null)
                : null;

        DisclosureGenerationResult gen;
        try {
            gen = vendorPort.generate(ar.request());
        } catch (RuntimeException e) {
            // REQUIRES_NEW ERROR row survives this transaction's rollback; rethrow to fail the request.
            errorRecorder.recordError(loanId, kind, currentUser.id().orElse(null), e.getMessage());
            throw e;
        }

        int version = issuanceRepo.findTopByLoanIdAndKindOrderByDisclosureVersionDesc(loanId, kind)
                .map(prev -> prev.getDisclosureVersion() + 1)
                .orElse(1);

        DocumentType docType = kind == DisclosureKind.LOAN_ESTIMATE
                ? DocumentType.LOAN_ESTIMATE
                : DocumentType.CLOSING_DISCLOSURE;
        String fileName = kind.name().toLowerCase() + "-v" + version + ".html";
        Document doc = documentService.storeGenerated(
                loanId, docType, "disclosure", fileName,
                gen.renderedContentType(), gen.renderedBytes());

        DeliveryMethod method = (req != null && req.deliveryMethod() != null)
                ? req.deliveryMethod()
                : DeliveryMethod.EMAIL;
        DeliveryResult del;
        try {
            del = vendorPort.send(new DeliveryRequest(loanId, null, kind, method));
        } catch (RuntimeException e) {
            errorRecorder.recordError(loanId, kind, currentUser.id().orElse(null), e.getMessage());
            throw e;
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate computedReceived = timingService.constructiveReceived(today);
        LocalDate earliest = kind == DisclosureKind.LOAN_ESTIMATE
                ? timingService.earliestConsummationForLe(today)
                : timingService.earliestConsummationForCd(computedReceived);

        DisclosureIssuance issuance = new DisclosureIssuance();
        issuance.setLoanId(loanId);
        issuance.setKind(kind);
        issuance.setDisclosureVersion(version);
        issuance.setStatus(DisclosureStatus.SENT);
        issuance.setApr(gen.apr());
        issuance.setFinanceCharge(gen.financeCharge());
        issuance.setAmountFinanced(gen.amountFinanced());
        issuance.setTotalOfPayments(gen.totalOfPayments());
        issuance.setTip(gen.tip());
        issuance.setAprIrregularBasis(gen.aprIrregularBasis());
        issuance.setPrepaymentPenalty(effectivePrepay);
        issuance.setProductDescription(ar.request().productDescription());
        issuance.setDeliveryMethod(method);
        issuance.setDeliveredAt(Instant.now());
        issuance.setReceivedBasis(del.basis());
        issuance.setComputedReceivedDate(computedReceived);
        issuance.setEarliestConsummationDate(earliest);
        issuance.setDocumentId(doc.getId());
        issuance.setVendorReference(gen.vendorReference());
        issuance.setSnapshot(ar.snapshot());
        issuance.setTriggerCocId(req != null ? req.triggerCocId() : null);
        issuance.setRequestedBy(currentUser.id().orElse(null));
        issuance.setRequestedAt(Instant.now());

        // CD re-disclosure: detect the TRID reset triggers against the prior CD (captured pre-save).
        List<ResetReason> resetReasons = List.of();
        if (kind == DisclosureKind.CLOSING_DISCLOSURE && priorCd != null) {
            resetReasons = resetDetector.detect(
                    priorCd, gen, ar.request().productDescription(), effectivePrepay);
        }
        issuance.setResetTriggered(!resetReasons.isEmpty());
        issuance.setResetReasons(new ArrayList<>(resetReasons));
        DisclosureIssuance saved = issuanceRepo.save(issuance);

        DisclosureEvent event = new DisclosureEvent();
        event.setLoanId(loanId);
        event.setDisclosureId(saved.getId());
        event.setEventType(kind == DisclosureKind.LOAN_ESTIMATE
                ? DisclosureEventType.LE_ISSUED
                : DisclosureEventType.CD_ISSUED);
        event.setActor(currentUser.id().orElse(null));
        event.setOccurredAt(Instant.now());
        event.setDetail(Map.of("version", version));
        eventRepo.save(event);

        if (!resetReasons.isEmpty()) {
            DisclosureEvent resetEvent = new DisclosureEvent();
            resetEvent.setLoanId(loanId);
            resetEvent.setDisclosureId(saved.getId());
            resetEvent.setEventType(DisclosureEventType.RESET_TRIGGERED);
            resetEvent.setActor(currentUser.id().orElse(null));
            resetEvent.setOccurredAt(Instant.now());
            resetEvent.setDetail(Map.of("reasons", resetReasons.toString()));
            eventRepo.save(resetEvent);
        }

        return saved;
    }

    /**
     * Records actual consumer receipt of an issued disclosure: flips the basis to {@code ACTUAL},
     * sets {@code computedReceivedDate} to the stated date, marks the issuance {@code RECEIVED}, and
     * recomputes the earliest-consummation date for a CD (3 precise business days after receipt). An
     * LE's earliest-consummation is keyed to delivery, not receipt, so it is left as-is. Append-only
     * {@link DisclosureEvent} audit row written.
     */
    @Transactional
    public DisclosureIssuance recordReceipt(UUID loanId, UUID disclosureId, LocalDate receivedAt) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        DisclosureIssuance issuance = loadInLoan(loanId, disclosureId);

        issuance.setReceivedAt(receivedAt.atStartOfDay(ZoneOffset.UTC).toInstant());
        issuance.setReceivedBasis(ReceivedBasis.ACTUAL);
        issuance.setComputedReceivedDate(receivedAt);
        issuance.setStatus(DisclosureStatus.RECEIVED);
        if (issuance.getKind() == DisclosureKind.CLOSING_DISCLOSURE) {
            issuance.setEarliestConsummationDate(timingService.earliestConsummationForCd(receivedAt));
        }
        DisclosureIssuance saved = issuanceRepo.save(issuance);

        DisclosureEvent event = new DisclosureEvent();
        event.setLoanId(loanId);
        event.setDisclosureId(saved.getId());
        event.setEventType(DisclosureEventType.RECEIPT_RECORDED);
        event.setActor(currentUser.id().orElse(null));
        event.setOccurredAt(Instant.now());
        event.setDetail(Map.of("receivedAt", receivedAt.toString()));
        eventRepo.save(event);

        return saved;
    }

    /** Loan-level TRID timing rollup across the latest LE and latest CD. */
    @Transactional(readOnly = true)
    public TimingResponse timing(UUID loanId) {
        Loan loan = loanService.get(loanId);
        accessGuard.assertCanAccess(loan);

        java.util.Optional<DisclosureIssuance> latestLe = issuanceRepo
                .findTopByLoanIdAndKindOrderByDisclosureVersionDesc(loanId, DisclosureKind.LOAN_ESTIMATE);

        LocalDate le = latestLe.map(DisclosureIssuance::getEarliestConsummationDate).orElse(null);
        LocalDate cd = issuanceRepo
                .findTopByLoanIdAndKindOrderByDisclosureVersionDesc(loanId, DisclosureKind.CLOSING_DISCLOSURE)
                .map(DisclosureIssuance::getEarliestConsummationDate).orElse(null);

        LocalDate overall;
        if (le == null) {
            overall = cd;
        } else if (cd == null) {
            overall = le;
        } else {
            overall = le.isAfter(cd) ? le : cd;
        }

        LocalDate consummationDate = loan.getConsummationDate();
        Boolean satisfies = (consummationDate == null || overall == null)
                ? null
                : !consummationDate.isBefore(overall);

        LocalDate revisedLeDeadline = revisedLeClockService.revisedLeDeadline(loanId);

        // Initial-LE delivery deadline (1026.19(e)(1)(iii)): 3 general business days after application.
        // v1 proxies the application date with the loan's createdAt (UTC). leDeliveredOnTime compares
        // the latest LE's deliveredAt (UTC date) against that deadline; null when no LE issued.
        LocalDate appDate = loan.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate leDeliveryDeadline = timingService.leDeliveryDeadline(appDate);
        Boolean leDeliveredOnTime = latestLe
                .map(i -> !i.getDeliveredAt().atZone(ZoneOffset.UTC).toLocalDate().isAfter(leDeliveryDeadline))
                .orElse(null);

        return new TimingResponse(le, cd, overall, consummationDate, satisfies, revisedLeDeadline,
                leDeliveryDeadline, leDeliveredOnTime);
    }

    /**
     * Good-faith tolerance view. TRID 1026.19(e)(3) measures the CD's <em>actual</em> charges against
     * the latest good-faith LE baseline:
     * <ul>
     *   <li><b>baseline</b> = the latest successfully-issued LE's snapshot cost rows. A valid revised
     *       LE re-baselines (we keep the LATEST good LE, not version 1). Null when no LE issued yet.</li>
     *   <li><b>current</b> = the latest successfully-issued CD's snapshot cost rows when one exists
     *       (the actual charges that matter); otherwise the live LE re-assembly as an advisory
     *       pre-CD view of where the numbers stand right now.</li>
     * </ul>
     * Bucket totals are grouped on the CURRENT set; the comparison is null until a baseline LE exists.
     */
    @Transactional(readOnly = true)
    public ToleranceResponse tolerance(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        List<DisclosureCostRow> baseline = issuanceRepo
                .findTopByLoanIdAndKindAndStatusInOrderByDisclosureVersionDesc(
                        loanId, DisclosureKind.LOAN_ESTIMATE, ISSUED_STATUSES)
                .map(i -> i.getSnapshot().costRows()).orElse(null);

        List<DisclosureCostRow> currentRows = issuanceRepo
                .findTopByLoanIdAndKindAndStatusInOrderByDisclosureVersionDesc(
                        loanId, DisclosureKind.CLOSING_DISCLOSURE, ISSUED_STATUSES)
                .map(i -> i.getSnapshot().costRows())
                .orElseGet(() -> assemblyService.assemble(loanId, DisclosureKind.LOAN_ESTIMATE)
                        .request().costTable());

        Map<String, BigDecimal> bucketTotals = new LinkedHashMap<>();
        for (DisclosureCostRow row : currentRows) {
            String key = row.bucket().name();
            BigDecimal amount = row.amount() == null ? BigDecimal.ZERO : row.amount();
            bucketTotals.merge(key, amount, BigDecimal::add);
        }
        bucketTotals.replaceAll((k, v) -> v.setScale(2, RoundingMode.HALF_UP));

        ToleranceComparison comparison =
                baseline == null ? null : tolerancePolicy.compare(baseline, currentRows);

        return new ToleranceResponse(bucketTotals, comparison);
    }

    /** Full issuance history for a loan, newest first. */
    @Transactional(readOnly = true)
    public List<DisclosureIssuance> history(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return issuanceRepo.findByLoanIdOrderByRequestedAtDescIdDesc(loanId);
    }

    /** One issuance, tenant + loan scoped (404 cross-tenant or cross-loan). */
    @Transactional(readOnly = true)
    public DisclosureIssuance get(UUID loanId, UUID disclosureId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return loadInLoan(loanId, disclosureId);
    }

    private DisclosureIssuance loadInLoan(UUID loanId, UUID disclosureId) {
        return issuanceRepo.findByIdAndOrgId(disclosureId, org())
                .filter(i -> i.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("Disclosure", disclosureId));
    }
}
