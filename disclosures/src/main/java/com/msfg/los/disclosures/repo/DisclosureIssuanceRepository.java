package com.msfg.los.disclosures.repo;

import com.msfg.los.disclosures.domain.DisclosureIssuance;
import com.msfg.los.disclosures.domain.DisclosureKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisclosureIssuanceRepository extends JpaRepository<DisclosureIssuance, UUID> {

    List<DisclosureIssuance> findByLoanIdOrderByRequestedAtDescIdDesc(UUID loanId);

    Optional<DisclosureIssuance> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<DisclosureIssuance> findTopByLoanIdAndKindOrderByDisclosureVersionDesc(UUID loanId, DisclosureKind kind);
}
