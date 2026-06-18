package com.msfg.los.conditions.web;

import com.msfg.los.conditions.service.ConditionService;
import com.msfg.los.conditions.web.dto.ConditionListResponse;
import com.msfg.los.conditions.web.dto.ConditionResponse;
import com.msfg.los.conditions.web.dto.UpsertConditionRequest;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Loan-scoped underwriting-condition endpoints. Staff-only, loan-access-gated (no role gate beyond
 * loan access, matching mortgage-app) — enforcement is in {@link ConditionService} via the loan guard.
 */
@RestController
@RequestMapping("/api/loans/{loanId}/conditions")
public class ConditionController {

    private final ConditionService service;

    public ConditionController(ConditionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ConditionResponse>> create(
            @PathVariable UUID loanId,
            @Valid @RequestBody UpsertConditionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(ConditionResponse.from(service.create(loanId, req))));
    }

    @GetMapping
    public ApiResponse<ConditionListResponse> list(@PathVariable UUID loanId) {
        var rows = service.list(loanId).stream().map(ConditionResponse::from).toList();
        return ApiResponse.ok(ConditionListResponse.of(rows));
    }

    @PatchMapping("/{conditionId}")
    public ApiResponse<ConditionResponse> update(
            @PathVariable UUID loanId,
            @PathVariable UUID conditionId,
            @Valid @RequestBody UpsertConditionRequest req) {
        return ApiResponse.ok(ConditionResponse.from(service.update(loanId, conditionId, req)));
    }

    @DeleteMapping("/{conditionId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID loanId,
            @PathVariable UUID conditionId) {
        service.softDelete(loanId, conditionId);
        return ResponseEntity.noContent().build();
    }
}
