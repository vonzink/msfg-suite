package com.msfg.los.financials.repo;

import com.msfg.los.financials.domain.AssetVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetVerificationRepository extends JpaRepository<AssetVerification, UUID> {
    List<AssetVerification> findByLoanIdOrderByOrderedAtDesc(UUID loanId);
    Optional<AssetVerification> findByIdAndOrgId(UUID id, UUID orgId);
}
