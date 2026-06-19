package com.msfg.los.loan.service;

import java.util.UUID;

/**
 * Cross-module seam (parties → loan-core) for <strong>verified-email borrower account linking</strong>.
 *
 * <p>Implemented by the {@code parties} module so {@code loan-core} (and {@code identity}, which deps
 * loan-core but not parties) never reaches the borrower repository directly (ArchUnit boundary).
 * Mirrors {@link LoanLinkageResolver}: UUID/String-only signatures, no {@code parties} types cross
 * the boundary. All queries are tenant-filtered via Hibernate {@code @TenantId} — the caller must
 * ensure the tenant context is bound.
 *
 * <p>Used by {@code /me} materialization (T5a) to link a signed-in borrower's Cognito {@code sub} to
 * their {@code borrower_party} row — but only under strict anti-takeover rules (see
 * {@link #linkByVerifiedEmail}).
 */
public interface BorrowerUserLinker {

    /**
     * Attempts to link {@code userId} (a Cognito sub) to a borrower row by verified email, within the
     * current tenant. <strong>Account-takeover defense — the contract is precise:</strong>
     *
     * <ul>
     *   <li>Match candidates are {@code borrower_party} rows in the current tenant where
     *       {@code lower(email) = lower(:email)} AND {@code user_id IS NULL}.</li>
     *   <li>Stamp {@code user_id = :userId} <strong>only if there is EXACTLY ONE</strong> such row.</li>
     *   <li>Zero matches, or more than one match → do nothing (no stamp).</li>
     *   <li>An already-linked row ({@code user_id} non-null) is never a candidate, so a pre-existing
     *       link is never overwritten.</li>
     *   <li>Idempotent: once a row is linked, the {@code user_id IS NULL} filter excludes it, so a
     *       re-call is a no-op.</li>
     * </ul>
     *
     * <p>Callers must ensure the email is verified by the IdP before calling — this method does not
     * itself check verification.
     *
     * @param email  the verified email to match (case-insensitively); a {@code null}/blank email never
     *               matches
     * @param userId the Cognito sub to stamp; {@code null} never links
     * @return {@link LinkResult#LINKED} if a single row was stamped, otherwise {@link LinkResult#NO_OP}
     */
    LinkResult linkByVerifiedEmail(String email, UUID userId);

    /** Outcome of a {@link #linkByVerifiedEmail} attempt. */
    enum LinkResult {
        /** Exactly one unlinked match was found and stamped with the user id. */
        LINKED,
        /** Zero matches, more than one match, or null/blank inputs — nothing was changed. */
        NO_OP
    }
}
