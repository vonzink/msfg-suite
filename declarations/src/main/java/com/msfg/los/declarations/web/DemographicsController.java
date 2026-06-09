package com.msfg.los.declarations.web;

import com.msfg.los.declarations.service.DemographicsService;
import com.msfg.los.declarations.web.dto.DemographicsRequest;
import com.msfg.los.declarations.web.dto.DemographicsResponse;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/borrowers/{borrowerId}/demographics")
public class DemographicsController {

    private final DemographicsService service;

    public DemographicsController(DemographicsService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<DemographicsResponse> get(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId) {
        return ApiResponse.ok(DemographicsResponse.from(service.get(loanId, borrowerId)));
    }

    @PutMapping
    public ApiResponse<DemographicsResponse> upsert(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId,
            @Valid @RequestBody DemographicsRequest req) {
        return ApiResponse.ok(DemographicsResponse.from(service.upsert(loanId, borrowerId, req)));
    }
}
