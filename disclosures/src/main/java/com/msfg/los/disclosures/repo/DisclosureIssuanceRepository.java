package com.msfg.los.disclosures.repo;

import com.msfg.los.disclosures.domain.DisclosureIssuance;
import com.msfg.los.disclosures.domain.DisclosureKind;
import com.msfg.los.disclosures.domain.DisclosureStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisclosureIssuanceRepository extends JpaRepository<DisclosureIssuance, UUID> {

    List<DisclosureIssuance> findByLoanIdOrderByRequestedAtDescIdDesc(UUID loanId);

    Optional<DisclosureIssuance> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<DisclosureIssuance> findTopByLoanIdAndKindOrderByDisclosureVersionDesc(UUID loanId, DisclosureKind kind);

    /**
     * The latest <em>successfully-issued</em> disclosure of a kind — restricted to a status set so
     * ERROR rows (which carry null APR/finance-charge and an empty snapshot) never poison reset
     * detection or tolerance baselining. Callers pass {@code List.of(SENT, RECEIVED)}.
     */
    Optional<DisclosureIssuance> findTopByLoanIdAndKindAndStatusInOrderByDisclosureVersionDesc(
            UUID loanId, DisclosureKind kind, Collection<DisclosureStatus> statuses);
}
