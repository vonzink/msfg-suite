package com.msfg.los.dashboard.web.dto;

import com.msfg.los.loan.domain.AmortizationType;
import com.msfg.los.loan.domain.LienPriorityType;

import java.math.BigDecimal;

/**
 * PATCH body for {@code /api/loans/{loanId}/dashboard/terms}. All fields nullable (patch semantics:
 * a null field is left unchanged). {@code interestRate} is range-validated in the service (>=0, &lt;100)
 * rather than via bean validation so the message matches the codebase's {@code ValidationException}
 * 400 envelope.
 */
public record DashboardTermsPatch(
        BigDecimal baseLoanAmount,
        BigDecimal noteAmount,
        BigDecimal interestRate,
        AmortizationType amortizationType,
        Integer loanTermMonths,
        LienPriorityType lienPriority,
        BigDecimal downPaymentAmount) {}
