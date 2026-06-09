package com.msfg.los.financials.web.dto;

import com.msfg.los.financials.domain.DtiExclusionReason;
import com.msfg.los.financials.domain.Liability;
import com.msfg.los.financials.domain.LiabilityType;

import java.math.BigDecimal;
import java.util.UUID;

public record LiabilityResponse(
        UUID id,
        UUID borrowerId,
        LiabilityType liabilityType,
        int ordinal,
        String creditorName,
        String accountNumber,
        BigDecimal unpaidBalance,
        BigDecimal monthlyPayment,
        boolean includeInDti,
        DtiExclusionReason exclusionReason,
        Integer monthsRemaining) {

    public static LiabilityResponse from(Liability liability) {
        return new LiabilityResponse(
                liability.getId(),
                liability.getBorrowerId(),
                liability.getLiabilityType(),
                liability.getOrdinal(),
                liability.getCreditorName(),
                liability.getAccountNumber(),
                liability.getUnpaidBalance(),
                liability.getMonthlyPayment(),
                liability.isIncludeInDti(),
                liability.getExclusionReason(),
                liability.getMonthsRemaining());
    }
}
