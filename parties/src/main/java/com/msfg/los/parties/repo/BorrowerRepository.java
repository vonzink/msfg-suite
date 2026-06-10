package com.msfg.los.parties.repo;

import com.msfg.los.parties.domain.BorrowerParty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BorrowerRepository extends JpaRepository<BorrowerParty, UUID> {

    List<BorrowerParty> findByLoanIdOrderByOrdinalAsc(UUID loanId);

    long countByLoanId(UUID loanId);

    Optional<BorrowerParty> findByIdAndOrgId(UUID id, UUID orgId);

    List<BorrowerParty> findByLoanIdInAndPrimaryTrue(Collection<UUID> loanIds);
}
