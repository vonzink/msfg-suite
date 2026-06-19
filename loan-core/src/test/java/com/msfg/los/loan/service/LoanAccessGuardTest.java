package com.msfg.los.loan.service;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.platform.error.ForbiddenException;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanAccessGuardTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID LOAN_ID = UUID.randomUUID();

    private Loan loanOwnedByOwner() {
        Loan l = new Loan();
        l.setLoanOfficerId(OWNER);
        l.setLoanNumber("LN-TEST");
        return l;
    }

    private CurrentUser userWith(String subject, String... authorities) {
        return new CurrentUser(new com.msfg.los.platform.security.PrincipalPort() {
            @Override public Optional<String> id() { return Optional.ofNullable(subject); }
            @Override public Optional<String> email() { return Optional.empty(); }
            @Override public Optional<String> name() { return Optional.empty(); }
            @Override public Optional<UUID> orgId() { return Optional.empty(); }
            @Override public Set<String> roles() { return Set.of(authorities); }
            @Override public boolean emailVerified() { return false; }
        });
    }

    /** Builds a guard whose linkage collaborators report no borrower/agent linkage by default. */
    private LoanAccessGuard guard(CurrentUser u) {
        return guard(u, null, null);
    }

    /**
     * Builds a guard where {@code borrowerLinkedSub}/{@code agentLinkedSub} (if non-null) are the
     * only subjects treated as linked to {@link #LOAN_ID}.
     */
    private LoanAccessGuard guard(CurrentUser u, UUID borrowerLinkedSub, UUID agentLinkedSub) {
        return guard(u, borrowerLinkedSub, agentLinkedSub, null, null);
    }

    /**
     * Builds a guard. In addition to the loan-level linkage stubs, {@code selfBorrowerId}/
     * {@code selfSub} (if both non-null) define the ONE {@code (borrowerId, userId)} pair that
     * {@link LoanLinkageResolver#isBorrowerSelf} reports as a self-match — driving
     * {@code assertBorrowerSelfReadable}.
     */
    private LoanAccessGuard guard(CurrentUser u, UUID borrowerLinkedSub, UUID agentLinkedSub,
                                  UUID selfBorrowerId, UUID selfSub) {
        LoanLinkageResolver borrowerLinker = new LoanLinkageResolver() {
            @Override public boolean isBorrowerOnLoan(UUID loanId, UUID userId) {
                return borrowerLinkedSub != null && borrowerLinkedSub.equals(userId) && LOAN_ID.equals(loanId);
            }
            @Override public List<UUID> loanIdsForBorrower(UUID userId) { return List.of(); }
            @Override public boolean isBorrowerSelf(UUID borrowerId, UUID userId) {
                return selfBorrowerId != null && selfSub != null
                        && selfBorrowerId.equals(borrowerId) && selfSub.equals(userId);
            }
        };
        LoanAgentService agentService = new LoanAgentService(null, null, null) {
            @Override public boolean isAgentOnLoan(UUID loanId, UUID userId) {
                return agentLinkedSub != null && agentLinkedSub.equals(userId) && LOAN_ID.equals(loanId);
            }
        };
        return new LoanAccessGuard(u, agentService, borrowerLinker);
    }

    private Loan loanWithId() {
        return withId(loanOwnedByOwner(), LOAN_ID);
    }

    /** BaseEntity.id has no setter (JPA-managed) — set it reflectively for these pure-unit tests. */
    private static Loan withId(Loan l, UUID id) {
        ReflectionTestUtils.setField(l, "id", id);
        return l;
    }

    // ── assertCanAccess (staff-or-owning-LO) ─────────────────────────────────────

    private void assertAllowed(CurrentUser u) {
        assertThatCode(() -> guard(u).assertCanAccess(loanOwnedByOwner())).doesNotThrowAnyException();
    }

    private void assertDenied(CurrentUser u) {
        assertThatThrownBy(() -> guard(u).assertCanAccess(loanOwnedByOwner()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test void owningLoAllowed()      { assertAllowed(userWith(OWNER.toString(), Role.LO.authority())); }
    @Test void otherLoDenied()        { assertDenied(userWith(UUID.randomUUID().toString(), Role.LO.authority())); }
    @Test void managerAllowed()       { assertAllowed(userWith(UUID.randomUUID().toString(), Role.MANAGER.authority())); }
    @Test void adminAllowed()         { assertAllowed(userWith(UUID.randomUUID().toString(), Role.ADMIN.authority())); }
    @Test void processorAllowed()     { assertAllowed(userWith(UUID.randomUUID().toString(), Role.PROCESSOR.authority())); }
    @Test void underwriterAllowed()   { assertAllowed(userWith(UUID.randomUUID().toString(), Role.UNDERWRITER.authority())); }
    @Test void closerAllowed()        { assertAllowed(userWith(UUID.randomUUID().toString(), Role.CLOSER.authority())); }
    @Test void platformAdminDenied()  { assertDenied(userWith(UUID.randomUUID().toString(), Role.PLATFORM_ADMIN.authority())); }
    @Test void noSubjectDenied()      { assertDenied(userWith(null, Role.LO.authority())); }
    @Test void garbledSubjectDenied() { assertDenied(userWith("not-a-uuid", Role.LO.authority())); }

    // LO hardening: the owning-LO branch requires the ROLE_LO authority too — a BORROWER (or any
    // non-LO) whose sub happens to equal loanOfficerId must NOT be treated as the LO.
    @Test void borrowerWithLoSubDeniedAccess() {
        assertDenied(userWith(OWNER.toString(), Role.BORROWER.authority()));
    }
    @Test void agentWithLoSubDeniedAccess() {
        assertDenied(userWith(OWNER.toString(), Role.REAL_ESTATE_AGENT.authority()));
    }
    // assertCanModify mirrors assertCanAccess.
    @Test void borrowerCannotModifyEvenWithLoSub() {
        assertThatThrownBy(() -> guard(userWith(OWNER.toString(), Role.BORROWER.authority()))
                .assertCanModify(loanOwnedByOwner())).isInstanceOf(ForbiddenException.class);
    }
    @Test void owningLoCanModify() {
        assertThatCode(() -> guard(userWith(OWNER.toString(), Role.LO.authority()))
                .assertCanModify(loanOwnedByOwner())).doesNotThrowAnyException();
    }

    // ── assertReadable (broader read predicate) ──────────────────────────────────

    @Test void readableForOwningLo() {
        assertThatCode(() -> guard(userWith(OWNER.toString(), Role.LO.authority()))
                .assertReadable(loanWithId())).doesNotThrowAnyException();
    }
    @Test void readableForOrgWideStaff() {
        assertThatCode(() -> guard(userWith(UUID.randomUUID().toString(), Role.PROCESSOR.authority()))
                .assertReadable(loanWithId())).doesNotThrowAnyException();
    }
    @Test void readableForLinkedBorrower() {
        UUID sub = UUID.randomUUID();
        assertThatCode(() -> guard(userWith(sub.toString(), Role.BORROWER.authority()), sub, null)
                .assertReadable(loanWithId())).doesNotThrowAnyException();
    }
    @Test void readableForLinkedAgent() {
        UUID sub = UUID.randomUUID();
        assertThatCode(() -> guard(userWith(sub.toString(), Role.REAL_ESTATE_AGENT.authority()), null, sub)
                .assertReadable(loanWithId())).doesNotThrowAnyException();
    }
    @Test void unlinkedBorrowerNotReadable() {
        UUID sub = UUID.randomUUID();
        assertThatThrownBy(() -> guard(userWith(sub.toString(), Role.BORROWER.authority()), null, null)
                .assertReadable(loanWithId())).isInstanceOf(ForbiddenException.class);
    }
    @Test void unlinkedAgentNotReadable() {
        UUID sub = UUID.randomUUID();
        assertThatThrownBy(() -> guard(userWith(sub.toString(), Role.REAL_ESTATE_AGENT.authority()), null, null)
                .assertReadable(loanWithId())).isInstanceOf(ForbiddenException.class);
    }
    // A borrower linked to SOME loan but reading a different loan is denied (linkage is per-loan).
    @Test void borrowerLinkedToAnotherLoanNotReadable() {
        UUID sub = UUID.randomUUID();
        Loan other = withId(loanOwnedByOwner(), UUID.randomUUID());   // not LOAN_ID → stub linker false
        assertThatThrownBy(() -> guard(userWith(sub.toString(), Role.BORROWER.authority()), sub, null)
                .assertReadable(other)).isInstanceOf(ForbiddenException.class);
    }
    // Role gate: a borrower-linked sub presenting an AGENT token (or vice versa) does not cross over.
    @Test void agentRoleDoesNotMatchBorrowerLinkage() {
        UUID sub = UUID.randomUUID();
        assertThatThrownBy(() -> guard(userWith(sub.toString(), Role.REAL_ESTATE_AGENT.authority()), sub, null)
                .assertReadable(loanWithId())).isInstanceOf(ForbiddenException.class);
    }
    @Test void platformAdminNotReadable() {
        assertThatThrownBy(() -> guard(userWith(UUID.randomUUID().toString(), Role.PLATFORM_ADMIN.authority()))
                .assertReadable(loanWithId())).isInstanceOf(ForbiddenException.class);
    }

    // ── assertBorrowerSelfReadable (per-borrower own-data read; T11) ───────────────
    // Passes for staff-or-owning-LO (unchanged), OR a ROLE_BORROWER whose sub is the SAME borrower
    // row being read. A co-borrower's borrowerId, an agent, and PLATFORM_ADMIN never pass.

    private static final UUID BORROWER_ID = UUID.randomUUID();

    @Test void selfReadableForOwningLo() {
        assertThatCode(() -> guard(userWith(OWNER.toString(), Role.LO.authority()))
                .assertBorrowerSelfReadable(loanWithId(), BORROWER_ID)).doesNotThrowAnyException();
    }
    @Test void selfReadableForOrgWideStaff() {
        assertThatCode(() -> guard(userWith(UUID.randomUUID().toString(), Role.PROCESSOR.authority()))
                .assertBorrowerSelfReadable(loanWithId(), BORROWER_ID)).doesNotThrowAnyException();
    }
    @Test void selfReadableForBorrowerReadingOwnRow() {
        UUID sub = UUID.randomUUID();
        assertThatCode(() -> guard(userWith(sub.toString(), Role.BORROWER.authority()), null, null, BORROWER_ID, sub)
                .assertBorrowerSelfReadable(loanWithId(), BORROWER_ID)).doesNotThrowAnyException();
    }
    // A borrower reading a CO-BORROWER's borrowerId is denied — self-match is per borrower row.
    @Test void selfNotReadableForCoBorrowerRow() {
        UUID sub = UUID.randomUUID();
        UUID coBorrowerId = UUID.randomUUID();
        assertThatThrownBy(() -> guard(userWith(sub.toString(), Role.BORROWER.authority()), null, null, BORROWER_ID, sub)
                .assertBorrowerSelfReadable(loanWithId(), coBorrowerId)).isInstanceOf(ForbiddenException.class);
    }
    // An unlinked / non-self borrower token is denied even on a real borrowerId.
    @Test void selfNotReadableForUnlinkedBorrower() {
        UUID sub = UUID.randomUUID();
        assertThatThrownBy(() -> guard(userWith(sub.toString(), Role.BORROWER.authority()), null, null, null, null)
                .assertBorrowerSelfReadable(loanWithId(), BORROWER_ID)).isInstanceOf(ForbiddenException.class);
    }
    // An agent never reaches per-borrower data, even self-matched on the same id (role gate).
    @Test void selfNotReadableForAgent() {
        UUID sub = UUID.randomUUID();
        assertThatThrownBy(() -> guard(userWith(sub.toString(), Role.REAL_ESTATE_AGENT.authority()), null, sub, BORROWER_ID, sub)
                .assertBorrowerSelfReadable(loanWithId(), BORROWER_ID)).isInstanceOf(ForbiddenException.class);
    }
    @Test void selfNotReadableForPlatformAdmin() {
        assertThatThrownBy(() -> guard(userWith(UUID.randomUUID().toString(), Role.PLATFORM_ADMIN.authority()))
                .assertBorrowerSelfReadable(loanWithId(), BORROWER_ID)).isInstanceOf(ForbiddenException.class);
    }
}
