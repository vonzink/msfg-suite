package com.msfg.los.declarations.repo;

import com.msfg.los.declarations.domain.BorrowerDeclarations;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BorrowerDeclarationsRepository extends JpaRepository<BorrowerDeclarations, UUID> {

    Optional<BorrowerDeclarations> findByBorrowerId(UUID borrowerId);

    Optional<BorrowerDeclarations> findByIdAndOrgId(UUID id, UUID orgId);
}
