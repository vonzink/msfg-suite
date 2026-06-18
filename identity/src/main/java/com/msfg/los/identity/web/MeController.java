package com.msfg.los.identity.web;

import com.msfg.los.identity.domain.UserAccount;
import com.msfg.los.identity.service.UserAccountService;
import com.msfg.los.identity.web.dto.MeResponse;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.loan.web.dto.LoanListItemResponse;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.web.ApiResponse;
import com.msfg.los.platform.web.PagedResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.UUID;

/** The authenticated caller's own identity + role-scoped loan list (cutover Phase 2/3 T2). */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final UserAccountService userAccounts;
    private final LoanService loans;
    private final LoanAccessGuard accessGuard;
    private final CurrentUser currentUser;

    public MeController(UserAccountService userAccounts, LoanService loans,
                        LoanAccessGuard accessGuard, CurrentUser currentUser) {
        this.userAccounts = userAccounts;
        this.loans = loans;
        this.accessGuard = accessGuard;
        this.currentUser = currentUser;
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
        // Org-wide roles (ADMIN/MANAGER/PROCESSOR/UNDERWRITER/CLOSER) see every loan; an LO sees
        // only loans where loanOfficerId == their sub. Reuses LoanService's role-scoped pipeline.
        Page<LoanListItemResponse> result =
                loans.pipeline(me, null, accessGuard.hasOrgWideView(), PageRequest.of(page, size));
        return ApiResponse.ok(PagedResponse.from(result));
    }
}
