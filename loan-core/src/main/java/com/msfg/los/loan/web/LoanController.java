package com.msfg.los.loan.web;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.domain.LoanLifecycle;
import com.msfg.los.loan.domain.LoanStatus;
import com.msfg.los.loan.domain.MortgageType;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.loan.service.PipelineSort;
import com.msfg.los.loan.web.dto.*;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.web.ApiResponse;
import com.msfg.los.platform.web.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    /**
     * Pipeline list with the full filter set (Phase 2 T4). All params optional except paging. Filtering
     * + sorting happen query-side via a JPA {@code Specification} (see {@code LoanSpecifications}). The
     * single-value {@code status} legacy behavior is preserved (a one-element list). Caller scope:
     * org-wide-view roles see all org loans; an LO sees only loans they own. Default ordering is
     * newest-first (createdAt DESC + id tiebreaker) when no {@code sort} is supplied.
     *
     * @param status      status in (...). Repeat the param for multiple values.
     * @param lo          assigned loan-officer id.
     * @param conditionsGt keep loans with strictly more than N outstanding conditions.
     * @param closingFrom consummationDate on or after this date.
     * @param closingTo   consummationDate on or before this date.
     * @param stageAgeGt  days; keep loans whose status changed more than N days ago.
     * @param loanType    mortgageType in (...). Repeat the param for multiple values.
     * @param amountMin   primary loan amount (baseLoanAmount, falling back to noteAmount) >= this.
     * @param amountMax   primary loan amount <= this.
     * @param sort        {@code field,dir} — whitelist createdAt|statusChangedAt|amount, dir asc|desc.
     */
    @GetMapping
    public ApiResponse<PagedResponse<LoanListItemResponse>> pipeline(
            @RequestParam(required = false) List<LoanStatus> status,
            @RequestParam(required = false) UUID lo,
            @RequestParam(required = false) Integer conditionsGt,
            @RequestParam(required = false) LocalDate closingFrom,
            @RequestParam(required = false) LocalDate closingTo,
            @RequestParam(required = false) Integer stageAgeGt,
            @RequestParam(required = false) List<MortgageType> loanType,
            @RequestParam(required = false) BigDecimal amountMin,
            @RequestParam(required = false) BigDecimal amountMax,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID me = currentUser.id().map(id -> {
            try { return UUID.fromString(id); } catch (IllegalArgumentException e) { return null; }
        }).orElse(null);
        PipelineFilter filter = new PipelineFilter(
                status, lo, conditionsGt, closingFrom, closingTo, stageAgeGt, loanType, amountMin, amountMax);
        Page<LoanListItemResponse> result = service.pipeline(
                filter, PipelineSort.parse(sort), accessGuard.hasOrgWideView(), me, page, size);
        return ApiResponse.ok(PagedResponse.from(result));
    }

    @GetMapping("/{id}")
    public ApiResponse<LoanSummaryResponse> get(@PathVariable UUID id) {
        Loan loan = service.get(id);
        accessGuard.assertCanAccess(loan);
        return ApiResponse.ok(LoanSummaryResponse.from(loan));
    }

    /** Lookup by human loan number (Phase 2 T3). Org-scoped + not-deleted; access-guarded. */
    @GetMapping("/number/{loanNumber}")
    public ApiResponse<LoanSummaryResponse> getByNumber(@PathVariable String loanNumber) {
        Loan loan = service.getByNumber(loanNumber);
        accessGuard.assertCanAccess(loan);
        return ApiResponse.ok(LoanSummaryResponse.from(loan));
    }

    /** Typeahead search (Phase 2 T3): q (min len 2 else empty), limit default 10, cap 50. Caller-scoped. */
    @GetMapping("/search")
    public ApiResponse<List<LoanSearchHit>> search(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "10") int limit) {
        int capped = Math.min(Math.max(limit, 1), 50);
        UUID me = currentUser.id().map(id -> {
            try { return UUID.fromString(id); } catch (IllegalArgumentException e) { return null; }
        }).orElse(null);
        return ApiResponse.ok(service.search(q, capped, accessGuard.hasOrgWideView(), me));
    }

    /** Soft-delete (Phase 2 T3). Gated to LO/MANAGER/ADMIN in SecurityConfig; LO must own the loan. */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        Loan loan = service.get(id);     // 404 if missing or already soft-deleted
        accessGuard.assertCanAccess(loan);   // owning LO (org-wide roles pass)
        service.softDelete(id);
        return ApiResponse.ok(null);
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
