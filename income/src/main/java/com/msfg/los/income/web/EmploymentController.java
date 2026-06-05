package com.msfg.los.income.web;

import com.msfg.los.income.service.EmploymentService;
import com.msfg.los.income.web.dto.AddEmploymentRequest;
import com.msfg.los.income.web.dto.EmploymentResponse;
import com.msfg.los.income.web.dto.UpdateEmploymentRequest;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/borrowers/{borrowerId}/employments")
public class EmploymentController {

    private final EmploymentService service;

    public EmploymentController(EmploymentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<EmploymentResponse>> add(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId,
            @Valid @RequestBody AddEmploymentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(EmploymentResponse.from(service.add(loanId, borrowerId, req))));
    }

    @GetMapping
    public ApiResponse<List<EmploymentResponse>> list(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId) {
        return ApiResponse.ok(service.list(loanId, borrowerId).stream().map(EmploymentResponse::from).toList());
    }

    @PatchMapping("/{employmentId}")
    public ApiResponse<EmploymentResponse> update(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId,
            @PathVariable UUID employmentId,
            @Valid @RequestBody UpdateEmploymentRequest req) {
        return ApiResponse.ok(EmploymentResponse.from(service.update(loanId, borrowerId, employmentId, req)));
    }

    @DeleteMapping("/{employmentId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId,
            @PathVariable UUID employmentId) {
        service.delete(loanId, borrowerId, employmentId);
        return ResponseEntity.noContent().build();
    }
}
