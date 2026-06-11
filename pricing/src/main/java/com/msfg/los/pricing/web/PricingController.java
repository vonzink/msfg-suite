package com.msfg.los.pricing.web;

import com.msfg.los.platform.web.ApiResponse;
import com.msfg.los.pricing.service.PricingService;
import com.msfg.los.pricing.web.dto.ExtendLockRequest;
import com.msfg.los.pricing.web.dto.LockEventResponse;
import com.msfg.los.pricing.web.dto.LockTermsRequest;
import com.msfg.los.pricing.web.dto.PricingAdjustmentResponse;
import com.msfg.los.pricing.web.dto.PricingResponse;
import com.msfg.los.pricing.web.dto.RateChangeRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/pricing")
public class PricingController {

    private final PricingService service;

    public PricingController(PricingService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PricingResponse> pricing(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.view(loanId));
    }

    @GetMapping("/adjustments")
    public ApiResponse<List<PricingAdjustmentResponse>> adjustments(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.adjustments(loanId).stream()
                .map(PricingAdjustmentResponse::from).toList());
    }

    @GetMapping("/lock/history")
    public ApiResponse<List<LockEventResponse>> lockHistory(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.history(loanId).stream()
                .map(LockEventResponse::from).toList());
    }

    @PostMapping("/lock/control-your-price")
    public ApiResponse<PricingResponse> controlYourPrice(@PathVariable UUID loanId,
                                                         @Valid @RequestBody LockTermsRequest req) {
        return ApiResponse.ok(service.controlYourPrice(loanId, req));
    }

    @PostMapping("/lock/extend")
    public ApiResponse<PricingResponse> extend(@PathVariable UUID loanId,
                                               @Valid @RequestBody ExtendLockRequest req) {
        return ApiResponse.ok(service.extend(loanId, req));
    }

    @PostMapping("/lock/rate-change")
    public ApiResponse<PricingResponse> rateChange(@PathVariable UUID loanId,
                                                   @Valid @RequestBody RateChangeRequest req) {
        return ApiResponse.ok(service.rateChange(loanId, req));
    }
}
