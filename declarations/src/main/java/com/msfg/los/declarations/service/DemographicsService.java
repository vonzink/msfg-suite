package com.msfg.los.declarations.service;

import com.msfg.los.declarations.domain.BorrowerDemographics;
import com.msfg.los.declarations.repo.BorrowerDemographicsRepository;
import com.msfg.los.declarations.web.dto.DemographicsRequest;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.repo.BorrowerRepository;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.UUID;

@Service
public class DemographicsService {

    private final BorrowerDemographicsRepository repo;
    private final BorrowerRepository borrowers;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;

    public DemographicsService(BorrowerDemographicsRepository repo,
                               BorrowerRepository borrowers,
                               LoanService loanService,
                               LoanAccessGuard accessGuard,
                               TenantContext tenantContext) {
        this.repo = repo;
        this.borrowers = borrowers;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
    }

    private UUID org() {
        return tenantContext.orgId().orElseThrow(() -> new NotFoundException("Tenant", "current"));
    }

    private void assertBorrowerInLoan(UUID loanId, UUID borrowerId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        borrowers.findByIdAndOrgId(borrowerId, org())
                .filter(b -> b.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("Borrower", borrowerId));
    }

    @Transactional(readOnly = true)
    public BorrowerDemographics get(UUID loanId, UUID borrowerId) {
        assertBorrowerInLoan(loanId, borrowerId);
        return repo.findByBorrowerId(borrowerId).orElse(null);
    }

    @Transactional
    public BorrowerDemographics upsert(UUID loanId, UUID borrowerId, DemographicsRequest req) {
        assertBorrowerInLoan(loanId, borrowerId);
        var e = repo.findByBorrowerId(borrowerId).orElseGet(() -> {
            var n = new BorrowerDemographics();
            n.setLoanId(loanId);
            n.setBorrowerId(borrowerId);
            return n;
        });
        // Full replace — PUT semantics: set every field including nulls, replace sets entirely
        e.setEthnicity(req.ethnicity() != null ? req.ethnicity() : new LinkedHashSet<>());
        e.setRace(req.race() != null ? req.race() : new LinkedHashSet<>());
        e.setSex(req.sex());
        e.setCollectedByVisualObservationOrSurname(req.collectedByVisualObservationOrSurname());
        e.setApplicationTakenMethod(req.applicationTakenMethod());
        return repo.save(e);
    }
}
