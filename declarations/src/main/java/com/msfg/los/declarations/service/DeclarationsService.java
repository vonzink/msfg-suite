package com.msfg.los.declarations.service;

import com.msfg.los.declarations.domain.BorrowerDeclarations;
import com.msfg.los.declarations.repo.BorrowerDeclarationsRepository;
import com.msfg.los.declarations.web.dto.DeclarationsRequest;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.service.BorrowerService;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.tenancy.TenantContext;
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
    private final TenantContext tenantContext;

    public DeclarationsService(BorrowerDeclarationsRepository repo,
                               BorrowerService borrowerService,
                               LoanService loanService,
                               LoanAccessGuard accessGuard,
                               TenantContext tenantContext) {
        this.repo = repo;
        this.borrowerService = borrowerService;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
    }

    private UUID org() {
        return tenantContext.orgId().orElseThrow(() -> new NotFoundException("Tenant", "current"));
    }

    private void assertBorrowerInLoan(UUID loanId, UUID borrowerId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        if (!borrowerService.isBorrowerInLoan(loanId, borrowerId))
            throw new NotFoundException("Borrower", borrowerId);
    }

    @Transactional(readOnly = true)
    public BorrowerDeclarations get(UUID loanId, UUID borrowerId) {
        assertBorrowerInLoan(loanId, borrowerId);
        return repo.findByBorrowerId(borrowerId).orElse(null);
    }

    @Transactional
    public BorrowerDeclarations upsert(UUID loanId, UUID borrowerId, DeclarationsRequest req) {
        assertBorrowerInLoan(loanId, borrowerId);
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
