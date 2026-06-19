package com.msfg.los.parties.repo;

import com.msfg.los.parties.domain.BorrowerParty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BorrowerRepository extends JpaRepository<BorrowerParty, UUID> {

    List<BorrowerParty> findByLoanIdOrderByOrdinalAsc(UUID loanId);

    long countByLoanId(UUID loanId);

    Optional<BorrowerParty> findByIdAndOrgId(UUID id, UUID orgId);

    List<BorrowerParty> findByLoanIdInAndPrimaryTrue(Collection<UUID> loanIds);

    /**
     * Returns {@code true} when any borrower on {@code loanId} in the current tenant has
     * {@code userId} set to the given value. Tenant-filtered by Hibernate {@code @TenantId}.
     *
     * <p>Drives {@link com.msfg.los.parties.service.LoanLinkageAdapter#isBorrowerOnLoan}.
     */
    boolean existsByLoanIdAndUserId(UUID loanId, UUID userId);

    /**
     * Returns {@code true} when the borrower row {@code id} in the current tenant is linked to
     * {@code userId} — i.e. the authenticated user IS that exact borrower. Tenant-filtered by
     * Hibernate {@code @TenantId}.
     *
     * <p>Drives {@link com.msfg.los.parties.service.LoanLinkageAdapter#isBorrowerSelf} (T11
     * per-borrower own-data reads). Matches the EXACT row, so a borrower can never read a
     * co-borrower's data.
     */
    boolean existsByIdAndUserId(UUID id, UUID userId);

    /**
     * Returns the distinct loan-ids where the given user appears as a borrower in the current
     * tenant. Tenant-filtered by Hibernate {@code @TenantId}.
     *
     * <p>{@code distinct} is required here (unlike the agent side's {@code findLoanIdsByUserId}):
     * {@code borrower_party} has NO {@code (loan_id, user_id)} uniqueness — a borrower may appear on
     * the same loan more than once (distinct {@code ordinal}s) — whereas {@code loan_agent} is uniquely
     * keyed on {@code (org_id, loan_id, user_id)}. Do not "harmonize" the two queries.
     *
     * <p>Drives {@link com.msfg.los.parties.service.LoanLinkageAdapter#loanIdsForBorrower}.
     */
    @Query("select distinct b.loanId from BorrowerParty b where b.userId = :userId")
    List<UUID> findLoanIdsByUserId(@Param("userId") UUID userId);

    /**
     * Loan ids whose PRIMARY borrower's first, last, or "first last" name contains {@code q}
     * (case-insensitive). Tenant-filtered by Hibernate {@code @TenantId} (JPQL). {@code q} is the
     * caller-supplied substring, already lower-cased and percent-wrapped (e.g. {@code %smith%}).
     */
    @Query("""
           select distinct b.loanId from BorrowerParty b
           where b.primary = true
             and (lower(b.firstName) like :q
                  or lower(b.lastName) like :q
                  or lower(concat(b.firstName, ' ', b.lastName)) like :q)
           """)
    List<UUID> findLoanIdsByPrimaryBorrowerNameLike(@Param("q") String q);

    /**
     * Ids of UNLINKED borrower rows in the current tenant whose email matches {@code email}
     * case-insensitively ({@code lower(email) = lower(:email)} AND {@code user_id IS NULL}).
     * Tenant-filtered by Hibernate {@code @TenantId} (JPQL).
     *
     * <p>The account-takeover defense: the adapter links only when this returns EXACTLY ONE id, and
     * the {@code user_id IS NULL} predicate guarantees an already-linked row is never a candidate
     * (no overwrite). A rows-with-null-email row never matches (the equality excludes nulls).
     *
     * <p>Drives {@link com.msfg.los.parties.service.BorrowerUserLinkAdapter#linkByVerifiedEmail}.
     */
    @Query("""
           select b.id from BorrowerParty b
           where b.userId is null
             and lower(b.email) = lower(:email)
           """)
    List<UUID> findUnlinkedIdsByEmailIgnoreCase(@Param("email") String email);

    /**
     * Stamps {@code user_id = :userId} on the single borrower row {@code :id}, but only while it is
     * still unlinked ({@code user_id IS NULL}). Tenant-filtered by Hibernate {@code @TenantId}.
     *
     * <p>The {@code user_id IS NULL} guard makes the write idempotent and never an overwrite even
     * under a race: a concurrent stamp leaves rows-affected = 0. Returns the number of rows updated
     * (0 or 1).
     *
     * <p>Drives {@link com.msfg.los.parties.service.BorrowerUserLinkAdapter#linkByVerifiedEmail}.
     *
     * <p>{@code flushAutomatically} only (NOT {@code clearAutomatically}): the linker loads no
     * {@code BorrowerParty} entities, so there is nothing stale to clear — and clearing the shared
     * persistence context would detach the {@code user_account} just materialized in the same
     * {@code /me} transaction.
     */
    @Modifying(flushAutomatically = true)
    @Query("update BorrowerParty b set b.userId = :userId where b.id = :id and b.userId is null")
    int linkUserIfUnlinked(@Param("id") UUID id, @Param("userId") UUID userId);
}
