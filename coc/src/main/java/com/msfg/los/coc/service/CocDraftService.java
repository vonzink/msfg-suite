package com.msfg.los.coc.service;

import com.msfg.los.coc.domain.CocDraft;
import com.msfg.los.coc.repo.CocDraftRepository;
import com.msfg.los.coc.web.dto.CocDraftRequest;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.UUID;

@Service
public class CocDraftService {

    private final CocDraftRepository drafts;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;

    public CocDraftService(CocDraftRepository drafts,
                           LoanService loanService,
                           LoanAccessGuard accessGuard) {
        this.drafts = drafts;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
    }

    @Transactional(readOnly = true)
    public CocDraft get(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return drafts.findByLoanId(loanId).orElse(null);
    }

    @Transactional
    public CocDraft save(UUID loanId, CocDraftRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        CocDraft draft = drafts.findByLoanId(loanId).orElseGet(() -> {
            CocDraft d = new CocDraft();
            d.setLoanId(loanId);
            return d;
        });

        draft.setDateOfDiscovery(req.dateOfDiscovery());
        draft.setReason(req.reason());
        draft.setStructureChanges(req.structureChanges() != null ? req.structureChanges() : new ArrayList<>());
        draft.setFeeChanges(req.feeChanges() != null ? req.feeChanges() : new ArrayList<>());

        return drafts.save(draft);
    }
}
