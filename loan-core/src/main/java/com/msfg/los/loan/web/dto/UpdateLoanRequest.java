package com.msfg.los.loan.web.dto;

import com.msfg.los.loan.domain.*;
import java.math.BigDecimal;

public record UpdateLoanRequest(
    MortgageType mortgageType,
    LienPriorityType lienPriority,
    AmortizationType amortizationType,
    BigDecimal noteAmount,
    String addressLine1,
    String addressLine2,
    String city,
    String state,
    String postalCode,
    BigDecimal estimatedValue) {}
