package com.msfg.los.identity.web;

import com.msfg.los.identity.domain.UserAccount;
import com.msfg.los.identity.service.UserAccountService;
import com.msfg.los.identity.web.dto.MeResponse;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanAgentService;
import com.msfg.los.loan.service.LoanLinkageResolver;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.loan.web.dto.LoanListItemResponse;
import com.msfg.los.loan.web.dto.PipelineFilter;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.security.Role;
import com.msfg.los.platform.web.ApiResponse;
import com.msfg.los.platform.web.PagedResponse;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The authenticated caller's own identity + role-scoped loan list (cutover Phase 2/3 T2).
 *
 * <p>/me/loans scope matrix (Phase F T7):
 * <ul>
 *   <li>Staff / org-wide roles → every loan in the org (unchanged)</li>
 *   <li>LO → loans owned by this LO (unchanged)</li>
 *   <li>BORROWER → loans this user is linked to as a borrower ({@link LoanLinkageResolver})</li>
 *   <li>REAL_ESTATE_AGENT → loans this user is linked to as an agent ({@link LoanAgentService})</li>
 *   <li>else → empty page</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final UserAccountService userAccounts;
    private final LoanService loans;
    private final LoanAccessGuard accessGuard;
    private final CurrentUser currentUser;
    private final LoanLinkageResolver loanLinkageResolver;
    private final LoanAgentService loanAgentService;

    public MeController(UserAccountService userAccounts, LoanService loans,
                        LoanAccessGuard accessGuard, CurrentUser currentUser,
                        LoanLinkageResolver loanLinkageResolver,
                        LoanAgentService loanAgentService) {
        this.userAccounts = userAccounts;
        this.loans = loans;
        this.accessGuard = accessGuard;
        this.currentUser = currentUser;
        this.loanLinkageResolver = loanLinkageResolver;
        this.loanAgentService = loanAgentService;
    }

    @GetMapping
    public ApiResponse<MeResponse> me() {
        UserAccount u = userAccounts.resolveOrCreate();
        return ApiResponse.ok(MeResponse.from(u, new ArrayList<>(currentUser.roles())));
    }

    @GetMapping("/loans")
    public ApiResponse<PagedResponse<LoanListItemResponse>> myLoans(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID me = currentUser.id().map(id -> {
            try { return UUID.fromString(id); } catch (IllegalArgumentException e) { return null; }
        }).orElse(null);

        // Branch 1 — org-wide roles (ADMIN/MANAGER/PROCESSOR/UNDERWRITER/CLOSER) see all loans.
        // Branch 2 — LO sees only loans where loanOfficerId == their sub.
        // Both reuse the existing pipeline() call (unchanged from Phase 2/3 T2).
        if (accessGuard.hasOrgWideView() || currentUser.roles().contains(Role.LO.authority())) {
            PipelineFilter noFilter = new PipelineFilter(
                    null, null, null, null, null, null, null, null, null);
            Page<LoanListItemResponse> result =
                    loans.pipeline(noFilter, null, accessGuard.hasOrgWideView(), me, page, size);
            return ApiResponse.ok(PagedResponse.from(result));
        }

        // Branch 3 — BORROWER: loans this user is linked to as a borrower.
        if (currentUser.roles().contains(Role.BORROWER.authority())) {
            List<UUID> linkedIds = me != null
                    ? loanLinkageResolver.loanIdsForBorrower(me)
                    : List.of();
            Page<LoanListItemResponse> result = loans.pipelineByIds(linkedIds, null, page, size);
            return ApiResponse.ok(PagedResponse.from(result));
        }

        // Branch 4 — REAL_ESTATE_AGENT: loans this user is linked to as an agent.
        if (currentUser.roles().contains(Role.REAL_ESTATE_AGENT.authority())) {
            List<UUID> linkedIds = me != null
                    ? loanAgentService.loanIdsForAgent(me)
                    : List.of();
            Page<LoanListItemResponse> result = loans.pipelineByIds(linkedIds, null, page, size);
            return ApiResponse.ok(PagedResponse.from(result));
        }

        // Branch 5 — unknown/unrecognised role: empty page (safe default).
        Page<LoanListItemResponse> empty = loans.pipelineByIds(List.of(), null, page, size);
        return ApiResponse.ok(PagedResponse.from(empty));
    }
}
