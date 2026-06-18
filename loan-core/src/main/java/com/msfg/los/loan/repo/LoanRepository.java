package com.msfg.los.loan.repo;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.domain.LoanStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoanRepository extends JpaRepository<Loan, UUID> {
    Optional<Loan> findByLoanNumber(String loanNumber);
    // Use findByIdAndOrgId (not findById) for all tenant-scoped loads: Hibernate 6's @TenantId
    // annotation does NOT add the org_id filter to EntityManager.find() by PK — only to
    // JPQL/Criteria queries. This method generates WHERE id=? AND org_id=? so cross-tenant
    // lookups return empty (→ NotFoundException 404) rather than finding the wrong tenant's row.
    Optional<Loan> findByIdAndOrgId(UUID id, UUID orgId);

    // Soft-delete aware loads (Phase 2 T3): deleted loans must disappear from every read.
    Optional<Loan> findByIdAndOrgIdAndDeletedAtIsNull(UUID id, UUID orgId);
    Optional<Loan> findByLoanNumberAndDeletedAtIsNull(String loanNumber);

    // Pipeline — not-deleted variants (Phase 2 T3).
    Page<Loan> findByDeletedAtIsNull(Pageable pageable);
    Page<Loan> findByStatusAndDeletedAtIsNull(LoanStatus status, Pageable pageable);
    Page<Loan> findByLoanOfficerIdAndDeletedAtIsNull(UUID loanOfficerId, Pageable pageable);
    Page<Loan> findByLoanOfficerIdAndStatusAndDeletedAtIsNull(UUID loanOfficerId, LoanStatus status, Pageable pageable);

    // Typeahead search (Phase 2 T3) — loanNumber match, not-deleted, caller-scoped. Ordering is
    // applied in the service (exact → prefix → name) so the SQL just selects the candidate rows.
    @Query("""
           select l from Loan l
           where l.deletedAt is null
             and lower(l.loanNumber) like :q
           """)
    List<Loan> searchByLoanNumberLikeOrgWide(@Param("q") String q);

    @Query("""
           select l from Loan l
           where l.deletedAt is null
             and l.loanOfficerId = :lo
             and lower(l.loanNumber) like :q
           """)
    List<Loan> searchByLoanNumberLikeForOfficer(@Param("lo") UUID loanOfficerId, @Param("q") String q);

    // Fetch a bounded set of loans by id, not-deleted, scoped — for the borrower-name search leg.
    @Query("""
           select l from Loan l
           where l.deletedAt is null
             and l.id in :ids
           """)
    List<Loan> findActiveByIds(@Param("ids") Collection<UUID> ids);

    @Query("""
           select l from Loan l
           where l.deletedAt is null
             and l.loanOfficerId = :lo
             and l.id in :ids
           """)
    List<Loan> findActiveByIdsForOfficer(@Param("lo") UUID loanOfficerId, @Param("ids") Collection<UUID> ids);
}
