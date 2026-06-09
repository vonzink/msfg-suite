package com.msfg.los.financials.web;

import com.msfg.los.financials.service.AssetService;
import com.msfg.los.financials.web.dto.AddAssetRequest;
import com.msfg.los.financials.web.dto.AssetResponse;
import com.msfg.los.financials.web.dto.UpdateAssetRequest;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/borrowers/{borrowerId}/assets")
public class AssetController {

    private final AssetService service;

    public AssetController(AssetService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AssetResponse>> add(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId,
            @Valid @RequestBody AddAssetRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(AssetResponse.from(service.add(loanId, borrowerId, req))));
    }

    @GetMapping
    public ApiResponse<List<AssetResponse>> list(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId) {
        return ApiResponse.ok(service.list(loanId, borrowerId).stream().map(AssetResponse::from).toList());
    }

    @PatchMapping("/{assetId}")
    public ApiResponse<AssetResponse> update(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId,
            @PathVariable UUID assetId,
            @Valid @RequestBody UpdateAssetRequest req) {
        return ApiResponse.ok(AssetResponse.from(service.update(loanId, borrowerId, assetId, req)));
    }

    @DeleteMapping("/{assetId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId,
            @PathVariable UUID assetId) {
        service.delete(loanId, borrowerId, assetId);
        return ResponseEntity.noContent().build();
    }
}
