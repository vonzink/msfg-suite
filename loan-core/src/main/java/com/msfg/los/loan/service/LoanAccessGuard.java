package com.msfg.los.loan.service;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.platform.error.ForbiddenException;
import com.msfg.los.platform.security.CurrentUser;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class LoanAccessGuard {
    private final CurrentUser currentUser;

    public LoanAccessGuard(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    public void assertCanAccess(Loan loan) {
        if (currentUser.isAdmin()) return;
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
