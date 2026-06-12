package com.msfg.los.aus.repo;

import com.msfg.los.aus.domain.CreditOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditOrderRepository extends JpaRepository<CreditOrder, UUID> {
    List<CreditOrder> findByLoanIdOrderByRequestedAtDescIdDesc(UUID loanId);   // @TenantId-filtered
    Optional<CreditOrder> findByIdAndOrgId(UUID id, UUID orgId);
}
