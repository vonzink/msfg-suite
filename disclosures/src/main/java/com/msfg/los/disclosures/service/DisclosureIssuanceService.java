package com.msfg.los.disclosures.service;

import com.msfg.los.disclosures.domain.DeliveryMethod;
import com.msfg.los.disclosures.domain.DisclosureEvent;
import com.msfg.los.disclosures.domain.DisclosureEventType;
import com.msfg.los.disclosures.domain.DisclosureIssuance;
import com.msfg.los.disclosures.domain.DisclosureKind;
import com.msfg.los.disclosures.domain.DisclosureStatus;
import com.msfg.los.disclosures.repo.DisclosureEventRepository;
import com.msfg.los.disclosures.repo.DisclosureIssuanceRepository;
import com.msfg.los.disclosures.service.DisclosureAssemblyService.AssemblyResult;
import com.msfg.los.disclosures.web.dto.IssueDisclosureRequest;
import com.msfg.los.documents.domain.Document;
import com.msfg.los.documents.domain.DocumentType;
import com.msfg.los.documents.service.DocumentService;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.platform.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
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

    public DisclosureIssuanceService(LoanService loanService,
                                     LoanAccessGuard accessGuard,
                                     DisclosureAssemblyService assemblyService,
                                     DisclosureVendorPort vendorPort,
                                     DocumentService documentService,
                                     DisclosureTimingService timingService,
                                     DisclosureIssuanceRepository issuanceRepo,
                                     DisclosureEventRepository eventRepo,
                                     CurrentUser currentUser) {
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.assemblyService = assemblyService;
        this.vendorPort = vendorPort;
        this.documentService = documentService;
        this.timingService = timingService;
        this.issuanceRepo = issuanceRepo;
        this.eventRepo = eventRepo;
        this.currentUser = currentUser;
    }

    @Transactional
    public DisclosureIssuance issue(UUID loanId, DisclosureKind kind, IssueDisclosureRequest req) {
        // Guard FIRST: 404 cross-tenant / 403 by role before anything is generated or persisted.
        accessGuard.assertCanAccess(loanService.get(loanId));

        AssemblyResult ar = assemblyService.assemble(loanId, kind);
        DisclosureGenerationResult gen = vendorPort.generate(ar.request());

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
        DeliveryResult del = vendorPort.send(new DeliveryRequest(loanId, null, kind, method));

        LocalDate today = LocalDate.now();
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
        issuance.setPrepaymentPenalty(ar.request().prepaymentPenalty());
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
        issuance.setResetTriggered(false);
        issuance.setRequestedBy(currentUser.id().orElse(null));
        issuance.setRequestedAt(Instant.now());
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

        return saved;
    }
}
