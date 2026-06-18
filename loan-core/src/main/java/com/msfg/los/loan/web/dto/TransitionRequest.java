package com.msfg.los.loan.web.dto;

import com.msfg.los.loan.domain.LoanStatus;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Status-transition request. {@code transitionedAt} is optional (Phase 2 T3): when present the
 * status-history row and the loan's mirror {@code statusChangedAt} are recorded with that effective
 * time (backdating); when null both default to {@code now()} — fully backward compatible.
 */
public record TransitionRequest(@NotNull LoanStatus targetStatus, String reason, Instant transitionedAt) {}
