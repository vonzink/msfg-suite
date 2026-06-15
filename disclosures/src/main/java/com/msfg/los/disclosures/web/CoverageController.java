package com.msfg.los.disclosures.web;

import com.msfg.los.disclosures.service.CoverageService;
import com.msfg.los.disclosures.web.dto.CoverageResponse;
import com.msfg.los.platform.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** TRID coverage gate — whether a loan is subject to the LE/CD disclosure regime. */
@RestController
public class CoverageController {

    private final CoverageService service;

    public CoverageController(CoverageService service) {
        this.service = service;
    }

    @GetMapping("/api/loans/{loanId}/disclosures/coverage")
    public ApiResponse<CoverageResponse> coverage(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.evaluate(loanId));
    }
}
