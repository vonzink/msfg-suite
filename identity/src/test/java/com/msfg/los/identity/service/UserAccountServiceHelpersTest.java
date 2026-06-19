package com.msfg.los.identity.service;

import com.msfg.los.platform.security.Role;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure-logic tests for the {@link UserAccountService} static helpers (no Spring context). */
class UserAccountServiceHelpersTest {

    @Test
    void initials_firstLetterOfEachWordUpToThreeUppercase() {
        assertThat(UserAccountService.initials("Jane Doe")).isEqualTo("JD");
        assertThat(UserAccountService.initials("jane")).isEqualTo("J");
        assertThat(UserAccountService.initials("Mary Jane Watson Smith")).isEqualTo("MJW"); // capped at 3
        assertThat(UserAccountService.initials("  multiple   spaces  ")).isEqualTo("MS");
        assertThat(UserAccountService.initials("first.last")).isEqualTo("FL"); // splits on punctuation
    }

    @Test
    void initials_blankOrNull_returnsNull() {
        assertThat(UserAccountService.initials(null)).isNull();
        assertThat(UserAccountService.initials("   ")).isNull();
    }

    @Test
    void primaryRole_picksHighestPriorityPresent() {
        // ADMIN > MANAGER > UNDERWRITER > CLOSER > PROCESSOR > LO
        assertThat(UserAccountService.primaryRole(
                Set.of(Role.LO.authority(), Role.ADMIN.authority(), Role.PROCESSOR.authority())))
                .isEqualTo("ADMIN");
        assertThat(UserAccountService.primaryRole(
                Set.of(Role.LO.authority(), Role.MANAGER.authority())))
                .isEqualTo("MANAGER");
        assertThat(UserAccountService.primaryRole(Set.of(Role.LO.authority())))
                .isEqualTo("LO");
    }

    @Test
    void primaryRole_noKnownRole_returnsNull() {
        assertThat(UserAccountService.primaryRole(Set.of())).isNull();
        assertThat(UserAccountService.primaryRole(Set.of("ROLE_PLATFORM_ADMIN"))).isNull();
        assertThat(UserAccountService.primaryRole(null)).isNull();
    }

    @Test
    void primaryRole_partyRolesRankLowestBelowLo() {
        // BORROWER and REAL_ESTATE_AGENT are the lowest priority — a party role alone resolves to
        // itself, but any staff role (down to LO) outranks them.
        assertThat(UserAccountService.primaryRole(Set.of(Role.BORROWER.authority())))
                .isEqualTo("BORROWER");
        assertThat(UserAccountService.primaryRole(Set.of(Role.REAL_ESTATE_AGENT.authority())))
                .isEqualTo("REAL_ESTATE_AGENT");
        assertThat(UserAccountService.primaryRole(
                Set.of(Role.LO.authority(), Role.BORROWER.authority())))
                .isEqualTo("LO");
        assertThat(UserAccountService.primaryRole(
                Set.of(Role.BORROWER.authority(), Role.REAL_ESTATE_AGENT.authority())))
                .isEqualTo("BORROWER"); // BORROWER ranks above REAL_ESTATE_AGENT
    }
}
