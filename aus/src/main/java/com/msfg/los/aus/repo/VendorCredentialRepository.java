package com.msfg.los.aus.repo;

import com.msfg.los.aus.domain.CredentialVendor;
import com.msfg.los.aus.domain.VendorCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VendorCredentialRepository extends JpaRepository<VendorCredential, UUID> {
    Optional<VendorCredential> findByOrgIdAndVendorAndLoanIdIsNull(UUID orgId, CredentialVendor vendor);
    Optional<VendorCredential> findByOrgIdAndVendorAndLoanId(UUID orgId, CredentialVendor vendor, UUID loanId);
    List<VendorCredential> findByOrgIdAndLoanIdIsNull(UUID orgId);
    List<VendorCredential> findByOrgIdAndLoanId(UUID orgId, UUID loanId);
    Optional<VendorCredential> findByIdAndOrgId(UUID id, UUID orgId);
}
