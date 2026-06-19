package com.msfg.los.loan.service;

import java.util.List;
import java.util.UUID;

/**
 * Cross-module seam (parties → loan-core) for borrower-side self-scoping.
 *
 * <p>Implemented by the {@code parties} module so that {@code loan-core} never reaches the
 * borrower repository directly (ArchUnit module-boundary rule). All queries are tenant-filtered
 * via Hibernate {@code @TenantId} — callers must ensure the tenant context is bound.
 *
 * <p>Used by:
 * <ul>
 *   <li>T6 access guard — {@link #isBorrowerOnLoan} determines borrower self-scope</li>
 *   <li>T7 {@code /me/loans} — {@link #loanIdsForBorrower} scopes the pipeline</li>
 *   <li>T11 access guard — {@link #isBorrowerSelf} gates per-borrower own-data reads</li>
 * </ul>
 */
public interface LoanLinkageResolver {

    /**
     * Returns {@code true} when {@code userId} (a Cognito sub) is linked as a borrower on
     * {@code loanId} within the current tenant.
     *
     * <p>A {@code null} {@code userId} always returns {@code false} — co-borrowers without a
     * linked account must never match an authenticated user.
     */
    boolean isBorrowerOnLoan(UUID loanId, UUID userId);

    /**
     * Returns the list of loan-ids the given user is linked to as a borrower within the current
     * tenant.  Used by {@code /me/loans} to scope the pipeline for an authenticated borrower.
     *
     * <p>A {@code null} {@code userId} always returns an empty list.
     */
    List<UUID> loanIdsForBorrower(UUID userId);

    /**
     * Returns {@code true} when a borrower row {@code borrowerId} in the current tenant is linked to
     * {@code userId} (a Cognito sub) — i.e. the authenticated user IS that specific borrower.
     *
     * <p>Unlike {@link #isBorrowerOnLoan} (any borrower on a loan), this matches the EXACT borrower
     * row: a borrower must never read a co-borrower's per-borrower 1003 data. Drives the T11 guard
     * {@code LoanAccessGuard.assertBorrowerSelfReadable}.
     *
     * <p>A {@code null} {@code userId} always returns {@code false} — co-borrowers without a linked
     * account must never match an authenticated user.
     */
    boolean isBorrowerSelf(UUID borrowerId, UUID userId);
}
