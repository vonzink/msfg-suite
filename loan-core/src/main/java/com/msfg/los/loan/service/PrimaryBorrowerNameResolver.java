package com.msfg.los.loan.service;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cross-module seam (parties → loan-core) for primary-borrower names. Implemented by the parties
 * module so loan-core never reaches the borrower repository directly (ArchUnit module boundary).
 */
public interface PrimaryBorrowerNameResolver {

    /** Batched: primary-borrower full name keyed by loan id (first primary per loan wins). */
    Map<UUID, String> primaryBorrowerNamesByLoanIds(Collection<UUID> loanIds);

    /**
     * Typeahead support (Phase 2 T3): loan ids whose primary borrower's first/last/full name contains
     * {@code query} (case-insensitive substring). Tenant-scoped via {@code @TenantId}. The caller
     * intersects the result with its own access scope (org-wide vs owning-LO).
     */
    Set<UUID> loanIdsByPrimaryBorrowerNameLike(String query);
}
