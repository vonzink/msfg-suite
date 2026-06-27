package com.msfg.los.declarations.service;

import com.msfg.los.declarations.domain.BorrowerDeclarations;
import com.msfg.los.declarations.repo.BorrowerDeclarationsRepository;
import com.msfg.los.declarations.web.dto.DeclarationsRequest;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.service.BorrowerService;
import com.msfg.los.platform.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.UUID;

@Service
public class DeclarationsService {

    private final BorrowerDeclarationsRepository repo;
    private final BorrowerService borrowerService;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;

    public DeclarationsService(BorrowerDeclarationsRepository repo,
                               BorrowerService borrowerService,
                               LoanService loanService,
                               LoanAccessGuard accessGuard) {
        this.repo = repo;
        this.borrowerService = borrowerService;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
    }

    private void assertBorrowerInLoan(UUID loanId, UUID borrowerId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        if (!borrowerService.isBorrowerInLoan(loanId, borrowerId))
            throw new NotFoundException("Borrower", borrowerId);
    }

    /**
     * Read gate (T11): staff/owning-LO OR the borrower reading their OWN row. The PUT-upsert keeps
     * the staff-only {@link #assertBorrowerInLoan}.
     */
    private void assertBorrowerSelfReadable(UUID loanId, UUID borrowerId) {
        accessGuard.assertBorrowerSelfReadable(loanService.get(loanId), borrowerId);
        if (!borrowerService.isBorrowerInLoan(loanId, borrowerId))
            throw new NotFoundException("Borrower", borrowerId);
    }

    @Transactional(readOnly = true)
    public BorrowerDeclarations get(UUID loanId, UUID borrowerId) {
        assertBorrowerSelfReadable(loanId, borrowerId);
        return repo.findByBorrowerId(borrowerId).orElse(null);
    }

    @Transactional
    public BorrowerDeclarations upsert(UUID loanId, UUID borrowerId, DeclarationsRequest req) {
        assertBorrowerInLoan(loanId, borrowerId);
        return upsertInternal(loanId, borrowerId, req);
    }

    /**
     * No-guard PUT-upsert seam (Stage-2 borrower-self application): full-replace the borrower's
     * declarations row WITHOUT the staff {@link #assertBorrowerInLoan} gate. Authorization lives at
     * the caller — {@code BorrowerApplicationService} asserts
     * {@link LoanAccessGuard#assertBorrowerSelfWritable} before invoking this. Used ONLY by the
     * borrower-application orchestrator; the public {@link #upsert} keeps the staff gate. Replace
     * semantics (find-by-borrowerId-or-create, every field including nulls/empty sets) are identical
     * to {@code upsert} so behaviour is unchanged. Runs in one tenant-scoped ({@code @TenantId})
     * transaction.
     */
    @Transactional
    public BorrowerDeclarations upsertInternal(UUID loanId, UUID borrowerId, DeclarationsRequest req) {
        var e = repo.findByBorrowerId(borrowerId).orElseGet(() -> {
            var n = new BorrowerDeclarations();
            n.setLoanId(loanId);
            n.setBorrowerId(borrowerId);
            return n;
        });
        // Full replace — PUT semantics: set every field including nulls
        e.setOccupyAsPrimaryResidence(req.occupyAsPrimaryResidence());
        e.setHadOwnershipInterestLast3Years(req.hadOwnershipInterestLast3Years());
        e.setFamilyOrBusinessAffiliationWithSeller(req.familyOrBusinessAffiliationWithSeller());
        e.setBorrowingUndisclosedMoney(req.borrowingUndisclosedMoney());
        e.setApplyingForOtherMortgageOnProperty(req.applyingForOtherMortgageOnProperty());
        e.setApplyingForNewCreditBeforeClosing(req.applyingForNewCreditBeforeClosing());
        e.setSubjectToPriorityLienPace(req.subjectToPriorityLienPace());
        e.setCoSignerOrGuarantorOnUndisclosedDebt(req.coSignerOrGuarantorOnUndisclosedDebt());
        e.setOutstandingJudgments(req.outstandingJudgments());
        e.setDelinquentOrDefaultOnFederalDebt(req.delinquentOrDefaultOnFederalDebt());
        e.setPartyToLawsuit(req.partyToLawsuit());
        e.setConveyedTitleInLieuLast7Years(req.conveyedTitleInLieuLast7Years());
        e.setCompletedPreForeclosureShortSaleLast7Years(req.completedPreForeclosureShortSaleLast7Years());
        e.setPropertyForeclosedLast7Years(req.propertyForeclosedLast7Years());
        e.setDeclaredBankruptcyLast7Years(req.declaredBankruptcyLast7Years());
        e.setPriorPropertyUsage(req.priorPropertyUsage());
        e.setPriorPropertyTitleType(req.priorPropertyTitleType());
        e.setBankruptcyTypes(req.bankruptcyTypes() != null ? req.bankruptcyTypes() : new LinkedHashSet<>());
        return repo.save(e);
    }
}
