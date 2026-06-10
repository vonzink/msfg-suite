package com.msfg.los.loan.web;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.domain.LoanLifecycle;
import com.msfg.los.loan.domain.LoanStatus;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.loan.web.dto.*;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.web.ApiResponse;
import com.msfg.los.platform.web.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private final LoanService service;
    private final LoanAccessGuard accessGuard;
    private final CurrentUser currentUser;
    private final LoanLifecycle lifecycle;

    public LoanController(LoanService service, LoanAccessGuard accessGuard,
                          CurrentUser currentUser, LoanLifecycle lifecycle) {
        this.service = service;
        this.accessGuard = accessGuard;
        this.currentUser = currentUser;
        this.lifecycle = lifecycle;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<LoanSummaryResponse>> create(@Valid @RequestBody CreateLoanRequest req) {
        Loan loan = service.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(LoanSummaryResponse.from(loan)));
    }

    @GetMapping
    public ApiResponse<PagedResponse<LoanListItemResponse>> pipeline(
            @RequestParam(required = false) LoanStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID me = currentUser.id().map(id -> {
            try { return UUID.fromString(id); } catch (IllegalArgumentException e) { return null; }
        }).orElse(null);
        Page<Loan> result = service.pipeline(me, status, currentUser.isAdmin(), PageRequest.of(page, size));
        return ApiResponse.ok(PagedResponse.from(result.map(LoanListItemResponse::from)));
    }

    @GetMapping("/{id}")
    public ApiResponse<LoanSummaryResponse> get(@PathVariable UUID id) {
        Loan loan = service.get(id);
        accessGuard.assertCanAccess(loan);
        return ApiResponse.ok(LoanSummaryResponse.from(loan));
    }

    @PatchMapping("/{id}")
    public ApiResponse<LoanSummaryResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateLoanRequest req) {
        accessGuard.assertCanAccess(service.get(id));
        return ApiResponse.ok(LoanSummaryResponse.from(service.update(id, req)));
    }

    @GetMapping("/{id}/status/transitions")
    public ApiResponse<TransitionsResponse> transitions(@PathVariable UUID id) {
        Loan loan = service.get(id);
        accessGuard.assertCanAccess(loan);
        return ApiResponse.ok(new TransitionsResponse(loan.getStatus(),
            lifecycle.allowedTransitions(loan.getStatus(), currentUser.roles())));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<LoanSummaryResponse> transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest req) {
        Loan loan = service.get(id);
        accessGuard.assertCanAccess(loan);
        Loan updated = service.transition(id, req, currentUser.roles());
        return ApiResponse.ok(LoanSummaryResponse.from(updated));
    }
}
