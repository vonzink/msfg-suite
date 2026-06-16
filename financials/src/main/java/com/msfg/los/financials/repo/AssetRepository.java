package com.msfg.los.financials.repo;

import com.msfg.los.financials.domain.Asset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<Asset, UUID> {
    List<Asset> findByBorrowerIdOrderByOrdinalAscIdAsc(UUID borrowerId);
    List<Asset> findByLoanIdOrderByOrdinalAsc(UUID loanId);
    Optional<Asset> findByIdAndOrgId(UUID id, UUID orgId);
    Optional<Asset> findTopByBorrowerIdOrderByOrdinalDesc(UUID borrowerId);
}
