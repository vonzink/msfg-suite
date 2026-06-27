package com.msfg.los.financials.service;

import com.msfg.los.financials.domain.Liability;
import com.msfg.los.financials.repo.LiabilityRepository;
import com.msfg.los.financials.web.dto.AddLiabilityRequest;
import com.msfg.los.financials.web.dto.UpdateLiabilityRequest;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.service.BorrowerService;
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
    private final BorrowerService borrowerService;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;

    public LiabilityService(LiabilityRepository liabilities, LoanService loanService,
                             LoanAccessGuard accessGuard, TenantContext tenantContext,
                             BorrowerService borrowerService) {
        this.liabilities = liabilities;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
        this.borrowerService = borrowerService;
    }

    private void assertBorrowerInLoan(UUID loanId, UUID borrowerId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        if (!borrowerService.isBorrowerInLoan(loanId, borrowerId))
            throw new NotFoundException("Borrower", borrowerId);
    }

    /**
     * Read gate (T11): staff/owning-LO OR the borrower reading their OWN row. Writes keep the
     * staff-only {@link #assertBorrowerInLoan}.
     */
    private void assertBorrowerSelfReadable(UUID loanId, UUID borrowerId) {
        accessGuard.assertBorrowerSelfReadable(loanService.get(loanId), borrowerId);
        if (!borrowerService.isBorrowerInLoan(loanId, borrowerId))
            throw new NotFoundException("Borrower", borrowerId);
    }

    /**
     * Apply and validate the EFFECTIVE (merged) DTI pairing state on the entity.
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
    private void applyAndValidateDtiPairing(Liability l) {
        if (!l.isIncludeInDti() && l.getExclusionReason() == null) {
            throw new ValidationException("exclusionReason is required when a liability is excluded from DTI");
        }
        if (l.isIncludeInDti() && l.getExclusionReason() != null) {
            l.setExclusionReason(null);
        }
    }

    // max+1, not count — count reuses ordinals after a delete and collides
    private int nextOrdinal(UUID borrowerId) {
        return liabilities.findTopByBorrowerIdOrderByOrdinalDesc(borrowerId)
                .map(x -> x.getOrdinal() + 1)
                .orElse(0);
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
        return insert(loanId, borrowerId, req);
    }

    /** Shared construction + validation + DTI-pairing + ordinal mechanics for {@link #add} and {@link #replaceForBorrowerInternal}. */
    private Liability insert(UUID loanId, UUID borrowerId, AddLiabilityRequest req) {
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
        if (req.exclusionReason() != null) l.setExclusionReason(req.exclusionReason());
        if (req.monthsRemaining() != null) l.setMonthsRemaining(req.monthsRemaining());
        l.setOrdinal(nextOrdinal(borrowerId));

        validateValues(l);
        applyAndValidateDtiPairing(l);

        return liabilities.save(l);
    }

    /**
     * No-guard self-service write seam (Stage-2 borrower-self application): REPLACE the borrower's
     * entire liability set with {@code items}. Deletes the borrower's existing rows (loaded via
     * {@code findByBorrowerIdOrderByOrdinalAscIdAsc} then {@code deleteAll} so org + RLS scoping
     * holds — never an unscoped bulk delete), then re-adds each item through the SAME {@link #insert}
     * mechanics the public {@link #add} uses (so {@link #validateValues} and
     * {@link #applyAndValidateDtiPairing} — the includeInDti/exclusionReason pairing rule — run per
     * row, and the max+1 ordinal logic re-derives 0..n-1 from the now-empty set; entity default
     * {@code includeInDti = true}).
     *
     * <p>No-guard; caller authorizes via {@link LoanAccessGuard#assertBorrowerSelfWritable}. The
     * {@code BorrowerApplicationService} orchestrator asserts that gate before invoking this. The
     * public {@link #add}/{@link #update}/{@link #delete} keep their staff {@link #assertBorrowerInLoan}.
     * Runs in one {@code @Transactional} method.
     */
    @Transactional
    public List<Liability> replaceForBorrowerInternal(UUID loanId, UUID borrowerId, List<AddLiabilityRequest> items) {
        List<Liability> existing = liabilities.findByBorrowerIdOrderByOrdinalAscIdAsc(borrowerId);
        liabilities.deleteAll(existing);
        liabilities.flush();
        List<Liability> created = new java.util.ArrayList<>();
        if (items != null) {
            for (AddLiabilityRequest req : items) {
                created.add(insert(loanId, borrowerId, req));
            }
        }
        return created;
    }

    @Transactional(readOnly = true)
    public List<Liability> list(UUID loanId, UUID borrowerId) {
        assertBorrowerSelfReadable(loanId, borrowerId);
        return liabilities.findByBorrowerIdOrderByOrdinalAscIdAsc(borrowerId);
    }

    @Transactional
    public Liability update(UUID loanId, UUID borrowerId, UUID liabilityId, UpdateLiabilityRequest req) {
        assertBorrowerInLoan(loanId, borrowerId);
        Liability l = liabilities.findByIdAndOrgId(liabilityId, tenantContext.requireOrgId())
                .filter(x -> x.getBorrowerId().equals(borrowerId))
                .orElseThrow(() -> new NotFoundException("Liability", liabilityId));

        // null-skip merge: apply simple fields first
        if (req.liabilityType() != null) l.setLiabilityType(req.liabilityType());
        if (req.creditorName() != null) l.setCreditorName(req.creditorName());
        if (req.accountNumber() != null) l.setAccountNumber(req.accountNumber());
        if (req.unpaidBalance() != null) l.setUnpaidBalance(req.unpaidBalance());
        if (req.monthlyPayment() != null) l.setMonthlyPayment(req.monthlyPayment());
        if (req.monthsRemaining() != null) l.setMonthsRemaining(req.monthsRemaining());

        if (req.includeInDti() != null) l.setIncludeInDti(req.includeInDti());
        if (req.exclusionReason() != null) l.setExclusionReason(req.exclusionReason());

        validateValues(l);
        applyAndValidateDtiPairing(l);

        return liabilities.save(l);
    }

    @Transactional
    public void delete(UUID loanId, UUID borrowerId, UUID liabilityId) {
        assertBorrowerInLoan(loanId, borrowerId);
        Liability l = liabilities.findByIdAndOrgId(liabilityId, tenantContext.requireOrgId())
                .filter(x -> x.getBorrowerId().equals(borrowerId))
                .orElseThrow(() -> new NotFoundException("Liability", liabilityId));
        liabilities.delete(l);
    }
}
