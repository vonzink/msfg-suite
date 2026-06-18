package com.msfg.los.notes.repo;

import com.msfg.los.notes.domain.LoanNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoanNoteRepository extends JpaRepository<LoanNote, UUID> {

    /** Notes for a loan, newest-first (stable id tiebreaker for same-instant rows). */
    List<LoanNote> findByLoanIdOrderByCreatedAtDescIdDesc(UUID loanId);

    /** Tenant-scoped PK load (the @TenantId filter does NOT apply to find()-by-PK). */
    Optional<LoanNote> findByIdAndOrgId(UUID id, UUID orgId);
}
