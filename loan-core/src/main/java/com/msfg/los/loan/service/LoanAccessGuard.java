package com.msfg.los.loan.service;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.platform.error.ForbiddenException;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.security.Role;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.UUID;

@Component
public class LoanAccessGuard {

    // Back-office roles work the whole org's pipeline (spec 2026-06-11). MANAGER is a staff
    // supervisor with full org-wide loan read/write (cutover Phase 2/3 T1, mortgage-app parity).
    // PLATFORM_ADMIN is deliberately absent: platform operators administer orgs, not loan files.
    private static final Set<String> ORG_WIDE_AUTHORITIES = Set.of(
            Role.PROCESSOR.authority(), Role.UNDERWRITER.authority(),
            Role.CLOSER.authority(), Role.MANAGER.authority());

    private final CurrentUser currentUser;

    public LoanAccessGuard(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    /** True when the caller may see every loan in their org (ADMIN or a back-office role). */
    public boolean hasOrgWideView() {
        if (currentUser.isAdmin()) return true;
        return currentUser.roles().stream().anyMatch(ORG_WIDE_AUTHORITIES::contains);
    }

    public void assertCanAccess(Loan loan) {
        if (hasOrgWideView()) return;
        String me = currentUser.id().orElse(null);
        UUID meId = null;
        if (me != null) {
            try { meId = UUID.fromString(me); } catch (IllegalArgumentException ignored) {}
        }
        if (meId == null || !loan.getLoanOfficerId().equals(meId)) {
            throw new ForbiddenException("No access to loan " + loan.getLoanNumber());
        }
    }
}
