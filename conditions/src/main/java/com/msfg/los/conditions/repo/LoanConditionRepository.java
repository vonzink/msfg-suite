package com.msfg.los.conditions.repo;

import com.msfg.los.conditions.domain.ConditionStatus;
import com.msfg.los.conditions.domain.LoanCondition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoanConditionRepository extends JpaRepository<LoanCondition, UUID> {

    /** Live (non-soft-deleted) conditions for a loan, ordered oldest-first (stable id tiebreaker). */
    List<LoanCondition> findByLoanIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(UUID loanId);

    /** Tenant-scoped PK load (the @TenantId filter does NOT apply to find()-by-PK). */
    Optional<LoanCondition> findByIdAndOrgId(UUID id, UUID orgId);

    /** Outstanding-condition count for a loan (excludes soft-deleted) — feeds the pipeline conditionsGt filter + dashboard. */
    int countByLoanIdAndStatusAndDeletedAtIsNull(UUID loanId, ConditionStatus status);

    /**
     * One grouped query: loan ids whose count of OUTSTANDING, non-soft-deleted conditions is strictly
     * greater than {@code n}. Org-scoped automatically by Hibernate {@code @TenantId}. Feeds the
     * pipeline {@code conditionsGt} filter (Phase 2 T4) — no per-loan round trips.
     */
    @Query("""
           select c.loanId from LoanCondition c
           where c.status = com.msfg.los.conditions.domain.ConditionStatus.Outstanding
             and c.deletedAt is null
           group by c.loanId
           having count(c) > :n
           """)
    List<UUID> findLoanIdsWithOutstandingOver(@Param("n") long n);
}
