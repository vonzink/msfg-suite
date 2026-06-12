package com.msfg.los.aus.web;

import com.msfg.los.aus.service.AusProfileService;
import com.msfg.los.aus.web.dto.AusProfileResponse;
import com.msfg.los.aus.web.dto.UpsertAusProfileRequest;
import com.msfg.los.platform.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Per-loan AUS profile (DU + LPA submission settings + per-borrower credit references). */
@RestController
@RequestMapping("/api/loans/{loanId}/aus/profile")
public class AusProfileController {

    private final AusProfileService service;

    public AusProfileController(AusProfileService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AusProfileResponse> get(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.get(loanId));
    }

    @PutMapping
    public ApiResponse<AusProfileResponse> upsert(@PathVariable UUID loanId,
                                                  @RequestBody UpsertAusProfileRequest req) {
        return ApiResponse.ok(service.upsert(loanId, req));
    }
}
