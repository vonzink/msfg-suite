package com.msfg.los.loan.service;

import java.util.Set;
import java.util.UUID;

/**
 * Cross-module seam (conditions → loan-core) for the pipeline {@code conditionsGt} filter (Phase 2 T4).
 *
 * <p>Implemented by the conditions module so loan-core never reaches the {@code loan_condition}
 * repository directly (ArchUnit module boundary — see {@code ModuleBoundaryTest}). loan-core depends
 * on this interface; the conditions module supplies the {@code @Component} adapter that delegates to
 * the conditions <em>service</em>.
 */
public interface OutstandingConditionResolver {

    /**
     * Loan ids (org-scoped via {@code @TenantId}) whose count of OUTSTANDING, non-soft-deleted
     * conditions is strictly greater than {@code n}. One grouped query — no per-loan round trips.
     * An empty result means "no loans qualify" (the caller then matches nothing).
     */
    Set<UUID> loanIdsWithOutstandingOver(int n);
}
