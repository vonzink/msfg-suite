package com.msfg.los.aus.repo;

import com.msfg.los.aus.domain.AusProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AusProfileRepository extends JpaRepository<AusProfile, UUID> {
    Optional<AusProfile> findByLoanId(UUID loanId);        // @TenantId-filtered
    Optional<AusProfile> findByIdAndOrgId(UUID id, UUID orgId);
}
