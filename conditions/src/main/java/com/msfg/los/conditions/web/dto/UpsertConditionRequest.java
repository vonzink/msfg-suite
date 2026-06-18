package com.msfg.los.conditions.web.dto;

import com.msfg.los.conditions.domain.ConditionStatus;
import com.msfg.los.conditions.domain.ConditionType;

import java.time.LocalDate;

/**
 * Create / patch body for a loan condition. Used by both POST (create) and PATCH (partial update).
 *
 * <p>All fields nullable so PATCH carries true patch-semantics (only non-null fields apply). The
 * service enforces the create-time rule that {@code conditionText} must be non-blank, and the
 * PATCH-time rule that a provided-but-blank {@code conditionText} is rejected (400).
 */
public record UpsertConditionRequest(
        String conditionText,
        ConditionType conditionType,
        ConditionStatus status,
        String assignedTo,
        LocalDate dueDate,
        String notes) {}
