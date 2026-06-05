package com.msfg.los.income.web;

import com.msfg.los.income.service.IncomeService;
import com.msfg.los.income.web.dto.AddIncomeRequest;
import com.msfg.los.income.web.dto.IncomeItemResponse;
import com.msfg.los.income.web.dto.UpdateIncomeRequest;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/borrowers/{borrowerId}/income")
public class IncomeController {

    private final IncomeService service;

    public IncomeController(IncomeService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<IncomeItemResponse>> add(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId,
            @Valid @RequestBody AddIncomeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(IncomeItemResponse.from(service.add(loanId, borrowerId, req))));
    }

    @GetMapping
    public ApiResponse<List<IncomeItemResponse>> list(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId) {
        return ApiResponse.ok(service.list(loanId, borrowerId).stream().map(IncomeItemResponse::from).toList());
    }

    @PatchMapping("/{incomeId}")
    public ApiResponse<IncomeItemResponse> update(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId,
            @PathVariable UUID incomeId,
            @Valid @RequestBody UpdateIncomeRequest req) {
        return ApiResponse.ok(IncomeItemResponse.from(service.update(loanId, borrowerId, incomeId, req)));
    }

    @DeleteMapping("/{incomeId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId,
            @PathVariable UUID incomeId) {
        service.delete(loanId, borrowerId, incomeId);
        return ResponseEntity.noContent().build();
    }
}
