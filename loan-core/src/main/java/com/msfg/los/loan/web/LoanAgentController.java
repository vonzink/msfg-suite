package com.msfg.los.loan.web;

import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanAgentService;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.loan.web.dto.AssignAgentRequest;
import com.msfg.los.loan.web.dto.LoanAgentResponse;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Staff-only agent assignment roster for a loan (T8).
 *
 * <p>All paths are under {@code /api/loans/{loanId}/agents} (2+ segments). The SecurityConfig
 * filter-layer catch-all already denies BORROWER/REAL_ESTATE_AGENT/PLATFORM_ADMIN tokens before
 * requests reach this controller.
 *
 * <p>Access control is done here (not in the service) to avoid the circular dependency between
 * {@link LoanAccessGuard} and {@link LoanAgentService}:
 * <ul>
 *   <li>Writes call {@link LoanAccessGuard#assertCanModify} (staff-or-owning-LO defence-in-depth).</li>
 *   <li>The list read calls {@link LoanAccessGuard#assertCanAccess} (same semantics).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/loans/{loanId}/agents")
public class LoanAgentController {

    private final LoanAgentService service;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;

    public LoanAgentController(LoanAgentService service,
                               LoanService loanService,
                               LoanAccessGuard accessGuard) {
        this.service = service;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
    }

    /** Assign a user as an agent on the loan. Duplicate (org, loan, user) → 409. */
    @PostMapping
    public ResponseEntity<ApiResponse<LoanAgentResponse>> assign(
            @PathVariable UUID loanId,
            @Valid @RequestBody AssignAgentRequest req) {
        accessGuard.assertCanModify(loanService.get(loanId));
        LoanAgentResponse body = LoanAgentResponse.from(
                service.assign(loanId, req.userId(), req.agentRole()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(body));
    }

    /** List the loan's agent roster (staff only). */
    @GetMapping
    public ApiResponse<List<LoanAgentResponse>> list(@PathVariable UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return ApiResponse.ok(
                service.list(loanId).stream().map(LoanAgentResponse::from).toList());
    }

    /** Unassign an agent from the loan (204). */
    @DeleteMapping("/{agentId}")
    public ResponseEntity<Void> remove(
            @PathVariable UUID loanId,
            @PathVariable UUID agentId) {
        accessGuard.assertCanModify(loanService.get(loanId));
        service.remove(loanId, agentId);
        return ResponseEntity.noContent().build();
    }
}
