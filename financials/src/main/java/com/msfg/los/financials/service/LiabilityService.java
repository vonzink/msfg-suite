package com.msfg.los.financials.service;

import com.msfg.los.financials.domain.Liability;
import com.msfg.los.financials.repo.LiabilityRepository;
import com.msfg.los.financials.web.dto.AddLiabilityRequest;
import com.msfg.los.financials.web.dto.UpdateLiabilityRequest;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.repo.BorrowerRepository;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class LiabilityService {

    private final LiabilityRepository liabilities;
    private final BorrowerRepository borrowers;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;

    public LiabilityService(LiabilityRepository liabilities, LoanService loanService,
                             LoanAccessGuard accessGuard, TenantContext tenantContext,
                             BorrowerRepository borrowers) {
        this.liabilities = liabilities;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
        this.borrowers = borrowers;
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

    /**
     * Validate the EFFECTIVE (merged) DTI pairing state and apply it to the entity.
     * Called after null-skip field merges so the entity already holds the effective values.
     *
     * Pairing rules:
     *   excluded (includeInDti==false) + no exclusionReason → 400
     *   included (includeInDti==true)  + exclusionReason present → CLEAR it (recoverable, not a 400)
     *
     * Value rules (each its own if/throw — do NOT collapse):
     *   monthlyPayment  < 0 → 400
     *   unpaidBalance   < 0 → 400
     *   monthsRemaining < 0 → 400
     */
    private void validateDtiPairing(Liability l) {
        if (!l.isIncludeInDti() && l.getExclusionReason() == null) {
            throw new ValidationException("exclusionReason is required when a liability is excluded from DTI");
        }
        if (l.isIncludeInDti() && l.getExclusionReason() != null) {
            l.setExclusionReason(null);
        }
    }

    private void validateValues(Liability l) {
        if (l.getMonthlyPayment() != null && l.getMonthlyPayment().signum() < 0) {
            throw new ValidationException("monthlyPayment must be >= 0");
        }
        if (l.getUnpaidBalance() != null && l.getUnpaidBalance().signum() < 0) {
            throw new ValidationException("unpaidBalance must be >= 0");
        }
        if (l.getMonthsRemaining() != null && l.getMonthsRemaining() < 0) {
            throw new ValidationException("monthsRemaining must be >= 0");
        }
    }

    @Transactional
    public Liability add(UUID loanId, UUID borrowerId, AddLiabilityRequest req) {
        assertBorrowerInLoan(loanId, borrowerId);

        Liability l = new Liability();
        l.setLoanId(loanId);
        l.setBorrowerId(borrowerId);
        l.setLiabilityType(req.liabilityType());
        if (req.creditorName() != null) l.setCreditorName(req.creditorName());
        if (req.accountNumber() != null) l.setAccountNumber(req.accountNumber());
        if (req.unpaidBalance() != null) l.setUnpaidBalance(req.unpaidBalance());
        if (req.monthlyPayment() != null) l.setMonthlyPayment(req.monthlyPayment());
        // entity default: includeInDti = true; override only if explicitly set
        if (req.includeInDti() != null) l.setIncludeInDti(req.includeInDti());
        // apply explicit includeInDti=true clears exclusionReason before it is set
        if (l.isIncludeInDti() && req.exclusionReason() != null) {
            // will be cleared by validateDtiPairing; set it so validate sees it and clears
            l.setExclusionReason(req.exclusionReason());
        } else if (req.exclusionReason() != null) {
            l.setExclusionReason(req.exclusionReason());
        }
        if (req.monthsRemaining() != null) l.setMonthsRemaining(req.monthsRemaining());
        l.setOrdinal((int) liabilities.countByBorrowerId(borrowerId));

        validateValues(l);
        validateDtiPairing(l);

        return liabilities.save(l);
    }

    @Transactional(readOnly = true)
    public List<Liability> list(UUID loanId, UUID borrowerId) {
        assertBorrowerInLoan(loanId, borrowerId);
        return liabilities.findByBorrowerIdOrderByOrdinalAsc(borrowerId);
    }

    @Transactional
    public Liability update(UUID loanId, UUID borrowerId, UUID liabilityId, UpdateLiabilityRequest req) {
        assertBorrowerInLoan(loanId, borrowerId);
        Liability l = liabilities.findByIdAndOrgId(liabilityId, org())
                .filter(x -> x.getBorrowerId().equals(borrowerId))
                .orElseThrow(() -> new NotFoundException("Liability", liabilityId));

        // null-skip merge: apply simple fields first
        if (req.liabilityType() != null) l.setLiabilityType(req.liabilityType());
        if (req.creditorName() != null) l.setCreditorName(req.creditorName());
        if (req.accountNumber() != null) l.setAccountNumber(req.accountNumber());
        if (req.unpaidBalance() != null) l.setUnpaidBalance(req.unpaidBalance());
        if (req.monthlyPayment() != null) l.setMonthlyPayment(req.monthlyPayment());
        if (req.monthsRemaining() != null) l.setMonthsRemaining(req.monthsRemaining());

        // DTI paired-field: apply includeInDti first; if it becomes true, clear exclusionReason
        if (req.includeInDti() != null) {
            l.setIncludeInDti(req.includeInDti());
            if (l.isIncludeInDti()) {
                l.setExclusionReason(null);
            }
        }
        // then apply exclusionReason (after potential clear above)
        if (req.exclusionReason() != null) l.setExclusionReason(req.exclusionReason());

        validateValues(l);
        validateDtiPairing(l);

        return l;
    }

    @Transactional
    public void delete(UUID loanId, UUID borrowerId, UUID liabilityId) {
        assertBorrowerInLoan(loanId, borrowerId);
        Liability l = liabilities.findByIdAndOrgId(liabilityId, org())
                .filter(x -> x.getBorrowerId().equals(borrowerId))
                .orElseThrow(() -> new NotFoundException("Liability", liabilityId));
        liabilities.delete(l);
    }
}
