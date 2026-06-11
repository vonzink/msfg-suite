package com.msfg.los.coc.repo;

import com.msfg.los.coc.domain.CocHistoryEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CocHistoryEntryRepository extends JpaRepository<CocHistoryEntry, UUID> {

    List<CocHistoryEntry> findByLoanIdOrderBySubmittedAtDesc(UUID loanId);

    Optional<CocHistoryEntry> findByIdAndOrgId(UUID id, UUID orgId);
}
