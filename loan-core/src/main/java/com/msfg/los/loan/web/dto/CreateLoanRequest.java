package com.msfg.los.loan.web.dto;

import com.msfg.los.loan.domain.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateLoanRequest(
    @NotNull LoanPurposeType loanPurpose,
    MortgageType mortgageType,
    LienPriorityType lienPriority,
    AmortizationType amortizationType,
    BigDecimal noteAmount,
    UUID loanOfficerId) {}
