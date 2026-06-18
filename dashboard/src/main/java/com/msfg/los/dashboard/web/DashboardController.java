package com.msfg.los.dashboard.web;

import com.msfg.los.dashboard.service.DashboardService;
import com.msfg.los.dashboard.web.dto.DashboardResponse;
import com.msfg.los.dashboard.web.dto.DashboardResponse.DashboardLoanTerms;
import com.msfg.los.dashboard.web.dto.DashboardTermsPatch;
import com.msfg.los.platform.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Aggregated, read-only loan dashboard (Phase 2 T6) + an edit-terms patch. Loan-access-gated in the
 * service. Mirrors {@code qualification}'s controller shape ({@code /api/loans/{loanId}/…}).
 */
@RestController
@RequestMapping("/api/loans/{loanId}/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<DashboardResponse> dashboard(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.dashboard(loanId));
    }

    @PatchMapping("/terms")
    public ApiResponse<DashboardLoanTerms> patchTerms(
            @PathVariable UUID loanId,
            @RequestBody DashboardTermsPatch patch) {
        return ApiResponse.ok(service.patchTerms(loanId, patch));
    }
}
