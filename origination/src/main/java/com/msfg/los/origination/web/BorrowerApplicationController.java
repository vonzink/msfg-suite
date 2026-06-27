package com.msfg.los.origination.web;

import com.msfg.los.origination.service.BorrowerApplicationService;
import com.msfg.los.origination.web.dto.BorrowerApplicationRequest;
import com.msfg.los.origination.web.dto.BorrowerApplicationResponse;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Stage-2 borrower-self application: read + write the caller's own 1003 to the suite (the system of
 * record), so the borrower and the loan officer edit the SAME loan.
 *
 * <p>Path auth lives in {@code SecurityConfig} (STAFF_AND_BORROWER on this UUID-constrained regex path).
 * Fine-grained borrower-self authorization is enforced in {@link BorrowerApplicationService}
 * ({@code assertBorrowerSelfReadable} on GET, {@code assertBorrowerSelfWritable} on PUT): a borrower
 * acts on their OWN row, staff/owning-LO target the primary borrower, everyone else is denied.
 */
@RestController
@RequestMapping("/api/loans/{loanId}/application")
public class BorrowerApplicationController {

    private final BorrowerApplicationService service;

    public BorrowerApplicationController(BorrowerApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<BorrowerApplicationResponse> get(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.get(loanId));
    }

    @PutMapping
    public ApiResponse<BorrowerApplicationResponse> upsert(
            @PathVariable UUID loanId,
            @Valid @RequestBody BorrowerApplicationRequest req) {
        return ApiResponse.ok(service.upsert(loanId, req));
    }
}
