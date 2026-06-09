package com.msfg.los.financials.web;

import com.msfg.los.financials.service.AssetVerificationService;
import com.msfg.los.financials.web.dto.AssetVerificationResponse;
import com.msfg.los.financials.web.dto.OrderAssetVerificationRequest;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/assets/verifications")
public class AssetVerificationController {

    private final AssetVerificationService service;

    public AssetVerificationController(AssetVerificationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AssetVerificationResponse>> order(
            @PathVariable UUID loanId,
            @Valid @RequestBody OrderAssetVerificationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(AssetVerificationResponse.from(service.order(loanId, req))));
    }

    @GetMapping
    public ApiResponse<List<AssetVerificationResponse>> list(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.list(loanId).stream().map(AssetVerificationResponse::from).toList());
    }
}
