package com.msfg.los.reo.repo;

import com.msfg.los.reo.domain.RealEstateOwned;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RealEstateOwnedRepository extends JpaRepository<RealEstateOwned, UUID> {
    List<RealEstateOwned> findByLoanIdOrderByOrdinalAscIdAsc(UUID loanId);
    Optional<RealEstateOwned> findByIdAndOrgId(UUID id, UUID orgId);
    Optional<RealEstateOwned> findTopByLoanIdOrderByOrdinalDesc(UUID loanId);
}
