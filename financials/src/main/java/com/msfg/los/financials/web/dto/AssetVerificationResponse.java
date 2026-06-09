package com.msfg.los.financials.web.dto;

import com.msfg.los.financials.domain.AssetVerification;
import com.msfg.los.financials.domain.AssetVerificationType;
import com.msfg.los.financials.domain.AssetVerificationStatus;

import java.time.Instant;
import java.util.UUID;

public record AssetVerificationResponse(
        UUID id,
        UUID loanId,
        UUID borrowerId,
        AssetVerificationType verificationType,
        AssetVerificationStatus status,
        String provider,
        String referenceNumber,
        Instant orderedAt,
        Instant completedAt
) {
    public static AssetVerificationResponse from(AssetVerification v) {
        return new AssetVerificationResponse(
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
