package com.msfg.los.fees.repo;

import com.msfg.los.fees.domain.FeeLineItem;
import com.msfg.los.fees.domain.FeeSection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeeLineItemRepository extends JpaRepository<FeeLineItem, UUID> {

    List<FeeLineItem> findByLoanIdOrderByOrdinalAsc(UUID loanId);

    Optional<FeeLineItem> findByIdAndOrgId(UUID id, UUID orgId);

    boolean existsByLoanIdAndSectionAndLabel(UUID loanId, FeeSection section, String label);

    long countByLoanId(UUID loanId);
}
