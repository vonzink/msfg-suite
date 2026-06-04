package com.msfg.los.loan.repo;

import com.msfg.los.loan.domain.LoanStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface LoanStatusHistoryRepository extends JpaRepository<LoanStatusHistory, UUID> {
    List<LoanStatusHistory> findByLoanIdOrderByCreatedAtAsc(UUID loanId);
}
