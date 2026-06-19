package com.msfg.los.loan.repo;

import com.msfg.los.loan.domain.LoanAgent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the V24 {@code loan_agent} table.
 *
 * <p>All derived queries and JPQL here use field-based predicates (not find-by-PK) so that
 * Hibernate's {@code @TenantId} filter is applied automatically — {@code EntityManager.find()}
 * by PK does NOT add the {@code org_id} predicate.
 */
public interface LoanAgentRepository extends JpaRepository<LoanAgent, UUID> {

    /**
     * Returns {@code true} when the given user is linked to the given loan in the current tenant.
     * Drives {@link com.msfg.los.loan.service.LoanAgentService#isAgentOnLoan}.
     */
    boolean existsByLoanIdAndUserId(UUID loanId, UUID userId);

    /**
     * Returns the set of loan-ids the given user is linked to in the current tenant.
     * Drives {@link com.msfg.los.loan.service.LoanAgentService#loanIdsForAgent}.
     */
    @Query("select a.loanId from LoanAgent a where a.userId = :userId")
    List<UUID> findLoanIdsByUserId(@Param("userId") UUID userId);

    // ── write-side additions (T8) ────────────────────────────────────────────

    /** Ordered roster for a loan; used by staff list + ordinal derivation. */
    List<LoanAgent> findByLoanIdOrderByOrdinalAscIdAsc(UUID loanId);

    /** Highest-ordinal row for a loan — used to compute max+1 for new assignments. */
    Optional<LoanAgent> findTopByLoanIdOrderByOrdinalDesc(UUID loanId);

    /**
     * Tenant-safe load by primary key — the predicate on {@code orgId} ensures the
     * {@code @TenantId} filter is included so find-by-PK cannot leak across tenants.
     */
    Optional<LoanAgent> findByIdAndOrgId(UUID id, UUID orgId);
}
