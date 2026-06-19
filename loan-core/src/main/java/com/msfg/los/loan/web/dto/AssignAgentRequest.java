package com.msfg.los.loan.web.dto;

import com.msfg.los.loan.domain.AgentRole;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for {@code POST /api/loans/{loanId}/agents} (T8).
 */
public record AssignAgentRequest(
        @NotNull UUID userId,
        @NotNull AgentRole agentRole) {
}
