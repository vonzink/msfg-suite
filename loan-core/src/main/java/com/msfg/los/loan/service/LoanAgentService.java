package com.msfg.los.loan.service;

import com.msfg.los.loan.repo.LoanAgentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Query-side service for the loan-agent roster.
 *
 * <p>Both methods rely on {@link com.msfg.los.loan.repo.LoanAgentRepository} derived / JPQL
 * queries whose {@code @TenantId} filter is set by the Hibernate multi-tenancy filter already
 * active for the current request — callers must ensure the tenant context is bound before calling.
 *
 * <p>Intentionally write-free for T3 — agent roster writes happen via a dedicated endpoint
 * built in a later task.
 */
@Service
public class LoanAgentService {

    private final LoanAgentRepository agents;

    public LoanAgentService(LoanAgentRepository agents) {
        this.agents = agents;
    }

    /**
     * Returns {@code true} when {@code userId} (a Cognito sub) is listed as an agent on
     * {@code loanId} within the current tenant.  The underlying query is tenant-filtered via
     * {@code @TenantId}.
     */
    @Transactional(readOnly = true)
    public boolean isAgentOnLoan(UUID loanId, UUID userId) {
        return agents.existsByLoanIdAndUserId(loanId, userId);
    }

    /**
     * Returns the list of loan-ids the given user is linked to as an agent within the current
     * tenant. Used by {@code /me/loans} to scope the pipeline for an authenticated agent.
     */
    @Transactional(readOnly = true)
    public List<UUID> loanIdsForAgent(UUID userId) {
        return agents.findLoanIdsByUserId(userId);
    }
}
