package com.msfg.los.loan.service;

import com.msfg.los.loan.domain.AgentRole;
import com.msfg.los.loan.domain.LoanAgent;
import com.msfg.los.loan.repo.LoanAgentRepository;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Loan-agent roster service — read + write (data layer only).
 *
 * <p>Access control is <em>not</em> enforced here to avoid a circular dependency with
 * {@link LoanAccessGuard} (which injects this service for {@code assertReadable}). Callers
 * (the {@code LoanAgentController}) must call {@link LoanAccessGuard#assertCanAccess} /
 * {@link LoanAccessGuard#assertCanModify} on the loan <em>before</em> invoking write/list methods.
 * <p>⚠️ This <em>deliberately diverges</em> from contacts/conditions/notes, which call the guard
 * <em>inside</em> the service — those services have no back-edge to {@link LoanAccessGuard}, so they
 * have no cycle. This service does (the guard depends on it for {@code assertReadable}), so the guard
 * call must live in the controller instead.
 *
 * <p>All repository queries use field-based predicates so Hibernate's {@code @TenantId} filter
 * fires automatically — callers must ensure the tenant context is bound for the current request.
 */
@Service
public class LoanAgentService {

    private final LoanAgentRepository agents;
    private final LoanService loanService;
    private final TenantContext tenantContext;

    public LoanAgentService(LoanAgentRepository agents,
                            LoanService loanService,
                            TenantContext tenantContext) {
        this.agents = agents;
        this.loanService = loanService;
        this.tenantContext = tenantContext;
    }

    // ── read-side (T3) ───────────────────────────────────────────────────────

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

    // ── write-side (T8) — caller must have already called assertCanModify/assertCanAccess ────────

    /**
     * Assigns a user as an agent on a loan.
     *
     * <p>Caller is responsible for asserting access via {@link LoanAccessGuard#assertCanModify}.
     * Ordinal = max+1 (mirrors the contacts pattern — count reuses ordinals after a delete; max+1
     * never does). Duplicate {@code (org_id, loan_id, user_id)} → DB unique constraint fires →
     * global {@code DataIntegrityViolationException→409} handler.
     *
     * <p>The loan existence / tenant check is performed here via {@link LoanService#get(UUID)}.
     *
     * @throws com.msfg.los.platform.error.NotFoundException if the loan does not exist or belongs
     *         to a different tenant.
     */
    @Transactional
    public LoanAgent assign(UUID loanId, UUID userId, AgentRole role) {
        // Existence + tenant check (throws 404 on unknown/cross-tenant loan).
        loanService.get(loanId);

        LoanAgent a = new LoanAgent();
        a.setLoanId(loanId);
        a.setUserId(userId);
        a.setAgentRole(role);
        a.setOrdinal(nextOrdinal(loanId));
        return agents.save(a);
    }

    /**
     * Lists the agent roster for a loan, ordered by ordinal then id (stable).
     *
     * <p>Caller is responsible for asserting access via {@link LoanAccessGuard#assertCanAccess}.
     * The loan existence / tenant check is performed here via {@link LoanService#get(UUID)}.
     *
     * @throws com.msfg.los.platform.error.NotFoundException if the loan does not exist or belongs
     *         to a different tenant.
     */
    @Transactional(readOnly = true)
    public List<LoanAgent> list(UUID loanId) {
        loanService.get(loanId);
        return agents.findByLoanIdOrderByOrdinalAscIdAsc(loanId);
    }

    /**
     * Unassigns an agent from a loan (204 — idempotent on 404).
     *
     * <p>Caller is responsible for asserting access via {@link LoanAccessGuard#assertCanModify}.
     * Uses {@code findByIdAndOrgId} (tenant-safe load by PK) then checks the loan cross-reference
     * before deleting — mirrors the contacts {@code load} pattern.
     *
     * @throws com.msfg.los.platform.error.NotFoundException if the row does not exist, belongs to a
     *         different tenant, or belongs to a different loan.
     */
    @Transactional
    public void remove(UUID loanId, UUID agentId) {
        loanService.get(loanId);
        LoanAgent a = agents.findByIdAndOrgId(agentId, tenantContext.requireOrgId())
                .filter(r -> r.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("LoanAgent", agentId));
        agents.delete(a);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** max+1 — count reuses ordinals after a delete; max+1 never does. */
    private int nextOrdinal(UUID loanId) {
        return agents.findTopByLoanIdOrderByOrdinalDesc(loanId)
                .map(a -> a.getOrdinal() + 1)
                .orElse(0);
    }
}
