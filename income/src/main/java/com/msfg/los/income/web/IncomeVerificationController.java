package com.msfg.los.income.web;

import com.msfg.los.income.service.IncomeVerificationService;
import com.msfg.los.income.web.dto.IncomeVerificationResponse;
import com.msfg.los.income.web.dto.OrderVerificationRequest;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/income/verifications")
public class IncomeVerificationController {

    private final IncomeVerificationService service;

    public IncomeVerificationController(IncomeVerificationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<IncomeVerificationResponse>> order(
            @PathVariable UUID loanId,
            @Valid @RequestBody OrderVerificationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(IncomeVerificationResponse.from(service.order(loanId, req))));
    }

    @GetMapping
    public ApiResponse<List<IncomeVerificationResponse>> list(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.list(loanId).stream().map(IncomeVerificationResponse::from).toList());
    }
}
