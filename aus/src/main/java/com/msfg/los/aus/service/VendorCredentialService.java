package com.msfg.los.aus.service;

import com.msfg.los.aus.domain.CredentialSource;
import com.msfg.los.aus.domain.CredentialVendor;
import com.msfg.los.aus.domain.VendorCredential;
import com.msfg.los.aus.repo.VendorCredentialRepository;
import com.msfg.los.aus.web.dto.UpsertVendorCredentialRequest;
import com.msfg.los.aus.web.dto.VendorCredentialResponse;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Vendor credentials: org-wide defaults (loanId NULL rows) + per-loan overrides (loanId set).
 * Secrets are AES-GCM-encrypted at rest by the entity's {@code EncryptedStringConverter} columns
 * and are NEVER returned: responses carry only set-booleans and a masked username — raw values
 * have no field to ride out on.
 */
@Service
public class VendorCredentialService {

    private final VendorCredentialRepository credentials;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;

    public VendorCredentialService(VendorCredentialRepository credentials,
                                   LoanService loanService,
                                   LoanAccessGuard accessGuard,
                                   TenantContext tenantContext) {
        this.credentials = credentials;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
    }

    private void guard(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
    }

    @Transactional
    public VendorCredentialResponse upsertOrg(CredentialVendor vendor, UpsertVendorCredentialRequest req) {
        VendorCredential cred = credentials.findByOrgIdAndVendorAndLoanIdIsNull(tenantContext.requireOrgId(), vendor)
                .orElseGet(() -> {
                    VendorCredential c = new VendorCredential();
                    c.setVendor(vendor);
                    c.setLoanId(null);
                    return c;
                });
        apply(cred, req);
        return toResponse(credentials.save(cred));
    }

    @Transactional(readOnly = true)
    public List<VendorCredentialResponse> listOrg() {
        return credentials.findByOrgIdAndLoanIdIsNull(tenantContext.requireOrgId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public VendorCredentialResponse upsertLoan(UUID loanId, CredentialVendor vendor, UpsertVendorCredentialRequest req) {
        guard(loanId);
        VendorCredential cred = credentials.findByOrgIdAndVendorAndLoanId(tenantContext.requireOrgId(), vendor, loanId)
                .orElseGet(() -> {
                    VendorCredential c = new VendorCredential();
                    c.setVendor(vendor);
                    c.setLoanId(loanId);
                    return c;
                });
        apply(cred, req);
        return toResponse(credentials.save(cred));
    }

    @Transactional(readOnly = true)
    public List<VendorCredentialResponse> listLoan(UUID loanId) {
        guard(loanId);
        return credentials.findByOrgIdAndLoanId(tenantContext.requireOrgId(), loanId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void deleteLoan(UUID loanId, CredentialVendor vendor) {
        guard(loanId);
        VendorCredential cred = credentials.findByOrgIdAndVendorAndLoanId(tenantContext.requireOrgId(), vendor, loanId)
                .orElseThrow(() -> new NotFoundException("VendorCredential", vendor.name()));
        credentials.delete(cred);
    }

    /**
     * Whole-row resolution: the loan override row wins outright, else the org row, else NONE.
     * No per-field merging — every field (including nulls) comes from the single chosen row.
     *
     * <p>Internal seam for vendor adapters: returns plaintext secrets and performs NO
     * authorization itself — callers MUST have already run the loan access guard.
     */
    @Transactional(readOnly = true)
    public ResolvedCredentials resolve(UUID loanId, CredentialVendor vendor) {
        return credentials.findByOrgIdAndVendorAndLoanId(tenantContext.requireOrgId(), vendor, loanId)
                .or(() -> credentials.findByOrgIdAndVendorAndLoanIdIsNull(tenantContext.requireOrgId(), vendor))
                .map(c -> new ResolvedCredentials(
                        c.getLoanId() != null ? CredentialSource.LOAN : CredentialSource.ORG,
                        c.getInstitutionId(),
                        c.getSellerServicerNumber(),
                        c.getTpoNumber(),
                        c.getBranchNumber(),
                        c.getUsername(),
                        c.getPassword(),
                        c.getCreditProviderCode(),
                        c.getCreditAffiliateCode(),
                        c.getCreditUsername(),
                        c.getCreditPassword()))
                .orElseGet(() -> new ResolvedCredentials(
                        CredentialSource.NONE, null, null, null, null, null, null, null, null, null, null));
    }

    private static void apply(VendorCredential cred, UpsertVendorCredentialRequest req) {
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
