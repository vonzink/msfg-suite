package com.msfg.los.aus.web;

import com.msfg.los.aus.domain.CredentialVendor;
import com.msfg.los.aus.service.VendorCredentialService;
import com.msfg.los.aus.web.dto.UpsertVendorCredentialRequest;
import com.msfg.los.aus.web.dto.VendorCredentialResponse;
import com.msfg.los.platform.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Role enforcement lives in SecurityConfig: /api/org/** requires ROLE_ADMIN.
@RestController
@RequestMapping("/api/org/vendor-credentials")
public class OrgVendorCredentialController {

    private final VendorCredentialService service;

    public OrgVendorCredentialController(VendorCredentialService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<VendorCredentialResponse>> list() {
        return ApiResponse.ok(service.listOrg());
    }

    @PutMapping("/{vendor}")
    public ApiResponse<VendorCredentialResponse> upsert(@PathVariable CredentialVendor vendor,
                                                        @RequestBody UpsertVendorCredentialRequest req) {
        return ApiResponse.ok(service.upsertOrg(vendor, req));
    }
}
