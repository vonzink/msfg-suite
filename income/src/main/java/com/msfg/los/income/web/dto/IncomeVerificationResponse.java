package com.msfg.los.income.web.dto;

import com.msfg.los.income.domain.IncomeVerification;
import com.msfg.los.income.domain.VerificationStatus;
import com.msfg.los.income.domain.VerificationType;

import java.time.Instant;
import java.util.UUID;

public record IncomeVerificationResponse(
        UUID id,
        UUID loanId,
        UUID borrowerId,
        VerificationType verificationType,
        VerificationStatus status,
        String provider,
        String referenceNumber,
        Instant orderedAt,
        Instant completedAt
) {
    public static IncomeVerificationResponse from(IncomeVerification v) {
        return new IncomeVerificationResponse(
                v.getId(),
                v.getLoanId(),
                v.getBorrowerId(),
                v.getVerificationType(),
                v.getStatus(),
                v.getProvider(),
                v.getReferenceNumber(),
                v.getOrderedAt(),
                v.getCompletedAt()
        );
    }
}
