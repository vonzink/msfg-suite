package com.msfg.los.aus.web;

import com.msfg.los.aus.domain.CredentialVendor;
import com.msfg.los.aus.service.VendorCredentialService;
import com.msfg.los.aus.web.dto.UpsertVendorCredentialRequest;
import com.msfg.los.aus.web.dto.VendorCredentialResponse;
import com.msfg.los.platform.web.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Per-loan vendor credential overrides. Access control: the service runs the loan access
 * guard (owner-scoped LO, org-wide back-office) on every call — cross-org callers get 404.
 * Responses are masked exactly like the org-level endpoints (no raw secrets, ever).
 */
@RestController
@RequestMapping("/api/loans/{loanId}/aus/credentials")
public class LoanVendorCredentialController {

    private final VendorCredentialService service;

    public LoanVendorCredentialController(VendorCredentialService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<VendorCredentialResponse>> list(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.listLoan(loanId));
    }

    @PutMapping("/{vendor}")
    public ApiResponse<VendorCredentialResponse> upsert(@PathVariable UUID loanId,
                                                        @PathVariable CredentialVendor vendor,
                                                        @RequestBody UpsertVendorCredentialRequest req) {
        return ApiResponse.ok(service.upsertLoan(loanId, vendor, req));
    }

    @DeleteMapping("/{vendor}")
    public ResponseEntity<Void> delete(@PathVariable UUID loanId,
                                       @PathVariable CredentialVendor vendor) {
        service.deleteLoan(loanId, vendor);
        return ResponseEntity.noContent().build();
    }
}
