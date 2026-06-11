package com.msfg.los.fees.web;

import com.msfg.los.fees.service.FeeService;
import com.msfg.los.fees.service.FeeTotalsService;
import com.msfg.los.fees.web.dto.AddFeeRequest;
import com.msfg.los.fees.web.dto.FeeLineItemResponse;
import com.msfg.los.fees.web.dto.FeeTotalsResponse;
import com.msfg.los.fees.web.dto.UpdateFeeRequest;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/fees")
public class FeeController {

    private final FeeService service;
    private final FeeTotalsService totalsService;

    public FeeController(FeeService service, FeeTotalsService totalsService) {
        this.service = service;
        this.totalsService = totalsService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<FeeLineItemResponse>> add(
            @PathVariable UUID loanId,
            @Valid @RequestBody AddFeeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(FeeLineItemResponse.from(service.add(loanId, req))));
    }

    @GetMapping
    public ApiResponse<List<FeeLineItemResponse>> list(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.list(loanId).stream().map(FeeLineItemResponse::from).toList());
    }

    @GetMapping("/totals")
    public ApiResponse<FeeTotalsResponse> totals(@PathVariable UUID loanId) {
        return ApiResponse.ok(totalsService.totals(loanId));
    }

    @PatchMapping("/{feeId}")
    public ApiResponse<FeeLineItemResponse> update(
            @PathVariable UUID loanId,
            @PathVariable UUID feeId,
            @RequestBody UpdateFeeRequest req) {
        return ApiResponse.ok(FeeLineItemResponse.from(service.update(loanId, feeId, req)));
    }

    @DeleteMapping("/{feeId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID loanId,
            @PathVariable UUID feeId) {
        service.delete(loanId, feeId);
        return ResponseEntity.noContent().build();
    }
}
