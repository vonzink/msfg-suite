package com.msfg.los.aus.repo;

import com.msfg.los.aus.domain.AusRun;
import com.msfg.los.aus.domain.AusVendor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AusRunRepository extends JpaRepository<AusRun, UUID> {
    List<AusRun> findByLoanIdOrderByRequestedAtDescIdDesc(UUID loanId);        // @TenantId-filtered

    // Latest prior run for this vendor that actually received a vendor-assigned casefile id
    // (ERROR rows carry no vendorCaseId). Fetches exactly the one row resubmits need.
    Optional<AusRun> findTopByLoanIdAndVendorAndVendorCaseIdIsNotNullOrderByRequestedAtDescIdDesc(
            UUID loanId, AusVendor vendor);

    Optional<AusRun> findByIdAndOrgId(UUID id, UUID orgId);
}
