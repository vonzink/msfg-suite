package com.msfg.los.coc.service;

import com.msfg.los.coc.domain.*;
import com.msfg.los.coc.repo.CocDraftRepository;
import com.msfg.los.coc.repo.CocHistoryEntryRepository;
import com.msfg.los.coc.web.dto.CocSubmitRequest;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.platform.error.ConflictException;
import com.msfg.los.platform.error.ForbiddenException;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.security.Role;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class CocService {

    private final CocHistoryEntryRepository history;
    private final CocDraftRepository drafts;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;
    private final CurrentUser currentUser;

    public CocService(CocHistoryEntryRepository history,
                      CocDraftRepository drafts,
                      LoanService loanService,
                      LoanAccessGuard accessGuard,
                      TenantContext tenantContext,
                      CurrentUser currentUser) {
        this.history = history;
        this.drafts = drafts;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
        this.currentUser = currentUser;
    }

    private UUID org() {
        return tenantContext.orgId().orElseThrow(() -> new NotFoundException("Tenant", "current"));
    }

    @Transactional
    public CocHistoryEntry submit(UUID loanId, CocSubmitRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        CocHistoryEntry entry = new CocHistoryEntry();
        entry.setLoanId(loanId);
        entry.setReason(req.reason());
        entry.setDateOfDiscovery(req.dateOfDiscovery());
        entry.setStructureChanges(req.structureChanges() != null ? req.structureChanges() : new ArrayList<>());
        entry.setFeeChanges(req.feeChanges() != null ? req.feeChanges() : new ArrayList<>());
        entry.setStatus(CocStatus.PENDING);
        entry.setSubmittedBy(currentUser.id().orElse(null));
        entry.setSubmittedAt(Instant.now());

        history.save(entry);

        // clear the draft
        drafts.findByLoanId(loanId).ifPresent(drafts::delete);

        return entry;
    }

    @Transactional(readOnly = true)
    public List<CocHistoryEntry> historyList(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return history.findByLoanIdOrderBySubmittedAtDesc(loanId);
    }

    /**
     * Cross-module read seam: the most recent CoC history entry for a loan in a given status (e.g.
     * the latest {@link CocStatus#ACCEPTED} entry that re-establishes good faith), tenant-scoped,
     * WITHOUT a loan access decision — internal disclosure-timing callers have already authorized.
     * Mirrors the raw {@code findTopByLoanIdAndStatusOrderByDecisionDateDesc} query.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<CocHistoryEntry> latestByStatus(UUID loanId, CocStatus status) {
        return history.findTopByLoanIdAndStatusOrderByDecisionDateDesc(loanId, status);
    }

    @Transactional
    public CocHistoryEntry decide(UUID loanId, UUID entryId, CocDecision decision, Set<String> authorities) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        if (!authorities.contains(Role.UNDERWRITER.authority()) && !authorities.contains(Role.ADMIN.authority())) {
            throw new ForbiddenException("Decision requires UNDERWRITER");
        }

        CocHistoryEntry entry = history.findByIdAndOrgId(entryId, org())
                .filter(e -> e.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("CoC entry", entryId));

        if (entry.getStatus() != CocStatus.PENDING) {
            throw new ConflictException("CoC entry already " + entry.getStatus());
        }

        entry.setStatus(decision == CocDecision.ACCEPT ? CocStatus.ACCEPTED : CocStatus.DENIED);
        entry.setDecisionBy(currentUser.id().orElse(null));
        entry.setDecisionDate(Instant.now());

        return history.save(entry);
    }
}
