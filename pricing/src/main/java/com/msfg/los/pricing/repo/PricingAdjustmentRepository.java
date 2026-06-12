package com.msfg.los.pricing.repo;

import com.msfg.los.pricing.domain.PricingAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PricingAdjustmentRepository extends JpaRepository<PricingAdjustment, UUID> {
    List<PricingAdjustment> findByLoanIdOrderByOrdinalAscIdAsc(UUID loanId);
    void deleteByLoanId(UUID loanId);
}
