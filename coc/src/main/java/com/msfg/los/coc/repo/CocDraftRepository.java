package com.msfg.los.coc.repo;

import com.msfg.los.coc.domain.CocDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CocDraftRepository extends JpaRepository<CocDraft, UUID> {

    Optional<CocDraft> findByLoanId(UUID loanId);

    Optional<CocDraft> findByIdAndOrgId(UUID id, UUID orgId);
}
