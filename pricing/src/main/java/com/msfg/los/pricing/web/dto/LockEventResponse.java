package com.msfg.los.pricing.web.dto;

import com.msfg.los.pricing.domain.LockAction;
import com.msfg.los.pricing.domain.LockEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LockEventResponse(
        UUID id, LockAction action, String actor, Instant occurredAt,
        BigDecimal rate, Integer commitmentDays, LocalDate expirationDate) {

    public static LockEventResponse from(LockEvent e) {
        return new LockEventResponse(e.getId(), e.getAction(), e.getActor(), e.getOccurredAt(),
                e.getRate(), e.getCommitmentDays(), e.getExpirationDate());
    }
}
