package com.msfg.los.aus.repo;

import com.msfg.los.aus.domain.AusRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AusRunRepository extends JpaRepository<AusRun, UUID> {
    List<AusRun> findByLoanIdOrderByRequestedAtDescIdDesc(UUID loanId);        // @TenantId-filtered
    Optional<AusRun> findByIdAndOrgId(UUID id, UUID orgId);
}
