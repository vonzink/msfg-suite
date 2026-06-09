package com.msfg.los.declarations.web;

import com.msfg.los.declarations.service.DeclarationsService;
import com.msfg.los.declarations.web.dto.DeclarationsRequest;
import com.msfg.los.declarations.web.dto.DeclarationsResponse;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/borrowers/{borrowerId}/declarations")
public class DeclarationsController {

    private final DeclarationsService service;

    public DeclarationsController(DeclarationsService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<DeclarationsResponse> get(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId) {
        return ApiResponse.ok(DeclarationsResponse.from(service.get(loanId, borrowerId)));
    }

    @PutMapping
    public ApiResponse<DeclarationsResponse> upsert(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId,
            @Valid @RequestBody DeclarationsRequest req) {
        return ApiResponse.ok(DeclarationsResponse.from(service.upsert(loanId, borrowerId, req)));
    }
}
