package com.msfg.los.notes.service;

import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.notes.domain.LoanNote;
import com.msfg.los.notes.repo.LoanNoteRepository;
import com.msfg.los.notes.web.dto.CreateNoteRequest;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Loan-scoped note CRUD. Every operation first asserts the caller can access the loan via
 * {@link LoanAccessGuard} (staff-only, loan-access-gated — no role gate beyond loan access, matching
 * mortgage-app). Cross-module loan reads go through {@link LoanService} (ArchUnit forbids importing
 * another module's repository).
 */
@Service
public class NoteService {

    private final LoanNoteRepository notes;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;
    private final CurrentUser currentUser;

    public NoteService(LoanNoteRepository notes,
                       LoanService loanService,
                       LoanAccessGuard accessGuard,
                       TenantContext tenantContext,
                       CurrentUser currentUser) {
        this.notes = notes;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
        this.currentUser = currentUser;
    }

    /** Tenant-scoped load + same-loan guard. */
    private LoanNote load(UUID loanId, UUID noteId) {
        return notes.findByIdAndOrgId(noteId, tenantContext.requireOrgId())
                .filter(n -> n.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("Note", noteId));
    }

    @Transactional(readOnly = true)
    public List<LoanNote> list(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return notes.findByLoanIdOrderByCreatedAtDescIdDesc(loanId);
    }

    @Transactional
    public LoanNote create(UUID loanId, CreateNoteRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        if (req.content() == null || req.content().isBlank())
            throw new ValidationException("content must not be blank");

        LoanNote n = new LoanNote();
        n.setLoanId(loanId);
        n.setContent(req.content());
        n.setAuthorId(currentUser.id().orElse(null));
        // Display name: the principal's name claim, else email, else the principal id (best-effort).
        n.setAuthorName(currentUser.name()
                .or(currentUser::email)
                .or(currentUser::id)
                .orElse(null));
        return notes.save(n);
    }

    @Transactional
    public void delete(UUID loanId, UUID noteId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        LoanNote n = load(loanId, noteId);  // 404 if not in this loan/org
        notes.delete(n);
    }
}
