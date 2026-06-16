package com.msfg.los.income.service;

import com.msfg.los.income.domain.Employment;
import com.msfg.los.income.domain.IncomeItem;
import com.msfg.los.income.repo.EmploymentRepository;
import com.msfg.los.income.repo.IncomeItemRepository;
import com.msfg.los.income.web.dto.IncomeSummaryResponse;
import com.msfg.los.income.web.dto.IncomeSummaryRow;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.domain.BorrowerParty;
import com.msfg.los.parties.service.BorrowerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class IncomeSummaryService {

    private final IncomeItemRepository income;
    private final EmploymentRepository employments;
    private final BorrowerService borrowerService;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;

    public IncomeSummaryService(IncomeItemRepository income, EmploymentRepository employments,
                                BorrowerService borrowerService, LoanService loanService,
                                LoanAccessGuard accessGuard) {
        this.income = income;
        this.employments = employments;
        this.borrowerService = borrowerService;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
    }

    @Transactional(readOnly = true)
    public IncomeSummaryResponse summarize(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));   // 404 cross-org, 403 not owner

        Map<UUID, String> borrowerNames = borrowerService.listByLoan(loanId).stream()
                .collect(Collectors.toMap(BorrowerParty::getId, IncomeSummaryService::fullName));

        Map<UUID, String> employerNames = new HashMap<>();
        employments.findByLoanIdOrderByOrdinalAsc(loanId).forEach(e ->
                employerNames.put(e.getId(),
                        e.getEmployerName() != null ? e.getEmployerName()
                                : (Boolean.TRUE.equals(e.getSelfEmployed()) ? "Self-Employed" : null)));

        List<IncomeItem> items = income.findByLoanIdOrderByOrdinalAsc(loanId);
        List<IncomeSummaryRow> rows = items.stream().map(i -> new IncomeSummaryRow(
                i.getBorrowerId(),
                borrowerNames.get(i.getBorrowerId()),
                i.getIncomeType(),
                i.getEmploymentId() == null ? null : employerNames.get(i.getEmploymentId()),
                i.getMonthlyAmount()
        )).toList();

        BigDecimal total = items.stream()
                .map(IncomeItem::getMonthlyAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new IncomeSummaryResponse(rows, total);
    }

    /**
     * Total monthly income for a loan — numeric only, no borrower-name map and no rows.
     *
     * <p><b>Unguarded</b>: callable only from an already loan-scoped + access-checked context
     * (e.g. {@code LoanCalculationService.calculate}, which guards once). The public
     * {@link #summarize} keeps its guard + rows for the GET summary endpoint. Result is the same
     * {@code totalMonthlyIncome} {@code summarize} returns (Σ non-null monthlyAmount; never null).
     */
    @Transactional(readOnly = true)
    public BigDecimal totalMonthlyIncomeForLoan(UUID loanId) {
        return income.sumMonthlyAmountByLoanId(loanId);
    }

    private static String fullName(BorrowerParty b) {
        String first = b.getFirstName() == null ? "" : b.getFirstName();
        String last = b.getLastName() == null ? "" : b.getLastName();
        return (first + " " + last).trim();
    }
}
