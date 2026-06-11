package com.msfg.los.loan.service;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.platform.error.ForbiddenException;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.security.Role;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanAccessGuardTest {

    private static final UUID OWNER = UUID.randomUUID();

    private Loan loanOwnedByOwner() {
        Loan l = new Loan();
        l.setLoanOfficerId(OWNER);
        l.setLoanNumber("LN-TEST");
        return l;
    }

    private CurrentUser userWith(String subject, String... authorities) {
        return new CurrentUser() {
            @Override public Optional<String> id() { return Optional.ofNullable(subject); }
            @Override public Set<String> roles() { return Set.of(authorities); }
        };
    }

    private void assertAllowed(CurrentUser u) {
        assertThatCode(() -> new LoanAccessGuard(u).assertCanAccess(loanOwnedByOwner()))
                .doesNotThrowAnyException();
    }

    private void assertDenied(CurrentUser u) {
        assertThatThrownBy(() -> new LoanAccessGuard(u).assertCanAccess(loanOwnedByOwner()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test void owningLoAllowed()      { assertAllowed(userWith(OWNER.toString(), Role.LO.authority())); }
    @Test void otherLoDenied()        { assertDenied(userWith(UUID.randomUUID().toString(), Role.LO.authority())); }
    @Test void adminAllowed()         { assertAllowed(userWith(UUID.randomUUID().toString(), Role.ADMIN.authority())); }
    @Test void processorAllowed()     { assertAllowed(userWith(UUID.randomUUID().toString(), Role.PROCESSOR.authority())); }
    @Test void underwriterAllowed()   { assertAllowed(userWith(UUID.randomUUID().toString(), Role.UNDERWRITER.authority())); }
    @Test void closerAllowed()        { assertAllowed(userWith(UUID.randomUUID().toString(), Role.CLOSER.authority())); }
    @Test void platformAdminDenied()  { assertDenied(userWith(UUID.randomUUID().toString(), Role.PLATFORM_ADMIN.authority())); }
    @Test void noSubjectDenied()      { assertDenied(userWith(null, Role.LO.authority())); }
    @Test void garbledSubjectDenied() { assertDenied(userWith("not-a-uuid", Role.LO.authority())); }
}
