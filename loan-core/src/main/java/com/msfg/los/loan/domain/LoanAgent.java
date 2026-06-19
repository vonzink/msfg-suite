package com.msfg.los.loan.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Loan-scoped real-estate agent roster row (V24 {@code loan_agent}).
 *
 * <p>{@code org_id} is stamped automatically via the inherited {@code @TenantId} on
 * {@link TenantScopedEntity}; {@code id} and {@code version} come from {@code BaseEntity};
 * audit columns come from {@code AuditableEntity}.
 *
 * <p>{@code user_id} is a Cognito sub (cross-service identity). No FK — identity lives in another
 * service. The unique constraint {@code (org_id, loan_id, user_id)} is enforced by the DB.
 */
@Entity
@Table(name = "loan_agent")
public class LoanAgent extends TenantScopedEntity {

    @Column(nullable = false)
    private UUID loanId;

    /** The user's Cognito sub — not a FK; identity service owns the record. */
    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentRole agentRole = AgentRole.BUYERS_AGENT;

    @Column(nullable = false)
    private int ordinal;

    public UUID getLoanId() { return loanId; }
    public void setLoanId(UUID loanId) { this.loanId = loanId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public AgentRole getAgentRole() { return agentRole; }
    public void setAgentRole(AgentRole agentRole) { this.agentRole = agentRole; }

    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
}
