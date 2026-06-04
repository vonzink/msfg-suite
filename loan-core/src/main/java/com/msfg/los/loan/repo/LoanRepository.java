package com.msfg.los.loan.repo;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.domain.LoanStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface LoanRepository extends JpaRepository<Loan, UUID> {
    Optional<Loan> findByLoanNumber(String loanNumber);
    Page<Loan> findByLoanOfficerId(UUID loanOfficerId, Pageable pageable);
    Page<Loan> findByLoanOfficerIdAndStatus(UUID loanOfficerId, LoanStatus status, Pageable pageable);
    Page<Loan> findByStatus(LoanStatus status, Pageable pageable);
}
