package com.msfg.los.loan.repo;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.domain.LoanStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface LoanRepository extends JpaRepository<Loan, UUID> {
    Optional<Loan> findByLoanNumber(String loanNumber);
    // Use findByIdAndOrgId (not findById) for all tenant-scoped loads: Hibernate 6's @TenantId
    // annotation does NOT add the org_id filter to EntityManager.find() by PK — only to
    // JPQL/Criteria queries. This method generates WHERE id=? AND org_id=? so cross-tenant
    // lookups return empty (→ NotFoundException 404) rather than finding the wrong tenant's row.
    Optional<Loan> findByIdAndOrgId(UUID id, UUID orgId);
    Page<Loan> findByLoanOfficerId(UUID loanOfficerId, Pageable pageable);
    Page<Loan> findByLoanOfficerIdAndStatus(UUID loanOfficerId, LoanStatus status, Pageable pageable);
    Page<Loan> findByStatus(LoanStatus status, Pageable pageable);
}
