package com.msfg.los.income.service;

import com.msfg.los.income.domain.IncomeItem;
import com.msfg.los.income.domain.IncomeType;
import com.msfg.los.income.repo.EmploymentRepository;
import com.msfg.los.income.repo.IncomeItemRepository;
import com.msfg.los.income.web.dto.AddIncomeRequest;
import com.msfg.los.income.web.dto.UpdateIncomeRequest;
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
public class IncomeService {

    private final IncomeItemRepository income;
    private final EmploymentRepository employments;
    private final BorrowerService borrowerService;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;

    public IncomeService(IncomeItemRepository income, EmploymentRepository employments,
                         BorrowerService borrowerService, LoanService loanService,
                         LoanAccessGuard accessGuard, TenantContext tenantContext) {
        this.income = income;
        this.employments = employments;
        this.borrowerService = borrowerService;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
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

    // max+1, not count — count reuses ordinals after a delete and collides
    private int nextOrdinal(UUID borrowerId) {
        return income.findTopByBorrowerIdOrderByOrdinalDesc(borrowerId)
                .map(x -> x.getOrdinal() + 1)
                .orElse(0);
    }

    private void validate(UUID borrowerId, IncomeType type, BigDecimal amount, UUID employmentId) {
        if (type.isEmployment()) {
            if (employmentId == null)
                throw new ValidationException("employmentId is required for employment income (" + type + ")");
            employments.findByIdAndOrgId(employmentId, tenantContext.requireOrgId())
                    .filter(e -> e.getBorrowerId().equals(borrowerId))
                    .orElseThrow(() -> new ValidationException(
                            "employmentId must reference an employment of this borrower"));
        } else if (employmentId != null) {
            throw new ValidationException("employmentId must be null for non-employment income (" + type + ")");
        }
        if (amount != null && !type.allowsNegative() && amount.signum() < 0)
            throw new ValidationException("monthlyAmount must be >= 0 for " + type);
    }

    @Transactional
    public IncomeItem add(UUID loanId, UUID borrowerId, AddIncomeRequest req) {
        assertBorrowerInLoan(loanId, borrowerId);
        validate(borrowerId, req.incomeType(), req.monthlyAmount(), req.employmentId());
        IncomeItem item = new IncomeItem();
        item.setLoanId(loanId);
        item.setBorrowerId(borrowerId);
        item.setIncomeType(req.incomeType());
        item.setMonthlyAmount(req.monthlyAmount());
        item.setEmploymentId(req.employmentId());
        item.setDescription(req.description());
        item.setOrdinal(nextOrdinal(borrowerId));
        return income.save(item);
    }

    /**
     * No-guard internal write seam (Stage-2 borrower-self application): insert an IncomeItem row WITHOUT
     * the staff {@link #assertBorrowerInLoan} loan gate. Authorization lives at the caller —
     * {@code BorrowerApplicationService} asserts {@link LoanAccessGuard#assertBorrowerSelfWritable}
     * (caller is staff/owning-LO, or a BORROWER editing their OWN row) before invoking this. Used ONLY by
     * the borrower-application orchestrator; the public {@link #add} keeps the staff gate. Reuses the same
     * {@link #validate} + {@link #nextOrdinal} helpers as {@code add}, so the employment-type↔employmentId
     * FK rule and the negative-amount rule (only SELF_EMPLOYMENT_INCOME) are enforced identically. For
     * employment income the caller passes the new employmentId from a just-inserted {@link #addInternal}
     * Employment of THIS borrower; for other income employmentId must be null.
     */
    @Transactional
    public IncomeItem addInternal(UUID loanId, UUID borrowerId, AddIncomeRequest req) {
        validate(borrowerId, req.incomeType(), req.monthlyAmount(), req.employmentId());
        IncomeItem item = new IncomeItem();
        item.setLoanId(loanId);
        item.setBorrowerId(borrowerId);
        item.setIncomeType(req.incomeType());
        item.setMonthlyAmount(req.monthlyAmount());
        item.setEmploymentId(req.employmentId());
        item.setDescription(req.description());
        item.setOrdinal(nextOrdinal(borrowerId));
        return income.save(item);
    }

    /**
     * No-guard internal delete seam (Stage-2 borrower-self application, full-replace): delete ALL of a
     * borrower's IncomeItem rows WITHOUT the staff {@link #assertBorrowerInLoan} loan gate. Authorization
     * lives at the caller — {@code BorrowerApplicationService} asserts
     * {@link LoanAccessGuard#assertBorrowerSelfWritable} before invoking. Loads the rows
     * ({@code @TenantId}-filtered) then {@code deleteAll}, so org + RLS scoping holds (NOT an unscoped
     * bulk delete). Must run BEFORE {@code EmploymentService.deleteAllForBorrowerInternal} (FK order).
     */
    @Transactional
    public void deleteAllForBorrowerInternal(UUID loanId, UUID borrowerId) {
        List<IncomeItem> rows = income.findByBorrowerIdOrderByOrdinalAscIdAsc(borrowerId);
        income.deleteAll(rows);
    }

    @Transactional(readOnly = true)
    public List<IncomeItem> list(UUID loanId, UUID borrowerId) {
        assertBorrowerSelfReadable(loanId, borrowerId);
        return income.findByBorrowerIdOrderByOrdinalAscIdAsc(borrowerId);
    }

    @Transactional
    public IncomeItem update(UUID loanId, UUID borrowerId, UUID incomeId, UpdateIncomeRequest req) {
        assertBorrowerInLoan(loanId, borrowerId);
        IncomeItem item = income.findByIdAndOrgId(incomeId, tenantContext.requireOrgId())
                .filter(x -> x.getBorrowerId().equals(borrowerId))
                .orElseThrow(() -> new NotFoundException("Income", incomeId));

        // compute effective values (null-skip merge of req over existing entity values)
        IncomeType effectiveType = req.incomeType() != null ? req.incomeType() : item.getIncomeType();
        BigDecimal effectiveAmount = req.monthlyAmount() != null ? req.monthlyAmount() : item.getMonthlyAmount();
        UUID effectiveEmploymentId = req.employmentId() != null ? req.employmentId() : item.getEmploymentId();
        // clear paired field when switching to a non-employment type
        if (req.incomeType() != null && !req.incomeType().isEmployment()) effectiveEmploymentId = null;

        validate(borrowerId, effectiveType, effectiveAmount, effectiveEmploymentId);

        if (req.incomeType() != null) item.setIncomeType(req.incomeType());
        if (req.monthlyAmount() != null) item.setMonthlyAmount(req.monthlyAmount());
        item.setEmploymentId(effectiveEmploymentId);   // authoritative: already merged + null-cleared for type switches
        if (req.description() != null) item.setDescription(req.description());

        return income.save(item);
    }

    @Transactional
    public void delete(UUID loanId, UUID borrowerId, UUID incomeId) {
        assertBorrowerInLoan(loanId, borrowerId);
        IncomeItem item = income.findByIdAndOrgId(incomeId, tenantContext.requireOrgId())
                .filter(x -> x.getBorrowerId().equals(borrowerId))
                .orElseThrow(() -> new NotFoundException("Income", incomeId));
        income.delete(item);
    }
}
