package com.msfg.los.income.service;

import com.msfg.los.income.domain.IncomeItem;
import com.msfg.los.income.domain.IncomeType;
import com.msfg.los.income.repo.EmploymentRepository;
import com.msfg.los.income.repo.IncomeItemRepository;
import com.msfg.los.income.web.dto.AddIncomeRequest;
import com.msfg.los.income.web.dto.UpdateIncomeRequest;
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
public class IncomeService {

    private final IncomeItemRepository income;
    private final EmploymentRepository employments;
    private final BorrowerRepository borrowers;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;

    public IncomeService(IncomeItemRepository income, EmploymentRepository employments,
                         BorrowerRepository borrowers, LoanService loanService,
                         LoanAccessGuard accessGuard, TenantContext tenantContext) {
        this.income = income;
        this.employments = employments;
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

    private void validate(UUID borrowerId, IncomeType type, BigDecimal amount, UUID employmentId) {
        if (type.isEmployment()) {
            if (employmentId == null)
                throw new ValidationException("employmentId is required for employment income (" + type + ")");
            employments.findByIdAndOrgId(employmentId, org())
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
        item.setOrdinal((int) income.countByBorrowerId(borrowerId));
        return income.save(item);
    }

    @Transactional(readOnly = true)
    public List<IncomeItem> list(UUID loanId, UUID borrowerId) {
        assertBorrowerInLoan(loanId, borrowerId);
        return income.findByBorrowerIdOrderByOrdinalAsc(borrowerId);
    }

    @Transactional
    public IncomeItem update(UUID loanId, UUID borrowerId, UUID incomeId, UpdateIncomeRequest req) {
        assertBorrowerInLoan(loanId, borrowerId);
        IncomeItem item = income.findByIdAndOrgId(incomeId, org())
                .filter(x -> x.getBorrowerId().equals(borrowerId))
                .orElseThrow(() -> new NotFoundException("Income", incomeId));

        // compute effective values (null-skip merge of req over existing entity values)
        IncomeType effectiveType = req.incomeType() != null ? req.incomeType() : item.getIncomeType();
        BigDecimal effectiveAmount = req.monthlyAmount() != null ? req.monthlyAmount() : item.getMonthlyAmount();
        UUID effectiveEmploymentId = req.employmentId() != null ? req.employmentId() : item.getEmploymentId();
        // clear paired field when switching to a non-employment type
        if (req.incomeType() != null && !req.incomeType().isEmployment()) effectiveEmploymentId = null;

        validate(borrowerId, effectiveType, effectiveAmount, effectiveEmploymentId);

        if (req.incomeType() != null) {
            item.setIncomeType(req.incomeType());
            if (!req.incomeType().isEmployment()) item.setEmploymentId(null);   // clear paired field on type switch
        }
        if (req.monthlyAmount() != null) item.setMonthlyAmount(req.monthlyAmount());
        if (req.employmentId() != null) item.setEmploymentId(req.employmentId());
        if (req.description() != null) item.setDescription(req.description());

        return item;
    }

    @Transactional
    public void delete(UUID loanId, UUID borrowerId, UUID incomeId) {
        assertBorrowerInLoan(loanId, borrowerId);
        IncomeItem item = income.findByIdAndOrgId(incomeId, org())
                .filter(x -> x.getBorrowerId().equals(borrowerId))
                .orElseThrow(() -> new NotFoundException("Income", incomeId));
        income.delete(item);
    }
}
