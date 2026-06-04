package com.msfg.los.loan.domain;

import com.msfg.los.platform.error.ConflictException;
import com.msfg.los.platform.error.ForbiddenException;
import com.msfg.los.platform.security.Role;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static com.msfg.los.loan.domain.LoanStatus.*;

class LoanLifecycleTest {
    private final LoanLifecycle lifecycle = new LoanLifecycle();

    @Test void allowsLegalForwardMove() {
        assertThatCode(() -> lifecycle.assertTransition(STARTED, APPLICATION_IN_PROGRESS, Set.of(Role.LO.authority())))
            .doesNotThrowAnyException();
    }
    @Test void rejectsIllegalMove() {
        assertThatThrownBy(() -> lifecycle.assertTransition(STARTED, FUNDED, Set.of(Role.ADMIN.authority())))
            .isInstanceOf(ConflictException.class);
    }
    @Test void rejectsMoveFromTerminalState() {
        assertThatThrownBy(() -> lifecycle.assertTransition(FUNDED, CLOSING, Set.of(Role.ADMIN.authority())))
            .isInstanceOf(ConflictException.class);
    }
    @Test void enforcesRoleGate_underwriterOnlyApproves() {
        assertThatThrownBy(() -> lifecycle.assertTransition(IN_UNDERWRITING, APPROVED_WITH_CONDITIONS, Set.of(Role.LO.authority())))
            .isInstanceOf(ForbiddenException.class);
        assertThatCode(() -> lifecycle.assertTransition(IN_UNDERWRITING, APPROVED_WITH_CONDITIONS, Set.of(Role.UNDERWRITER.authority())))
            .doesNotThrowAnyException();
    }
    @Test void anyActiveStateCanWithdraw() {
        assertThatCode(() -> lifecycle.assertTransition(SUBMITTED, WITHDRAWN, Set.of(Role.LO.authority())))
            .doesNotThrowAnyException();
    }
    @Test void adminBypassesRoleGate() {
        assertThatCode(() -> lifecycle.assertTransition(IN_UNDERWRITING, APPROVED_WITH_CONDITIONS, Set.of(Role.ADMIN.authority())))
            .doesNotThrowAnyException();
    }
    @Test void suspendedCanWithdrawButNotResume() {
        assertThatCode(() -> lifecycle.assertTransition(SUSPENDED, WITHDRAWN, Set.of(Role.LO.authority())))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> lifecycle.assertTransition(SUSPENDED, IN_UNDERWRITING, Set.of(Role.ADMIN.authority())))
            .isInstanceOf(ConflictException.class);
    }
}
