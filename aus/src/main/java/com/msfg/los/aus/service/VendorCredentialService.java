package com.msfg.los.aus.service;

import com.msfg.los.aus.domain.CredentialVendor;
import com.msfg.los.aus.domain.VendorCredential;
import com.msfg.los.aus.repo.VendorCredentialRepository;
import com.msfg.los.aus.web.dto.UpsertVendorCredentialRequest;
import com.msfg.los.aus.web.dto.VendorCredentialResponse;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Org-wide vendor credentials (loanId NULL rows). Secrets are AES-GCM-encrypted at rest by the
 * entity's {@code EncryptedStringConverter} columns and are NEVER returned: responses carry only
 * set-booleans and a masked username — raw values have no field to ride out on.
 */
@Service
public class VendorCredentialService {

    private final VendorCredentialRepository credentials;
    private final TenantContext tenantContext;

    public VendorCredentialService(VendorCredentialRepository credentials, TenantContext tenantContext) {
        this.credentials = credentials;
        this.tenantContext = tenantContext;
    }

    private UUID org() {
        return tenantContext.orgId().orElseThrow(() -> new NotFoundException("Tenant", "current"));
    }

    @Transactional
    public VendorCredentialResponse upsertOrg(CredentialVendor vendor, UpsertVendorCredentialRequest req) {
        VendorCredential cred = credentials.findByOrgIdAndVendorAndLoanIdIsNull(org(), vendor)
                .orElseGet(() -> {
                    VendorCredential c = new VendorCredential();
                    c.setVendor(vendor);
                    c.setLoanId(null);
                    return c;
                });

        // Identity fields: overwrite only when the request carries a value.
        if (req.institutionId() != null) cred.setInstitutionId(req.institutionId());
        if (req.sellerServicerNumber() != null) cred.setSellerServicerNumber(req.sellerServicerNumber());
        if (req.tpoNumber() != null) cred.setTpoNumber(req.tpoNumber());
        if (req.branchNumber() != null) cred.setBranchNumber(req.branchNumber());
        if (req.creditProviderCode() != null) cred.setCreditProviderCode(req.creditProviderCode());
        if (req.creditAffiliateCode() != null) cred.setCreditAffiliateCode(req.creditAffiliateCode());

        // Secret fields: null = keep, blank = clear, non-blank = set.
        cred.setUsername(applySecret(cred.getUsername(), req.username()));
        cred.setPassword(applySecret(cred.getPassword(), req.password()));
        cred.setCreditUsername(applySecret(cred.getCreditUsername(), req.creditUsername()));
        cred.setCreditPassword(applySecret(cred.getCreditPassword(), req.creditPassword()));

        return toResponse(credentials.save(cred));
    }

    @Transactional(readOnly = true)
    public List<VendorCredentialResponse> listOrg() {
        return credentials.findByOrgIdAndLoanIdIsNull(org()).stream()
                .map(this::toResponse)
                .toList();
    }

    private static String applySecret(String current, String incoming) {
        if (incoming == null) return current;
        return incoming.isBlank() ? null : incoming;
    }

    static String mask(String v) {
        if (v == null || v.isBlank()) return null;
        return v.length() <= 2 ? "••" : v.charAt(0) + "•••" + v.charAt(v.length() - 1);
    }

    private static boolean isSet(String v) {
        return v != null && !v.isBlank();
    }

    private VendorCredentialResponse toResponse(VendorCredential c) {
        return new VendorCredentialResponse(
                c.getVendor(),
                c.getInstitutionId(),
                c.getSellerServicerNumber(),
                c.getTpoNumber(),
                c.getBranchNumber(),
                c.getCreditProviderCode(),
                c.getCreditAffiliateCode(),
                isSet(c.getUsername()), mask(c.getUsername()),
                isSet(c.getPassword()),
                isSet(c.getCreditUsername()), mask(c.getCreditUsername()),
                isSet(c.getCreditPassword()));
    }
}
