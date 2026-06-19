package com.msfg.los.loan.web.dto;

import com.msfg.los.loan.domain.AgentRole;
import com.msfg.los.loan.domain.LoanAgent;

import java.util.UUID;

/**
 * Response DTO for a {@code loan_agent} roster row (T8).
 */
public record LoanAgentResponse(
        UUID id,
        UUID userId,
        AgentRole agentRole,
        int ordinal) {

    public static LoanAgentResponse from(LoanAgent a) {
        return new LoanAgentResponse(
                a.getId(),
                a.getUserId(),
                a.getAgentRole(),
                a.getOrdinal());
    }
}
