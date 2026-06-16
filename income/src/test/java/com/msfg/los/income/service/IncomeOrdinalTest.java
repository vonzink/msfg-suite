package com.msfg.los.income.service;

import com.msfg.los.income.domain.Employment;
import com.msfg.los.income.domain.IncomeItem;
import com.msfg.los.income.domain.IncomeType;
import com.msfg.los.income.repo.EmploymentRepository;
import com.msfg.los.income.repo.IncomeItemRepository;
import com.msfg.los.income.web.dto.AddEmploymentRequest;
import com.msfg.los.income.web.dto.AddIncomeRequest;
import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.service.BorrowerService;
import com.msfg.los.platform.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Regression for the count-vs-max+1 ordinal bug (finding C1): after a middle row is
 * deleted, the highest existing ordinal exceeds the row count, so a count-based
 * assignment REUSES an ordinal and collides. These tests stub the top-ordinal query
 * to return an existing ordinal of 2 — max+1 must yield 3, which count-based code
 * (which never consults the top-ordinal query) cannot produce.
 */
@ExtendWith(MockitoExtension.class)
class IncomeOrdinalTest {

    private static final UUID LOAN_ID = UUID.randomUUID();
    private static final UUID BORROWER_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();

    @Mock IncomeItemRepository income;
    @Mock EmploymentRepository employments;
    @Mock BorrowerService borrowerService;
    @Mock LoanService loanService;
    @Mock LoanAccessGuard accessGuard;
    @Mock TenantContext tenantContext;

    private void stubBorrowerInLoan() {
        when(loanService.get(LOAN_ID)).thenReturn(new Loan());
        when(borrowerService.isBorrowerInLoan(LOAN_ID, BORROWER_ID)).thenReturn(true);
    }

    @Test
    void incomeAddUsesMaxPlusOneNotCount() {
        IncomeService svc = new IncomeService(income, employments, borrowerService, loanService, accessGuard, tenantContext);
        stubBorrowerInLoan();

        IncomeItem existingTop = new IncomeItem();
        existingTop.setOrdinal(2);
        when(income.findTopByBorrowerIdOrderByOrdinalDesc(BORROWER_ID)).thenReturn(Optional.of(existingTop));
        when(income.save(any(IncomeItem.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.add(LOAN_ID, BORROWER_ID,
                new AddIncomeRequest(IncomeType.OTHER, BigDecimal.TEN, null, "x"));

        ArgumentCaptor<IncomeItem> saved = ArgumentCaptor.forClass(IncomeItem.class);
        org.mockito.Mockito.verify(income).save(saved.capture());
        assertThat(saved.getValue().getOrdinal()).isEqualTo(3);
    }

    @Test
    void incomeAddFirstRowOrdinalZero() {
        IncomeService svc = new IncomeService(income, employments, borrowerService, loanService, accessGuard, tenantContext);
        stubBorrowerInLoan();

        when(income.findTopByBorrowerIdOrderByOrdinalDesc(BORROWER_ID)).thenReturn(Optional.empty());
        when(income.save(any(IncomeItem.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.add(LOAN_ID, BORROWER_ID,
                new AddIncomeRequest(IncomeType.OTHER, BigDecimal.TEN, null, "x"));

        ArgumentCaptor<IncomeItem> saved = ArgumentCaptor.forClass(IncomeItem.class);
        org.mockito.Mockito.verify(income).save(saved.capture());
        assertThat(saved.getValue().getOrdinal()).isEqualTo(0);
    }

    @Test
    void employmentAddUsesMaxPlusOneNotCount() {
        EmploymentService svc = new EmploymentService(employments, borrowerService, loanService, accessGuard, tenantContext);
        stubBorrowerInLoan();

        Employment existingTop = new Employment();
        existingTop.setOrdinal(2);
        when(employments.findTopByBorrowerIdOrderByOrdinalDesc(BORROWER_ID)).thenReturn(Optional.of(existingTop));
        when(employments.save(any(Employment.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.add(LOAN_ID, BORROWER_ID,
                new AddEmploymentRequest("Acme", null, null, null, null, null, null, null,
                        null, null, false, null, null, null, null, null));

        ArgumentCaptor<Employment> saved = ArgumentCaptor.forClass(Employment.class);
        org.mockito.Mockito.verify(employments).save(saved.capture());
        assertThat(saved.getValue().getOrdinal()).isEqualTo(3);
    }
}
