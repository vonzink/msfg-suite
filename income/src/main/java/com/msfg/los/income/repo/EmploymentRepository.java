package com.msfg.los.income.repo;

import com.msfg.los.income.domain.Employment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmploymentRepository extends JpaRepository<Employment, UUID> {
    List<Employment> findByBorrowerIdOrderByOrdinalAsc(UUID borrowerId);
    List<Employment> findByLoanIdOrderByOrdinalAsc(UUID loanId);
    Optional<Employment> findByIdAndOrgId(UUID id, UUID orgId);
    long countByBorrowerId(UUID borrowerId);
}
