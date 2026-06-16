package com.msfg.los.income.repo;

import com.msfg.los.income.domain.IncomeItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncomeItemRepository extends JpaRepository<IncomeItem, UUID> {
    List<IncomeItem> findByBorrowerIdOrderByOrdinalAscIdAsc(UUID borrowerId);
    List<IncomeItem> findByLoanIdOrderByOrdinalAsc(UUID loanId);
    Optional<IncomeItem> findByIdAndOrgId(UUID id, UUID orgId);
    Optional<IncomeItem> findTopByBorrowerIdOrderByOrdinalDesc(UUID borrowerId);
}
