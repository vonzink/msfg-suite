package com.msfg.los.financials.web.dto;

import com.msfg.los.financials.domain.DtiExclusionReason;
import com.msfg.los.financials.domain.LiabilityType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AddLiabilityRequest(
        @NotNull LiabilityType liabilityType,
        String creditorName,
        String accountNumber,
        BigDecimal unpaidBalance,
        BigDecimal monthlyPayment,
        Boolean includeInDti,
        DtiExclusionReason exclusionReason,
        Integer monthsRemaining) {}
