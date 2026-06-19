package com.msfg.los.loan.service;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.platform.error.ForbiddenException;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.security.Role;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.UUID;

/**
 * Single chokepoint for loan-scoped access. Two distinct predicates with deliberately different
 * blast radius:
 *
 * <ul>
 *   <li>{@link #assertCanAccess(Loan)} / {@link #assertCanModify(Loan)} — staff-or-owning-LO.
 *       This is the gate on ~40 read AND write call sites (NPI reveal, borrower add/update/delete,
 *       conditions, notes, documents, status transitions, …). It is intentionally NOT widened for
 *       borrowers/agents: a party token denied here stays denied everywhere.</li>
 *   <li>{@link #assertReadable(Loan)} — a strictly larger READ predicate that ALSO admits a linked
 *       borrower or a linked agent on THIS loan. Wired only to the borrower/agent read allowlist
 *       (loan summary + status transitions). Never admits writes or NPI.</li>
 * </ul>
 *
 * <p>PLATFORM_ADMIN is deliberately absent from every branch: platform operators administer orgs,
 * not loan files.
 */
@Component
public class LoanAccessGuard {

    // Back-office roles work the whole org's pipeline (spec 2026-06-11). MANAGER is a staff
    // supervisor with full org-wide loan read/write (cutover Phase 2/3 T1, mortgage-app parity).
    // PLATFORM_ADMIN is deliberately absent: platform operators administer orgs, not loan files.
    private static final Set<String> ORG_WIDE_AUTHORITIES = Set.of(
            Role.PROCESSOR.authority(), Role.UNDERWRITER.authority(),
            Role.CLOSER.authority(), Role.MANAGER.authority());

    private final CurrentUser currentUser;
    private final LoanAgentService loanAgentService;
    private final LoanLinkageResolver loanLinkageResolver;

    public LoanAccessGuard(CurrentUser currentUser,
                           LoanAgentService loanAgentService,
                           LoanLinkageResolver loanLinkageResolver) {
        this.currentUser = currentUser;
        this.loanAgentService = loanAgentService;
        this.loanLinkageResolver = loanLinkageResolver;
    }

    /** True when the caller may see every loan in their org (ADMIN or a back-office role). */
    public boolean hasOrgWideView() {
        if (currentUser.isAdmin()) return true;
        return currentUser.roles().stream().anyMatch(ORG_WIDE_AUTHORITIES::contains);
    }

    /**
     * Staff-or-owning-LO gate. Passes for org-wide-view roles, or the owning LO (requires the
     * {@code ROLE_LO} authority AND {@code sub == loan.loanOfficerId}). Throws otherwise.
     *
     * <p>This is the unchanged write/NPI/non-allowlisted-read gate. Borrowers and agents always
     * fail it.
     */
    public void assertCanAccess(Loan loan) {
        if (!isStaffOrOwningLo(loan)) {
            throw new ForbiddenException("No access to loan " + loan.getLoanNumber());
        }
    }

    /**
     * Explicit write gate — identical semantics to {@link #assertCanAccess(Loan)} (staff-or-owning-LO),
     * named for call-site clarity at mutation endpoints. Borrowers/agents never pass.
     *
     * <p>{@code LoanController} writes (PATCH update, POST status-transition, DELETE soft-delete) use
     * this; other modules' writes still call {@link #assertCanAccess(Loan)} (functionally identical)
     * pending migration.
     */
    public void assertCanModify(Loan loan) {
        if (!isStaffOrOwningLo(loan)) {
            throw new ForbiddenException("No write access to loan " + loan.getLoanNumber());
        }
    }

    /**
     * READ predicate — strictly broader than {@link #assertCanAccess(Loan)}. Passes for
     * staff-or-owning-LO, OR a linked borrower on this loan (ROLE_BORROWER + borrower-linkage), OR a
     * linked agent on this loan (ROLE_REAL_ESTATE_AGENT + agent-linkage). Throws otherwise.
     *
     * <p>Wired ONLY to the borrower/agent read allowlist. Branch order is exhaustive:
     * org-wide → owning-LO → linked-borrower → linked-agent → deny. PLATFORM_ADMIN never passes.
     */
    public void assertReadable(Loan loan) {
        if (isStaffOrOwningLo(loan)) return;
        UUID me = currentSubject();
        if (me != null) {
            if (currentUser.roles().contains(Role.BORROWER.authority())
                    && loanLinkageResolver.isBorrowerOnLoan(loan.getId(), me)) {
                return;
            }
            if (currentUser.roles().contains(Role.REAL_ESTATE_AGENT.authority())
                    && loanAgentService.isAgentOnLoan(loan.getId(), me)) {
                return;
            }
        }
        throw new ForbiddenException("No access to loan " + loan.getLoanNumber());
    }

    /**
     * Staff-or-owning-LO. Org-wide-view roles pass. The owning-LO branch requires BOTH the
     * {@code ROLE_LO} authority AND {@code sub == loan.loanOfficerId} — a borrower/agent whose sub
     * happened to equal a {@code loanOfficerId} must NOT be treated as the LO.
     */
    private boolean isStaffOrOwningLo(Loan loan) {
        if (hasOrgWideView()) return true;
        if (!currentUser.roles().contains(Role.LO.authority())) return false;
        UUID me = currentSubject();
        return me != null && me.equals(loan.getLoanOfficerId());
    }

    /** The authenticated caller's subject as a UUID, or {@code null} if absent / unparseable. */
    private UUID currentSubject() {
        String me = currentUser.id().orElse(null);
        if (me == null) return null;
        try {
            return UUID.fromString(me);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
