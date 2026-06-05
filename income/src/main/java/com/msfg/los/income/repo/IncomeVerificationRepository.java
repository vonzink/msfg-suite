package com.msfg.los.income.repo;

import com.msfg.los.income.domain.IncomeVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncomeVerificationRepository extends JpaRepository<IncomeVerification, UUID> {
    List<IncomeVerification> findByLoanIdOrderByOrderedAtDesc(UUID loanId);
    Optional<IncomeVerification> findByIdAndOrgId(UUID id, UUID orgId);
}
