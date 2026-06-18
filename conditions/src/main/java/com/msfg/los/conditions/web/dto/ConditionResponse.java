package com.msfg.los.conditions.web.dto;

import com.msfg.los.conditions.domain.ConditionStatus;
import com.msfg.los.conditions.domain.ConditionType;
import com.msfg.los.conditions.domain.LoanCondition;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ConditionResponse(
        UUID id,
        UUID loanId,
        String conditionText,
        ConditionType conditionType,
        ConditionStatus status,
        String assignedTo,
        LocalDate dueDate,
        Instant clearedAt,
        String clearedBy,
        String notes,
        Instant createdAt,
        String createdBy,
        Instant updatedAt) {

    public static ConditionResponse from(LoanCondition c) {
        return new ConditionResponse(
                c.getId(),
                c.getLoanId(),
                c.getConditionText(),
                c.getConditionType(),
                c.getStatus(),
                c.getAssignedTo(),
                c.getDueDate(),
                c.getClearedAt(),
                c.getClearedBy(),
                c.getNotes(),
                c.getCreatedAt(),
                c.getCreatedBy(),
                c.getUpdatedAt());
    }
}
