package com.msfg.los.declarations.repo;

import com.msfg.los.declarations.domain.BorrowerDemographics;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BorrowerDemographicsRepository extends JpaRepository<BorrowerDemographics, UUID> {

    Optional<BorrowerDemographics> findByBorrowerId(UUID borrowerId);

    Optional<BorrowerDemographics> findByIdAndOrgId(UUID id, UUID orgId);
}
