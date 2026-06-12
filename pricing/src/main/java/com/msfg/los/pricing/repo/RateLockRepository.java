package com.msfg.los.pricing.repo;

import com.msfg.los.pricing.domain.RateLock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RateLockRepository extends JpaRepository<RateLock, UUID> {
    Optional<RateLock> findByLoanId(UUID loanId);          // @TenantId-filtered
}
