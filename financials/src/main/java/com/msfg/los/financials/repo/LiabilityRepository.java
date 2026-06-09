package com.msfg.los.financials.repo;

import com.msfg.los.financials.domain.Liability;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LiabilityRepository extends JpaRepository<Liability, UUID> {
    List<Liability> findByBorrowerIdOrderByOrdinalAsc(UUID borrowerId);
    List<Liability> findByLoanIdOrderByOrdinalAsc(UUID loanId);
    Optional<Liability> findByIdAndOrgId(UUID id, UUID orgId);
    long countByBorrowerId(UUID borrowerId);
}
