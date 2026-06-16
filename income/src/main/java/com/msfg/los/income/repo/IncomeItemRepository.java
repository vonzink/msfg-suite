package com.msfg.los.income.repo;

import com.msfg.los.income.domain.IncomeItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncomeItemRepository extends JpaRepository<IncomeItem, UUID> {
    List<IncomeItem> findByBorrowerIdOrderByOrdinalAscIdAsc(UUID borrowerId);
    List<IncomeItem> findByLoanIdOrderByOrdinalAsc(UUID loanId);
    Optional<IncomeItem> findByIdAndOrgId(UUID id, UUID orgId);
    Optional<IncomeItem> findTopByBorrowerIdOrderByOrdinalDesc(UUID borrowerId);

    /** Σ monthlyAmount for a loan (null amounts ignored); never null — 0 when no rows. */
    @Query("select coalesce(sum(i.monthlyAmount), 0) from IncomeItem i where i.loanId = :loanId")
    BigDecimal sumMonthlyAmountByLoanId(@Param("loanId") UUID loanId);
}
