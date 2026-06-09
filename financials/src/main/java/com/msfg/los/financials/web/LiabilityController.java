package com.msfg.los.financials.web;

import com.msfg.los.financials.service.LiabilityService;
import com.msfg.los.financials.web.dto.AddLiabilityRequest;
import com.msfg.los.financials.web.dto.LiabilityResponse;
import com.msfg.los.financials.web.dto.UpdateLiabilityRequest;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/borrowers/{borrowerId}/liabilities")
public class LiabilityController {

    private final LiabilityService service;

    public LiabilityController(LiabilityService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<LiabilityResponse>> add(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId,
            @Valid @RequestBody AddLiabilityRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(LiabilityResponse.from(service.add(loanId, borrowerId, req))));
    }

    @GetMapping
    public ApiResponse<List<LiabilityResponse>> list(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId) {
        return ApiResponse.ok(service.list(loanId, borrowerId).stream().map(LiabilityResponse::from).toList());
    }

    @PatchMapping("/{liabilityId}")
    public ApiResponse<LiabilityResponse> update(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId,
            @PathVariable UUID liabilityId,
            @Valid @RequestBody UpdateLiabilityRequest req) {
        return ApiResponse.ok(LiabilityResponse.from(service.update(loanId, borrowerId, liabilityId, req)));
    }

    @DeleteMapping("/{liabilityId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId,
            @PathVariable UUID liabilityId) {
        service.delete(loanId, borrowerId, liabilityId);
        return ResponseEntity.noContent().build();
    }
}
