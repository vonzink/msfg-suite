package com.msfg.los.loan.web.dto;

import com.msfg.los.loan.domain.LoanStatus;
import com.msfg.los.loan.domain.LoanStatusHistory;

import java.time.Instant;
import java.util.UUID;

/**
 * One milestone in a loan's status timeline (the borrower/agent portal progress view + staff audit).
 * {@code status} is the status the loan moved TO; {@code transitionedAt} is the effective time
 * (backdateable, distinct from the audit createdAt); {@code changedBy} is the actor's sub.
 */
public record StatusHistoryItemResponse(
        UUID id,
        LoanStatus status,
        LoanStatus fromStatus,
        Instant transitionedAt,
        String reason,
        String changedBy) {

    public static StatusHistoryItemResponse from(LoanStatusHistory h) {
        return new StatusHistoryItemResponse(
                h.getId(),
                h.getToStatus(),
                h.getFromStatus(),
                h.getTransitionedAt(),
                h.getReason(),
                h.getCreatedBy());
    }
}
