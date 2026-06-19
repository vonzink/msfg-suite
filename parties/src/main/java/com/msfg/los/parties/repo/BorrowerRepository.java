package com.msfg.los.parties.repo;

import com.msfg.los.parties.domain.BorrowerParty;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
