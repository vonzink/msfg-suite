package com.msfg.los.pricing.port;

import com.msfg.los.loan.domain.LoanPurposeType;
import com.msfg.los.pricing.domain.CompensationPayerType;
import java.math.BigDecimal;

/** Inputs to a pricing quote. fico/ltv/loanPurpose may be null (no-data bucket). */
public record PricingQuoteRequest(
        BigDecimal rate,
        int commitmentDays,
        CompensationPayerType compensationPayerType,
        int extensionDaysTotal,
        Integer fico,
        BigDecimal ltv,
        LoanPurposeType loanPurpose,
        BigDecimal totalLoanAmount) {}
