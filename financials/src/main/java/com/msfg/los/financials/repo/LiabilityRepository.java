package com.msfg.los.financials.repo;

import com.msfg.los.financials.domain.Liability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LiabilityRepository extends JpaRepository<Liability, UUID> {
    List<Liability> findByBorrowerIdOrderByOrdinalAscIdAsc(UUID borrowerId);
    List<Liability> findByLoanIdOrderByOrdinalAsc(UUID loanId);
    Optional<Liability> findByIdAndOrgId(UUID id, UUID orgId);
    Optional<Liability> findTopByBorrowerIdOrderByOrdinalDesc(UUID borrowerId);

    /** Σ monthlyPayment for a loan, DTI-included rows only (null payments ignored); never null. */
    @Query("select coalesce(sum(l.monthlyPayment), 0) from Liability l "
            + "where l.loanId = :loanId and l.includeInDti = true")
    BigDecimal sumDtiMonthlyPaymentsByLoanId(@Param("loanId") UUID loanId);
}
