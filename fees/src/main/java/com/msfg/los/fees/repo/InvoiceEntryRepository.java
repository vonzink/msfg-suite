package com.msfg.los.fees.repo;

import com.msfg.los.fees.domain.InvoiceEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceEntryRepository extends JpaRepository<InvoiceEntry, UUID> {

    List<InvoiceEntry> findByLoanIdOrderByFeeLabelAsc(UUID loanId);

    Optional<InvoiceEntry> findByLoanIdAndFeeLabel(UUID loanId, String feeLabel);

    Optional<InvoiceEntry> findByIdAndOrgId(UUID id, UUID orgId);
}
