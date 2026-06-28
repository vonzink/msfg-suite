package com.msfg.los.documents.service;

import com.msfg.los.documents.domain.Document;
import com.msfg.los.documents.domain.DocumentStatus;
import com.msfg.los.documents.service.DocumentService.DownloadUrl;
import com.msfg.los.documents.service.DocumentService.UploadUrl;
import com.msfg.los.documents.web.dto.BorrowerUploadUrlRequest;
import com.msfg.los.documents.web.dto.UploadUrlRequest;
import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.platform.error.ConflictException;
import com.msfg.los.platform.error.ForbiddenException;
import com.msfg.los.platform.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Borrower-self document seam — a borrower linked to a loan uploads / confirms / lists / downloads
 * THEIR OWN documents into the suite DMS (the system of record), so staff processing/UW see them.
 *
 * <p>Mirrors the Stage-2 borrower-self application pattern: this service does its OWN borrower
 * authorization ({@link LoanAccessGuard#assertBorrowerOnLoan} + a per-document own-row check) and
 * then delegates to {@link DocumentService}'s guard-free seams — it never widens the staff-only
 * document guard. The {@code partyRole} is forced to {@code "borrower"} (the caller cannot choose it),
 * and ownership is the audit {@code createdBy} (the borrower's sub), never client-supplied.
 */
@Service
public class BorrowerDocumentService {

    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final DocumentService documentService;
    private final CurrentUser currentUser;

    public BorrowerDocumentService(LoanService loanService,
                                   LoanAccessGuard accessGuard,
                                   DocumentService documentService,
                                   CurrentUser currentUser) {
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.documentService = documentService;
        this.currentUser = currentUser;
    }

    /** Step 1 — a linked borrower requests a presigned upload URL for one of their own documents. */
    @Transactional
    public UploadUrl uploadUrl(UUID loanId, BorrowerUploadUrlRequest req) {
        Loan loan = loanService.get(loanId);
        accessGuard.assertBorrowerOnLoan(loan);
        // partyRole is FORCED to "borrower" — the borrower never chooses it.
        UploadUrlRequest scoped = new UploadUrlRequest(
                req.fileName(), null, "borrower", req.contentType(), req.folderId(), req.documentTypeId());
        return documentService.doCreateUploadUrl(loanId, loan, scoped);
    }

    /** Step 3 — confirm the borrower's own upload (must be a fresh, borrower-created PENDING doc). */
    @Transactional
    public Document confirm(UUID loanId, UUID docId) {
        Loan loan = loanService.get(loanId);
        accessGuard.assertBorrowerOnLoan(loan);
        Document doc = requireOwnDocument(loanId, docId, loan);
        // Only a fresh upload may be confirmed — a borrower must NOT re-confirm (and thereby reset a
        // staff review decision on) a doc that has already moved past PENDING_UPLOAD.
        if (doc.getDocumentStatus() != DocumentStatus.PENDING_UPLOAD) {
            throw new ConflictException("Document is not awaiting upload confirmation");
        }
        return documentService.doConfirm(loanId, docId, "borrower_upload");
    }

    /** The borrower's OWN confirmed documents on the loan (newest first). */
    @Transactional(readOnly = true)
    public List<Document> list(UUID loanId) {
        Loan loan = loanService.get(loanId);
        accessGuard.assertBorrowerOnLoan(loan);
        return documentService.listOwnedByCreator(loanId, currentSub());
    }

    /** Presigned download for one of the borrower's OWN documents. */
    @Transactional(readOnly = true)
    public DownloadUrl downloadUrl(UUID loanId, UUID docId) {
        Loan loan = loanService.get(loanId);
        accessGuard.assertBorrowerOnLoan(loan);
        requireOwnDocument(loanId, docId, loan);
        return documentService.doDownloadUrl(loanId, docId);
    }

    /**
     * Load the doc and enforce the per-document own-row rule: staff-or-owning-LO may act on any doc
     * (they also reach the staff endpoints); otherwise the doc MUST have been created by the calling
     * borrower (audit createdBy). A doc that exists on the loan but was uploaded by staff/another
     * borrower → 403, not 404 — we do not leak whether the id exists once the caller is on the loan.
     */
    private Document requireOwnDocument(UUID loanId, UUID docId, Loan loan) {
        Document doc = documentService.requireLiveDoc(loanId, docId);
        if (!accessGuard.isStaffOrOwningLo(loan)) {
            String me = currentSub();
            if (me == null || !me.equals(doc.getCreatedBy())) {
                throw new ForbiddenException("No access to document " + docId);
            }
        }
        return doc;
    }

    private String currentSub() {
        return currentUser.id().orElse(null);
    }
}
