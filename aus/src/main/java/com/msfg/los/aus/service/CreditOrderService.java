package com.msfg.los.aus.service;

import com.msfg.los.aus.domain.CredentialVendor;
import com.msfg.los.aus.domain.CreditOrder;
import com.msfg.los.aus.domain.CreditOrderAction;
import com.msfg.los.aus.domain.CreditOrderStatus;
import com.msfg.los.aus.repo.AusProfileRepository;
import com.msfg.los.aus.repo.CreditOrderRepository;
import com.msfg.los.aus.web.dto.CreditOrderRequest;
import com.msfg.los.aus.web.dto.CreditOrderResponse;
import com.msfg.los.documents.domain.Document;
import com.msfg.los.documents.domain.DocumentType;
import com.msfg.los.documents.service.DocumentService;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.domain.BorrowerParty;
import com.msfg.los.parties.repo.BorrowerRepository;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orders (or reissues) a credit report through the {@link CreditVendorPort}, stores the vendor
 * report as a real loan document ({@code CREDIT_REPORT}), and keeps every order as its own
 * history row — REISSUE reuses the bureau-assigned identifier but lands a new row with its
 * own artifact.
 */
@Service
public class CreditOrderService {

    private final CreditOrderRepository orders;
    private final CreditVendorPort creditVendor;
    private final DocumentService documentService;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final BorrowerRepository borrowers;
    private final AusProfileRepository profiles;
    private final VendorCredentialService credentials;
    private final CurrentUser currentUser;

    public CreditOrderService(CreditOrderRepository orders,
                              CreditVendorPort creditVendor,
                              DocumentService documentService,
                              LoanService loanService,
                              LoanAccessGuard accessGuard,
                              BorrowerRepository borrowers,
                              AusProfileRepository profiles,
                              VendorCredentialService credentials,
                              CurrentUser currentUser) {
        this.orders = orders;
        this.creditVendor = creditVendor;
        this.documentService = documentService;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.borrowers = borrowers;
        this.profiles = profiles;
        this.credentials = credentials;
        this.currentUser = currentUser;
    }

    private void guard(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
    }

    @Transactional
    public CreditOrderResponse order(UUID loanId, CreditOrderRequest req) {
        guard(loanId);

        if (req.action() == null) {
            throw new ValidationException("action is required");
        }
        if (req.requestType() == null) {
            throw new ValidationException("requestType is required");
        }
        if (req.borrowerIds() == null || req.borrowerIds().isEmpty()) {
            throw new ValidationException("borrowerIds required");
        }
        // Every requested borrower must be a member of this loan (loaded once, @TenantId-filtered).
        Map<UUID, BorrowerParty> loanBorrowers = new HashMap<>();
        for (BorrowerParty b : borrowers.findByLoanIdOrderByOrdinalAsc(loanId)) {
            loanBorrowers.put(b.getId(), b);
        }
        for (UUID borrowerId : req.borrowerIds()) {
            if (borrowerId == null || !loanBorrowers.containsKey(borrowerId)) {
                throw new ValidationException("unknown borrower " + borrowerId);
            }
        }
        if (req.action() == CreditOrderAction.REISSUE
                && (req.creditReportIdentifier() == null || req.creditReportIdentifier().isBlank())) {
            throw new ValidationException("creditReportIdentifier is required for REISSUE");
        }

        // Null bureau flags default to true — a tri-merge.
        boolean equifax = req.equifax() == null || req.equifax();
        boolean experian = req.experian() == null || req.experian();
        boolean transUnion = req.transUnion() == null || req.transUnion();

        String providerCode = resolveProviderCode(loanId);
        List<CreditBorrower> creditBorrowers = req.borrowerIds().stream()
                .map(loanBorrowers::get)
                .map(b -> new CreditBorrower(b.getId(), b.getFirstName(), b.getLastName()))
                .toList();

        CreditVendorResult result = creditVendor.order(new CreditVendorRequest(
                loanId, providerCode, req.action(), req.requestType(),
                equifax, experian, transUnion, creditBorrowers, req.creditReportIdentifier()));

        VendorArtifact report = result.report();
        Document reportDoc = documentService.storeGenerated(loanId, DocumentType.CREDIT_REPORT, "credit",
                "credit-report-" + result.creditReportIdentifier() + ".html",
                report.contentType(), report.bytes());

        CreditOrder order = new CreditOrder();
        order.setLoanId(loanId);
        order.setProviderCode(providerCode);
        order.setAction(req.action());
        order.setRequestType(req.requestType());
        order.setEquifax(equifax);
        order.setExperian(experian);
        order.setTransUnion(transUnion);
        order.setBorrowerIds(new ArrayList<>(req.borrowerIds()));
        order.setStatus(CreditOrderStatus.COMPLETE);
        order.setCreditReportIdentifier(result.creditReportIdentifier());
        order.setScores(result.scores());
        order.setReportDocumentId(reportDoc.getId());
        order.setRequestedBy(currentUser.id().orElse(null));
        order.setRequestedAt(Instant.now());
        return toResponse(orders.save(order));
    }

    @Transactional(readOnly = true)
    public List<CreditOrderResponse> history(UUID loanId) {
        guard(loanId);
        return orders.findByLoanIdOrderByRequestedAtDescIdDesc(loanId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Provider-code fallback chain: the loan's AUS profile DU settings, then the resolved CREDIT
     * vendor credentials, then null (the stub tolerates null).
     */
    private String resolveProviderCode(UUID loanId) {
        String fromProfile = profiles.findByLoanId(loanId)
                .map(p -> p.getDuSettings() == null ? null : p.getDuSettings().creditProviderCode())
                .orElse(null);
        if (fromProfile != null && !fromProfile.isBlank()) {
            return fromProfile;
        }
        return credentials.resolve(loanId, CredentialVendor.CREDIT).creditProviderCode();
    }

    private CreditOrderResponse toResponse(CreditOrder o) {
        return new CreditOrderResponse(o.getId(), o.getProviderCode(), o.getAction(), o.getRequestType(),
                o.isEquifax(), o.isExperian(), o.isTransUnion(), o.getBorrowerIds(), o.getStatus(),
                o.getCreditReportIdentifier(), o.getScores(), o.getReportDocumentId(),
                o.getRequestedBy(), o.getRequestedAt());
    }
}
