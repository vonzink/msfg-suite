package com.msfg.los.documents.service;

import com.msfg.los.documents.domain.Document;
import com.msfg.los.documents.domain.DocumentStatus;
import com.msfg.los.documents.domain.DocumentType;
import com.msfg.los.documents.domain.DocumentTypeCatalog;
import com.msfg.los.documents.domain.Folder;
import com.msfg.los.documents.domain.DocumentStatusHistory;
import com.msfg.los.documents.repo.DocumentRepository;
import com.msfg.los.documents.repo.DocumentSpecifications;
import com.msfg.los.documents.repo.DocumentStatusHistoryRepository;
import com.msfg.los.documents.repo.DocumentTypeCatalogRepository;
import com.msfg.los.documents.repo.FolderRepository;
import com.msfg.los.documents.web.dto.BulkReviewResult;
import com.msfg.los.documents.web.dto.MoveDocumentsRequest;
import com.msfg.los.documents.web.dto.PatchDocumentRequest;
import com.msfg.los.documents.web.dto.UploadUrlRequest;
import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.loan.service.PrimaryBorrowerNameResolver;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.storage.BlobStoragePort;
import com.msfg.los.platform.storage.ObjectStoragePort;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loan-scoped document service. Phase-1 (cutover) adds the 3-step presigned upload flow
 * (upload-url → client PUT → confirm), a presigned download URL, query-side list + faceted search
 * (JPA {@link Specification}s — never load-all-then-filter), and patch/move/permanent-delete.
 *
 * <p>Every entrypoint is staff-gated via {@link LoanAccessGuard} after fetching the loan through
 * {@link LoanService} (cross-module reads go through a SERVICE — ArchUnit enforces it). Tenant-scoped
 * loads use {@code findByIdAndOrgId} / loan-scoped finders, never bare {@code findById}.
 *
 * <p>Review actions, status transitions and status-history are deliberately NOT here — Task 6 owns
 * them. {@code confirm} sets {@code UPLOADED} directly (the virus-scan states are an unwired seam).
 */
@Service
public class DocumentService {

    /** Presigned-URL validity. */
    static final Duration UPLOAD_TTL = Duration.ofMinutes(15);
    static final Duration DOWNLOAD_TTL = Duration.ofMinutes(15);

    private final DocumentRepository documents;
    private final DocumentTypeCatalogRepository documentTypes;
    private final FolderRepository folders;
    private final DocumentStatusHistoryRepository statusHistory;
    private final FolderService folderService;
    private final BlobStoragePort blobPort;
    private final ObjectStoragePort objectStorage;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;
    private final CurrentUser currentUser;
    private final PrimaryBorrowerNameResolver borrowerNameResolver;
    private final PreApprovalLetterGenerator generator;

    public DocumentService(DocumentRepository documents,
                           DocumentTypeCatalogRepository documentTypes,
                           FolderRepository folders,
                           DocumentStatusHistoryRepository statusHistory,
                           FolderService folderService,
                           BlobStoragePort blobPort,
                           ObjectStoragePort objectStorage,
                           LoanService loanService,
                           LoanAccessGuard accessGuard,
                           TenantContext tenantContext,
                           CurrentUser currentUser,
                           PrimaryBorrowerNameResolver borrowerNameResolver,
                           PreApprovalLetterGenerator generator) {
        this.documents = documents;
        this.documentTypes = documentTypes;
        this.folders = folders;
        this.statusHistory = statusHistory;
        this.folderService = folderService;
        this.blobPort = blobPort;
        this.objectStorage = objectStorage;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
        this.currentUser = currentUser;
        this.borrowerNameResolver = borrowerNameResolver;
        this.generator = generator;
    }

    // ── 3-step presigned upload ──────────────────────────────────────────────────────────

    /** Result of step-1: the created PENDING_UPLOAD doc + the presigned PUT URL. */
    public record UploadUrl(Document doc, String uploadUrl, String contentType, long expiresInSeconds) {
    }

    /**
     * Step 1 — create the {@code PENDING_UPLOAD} document row + server-minted key and presign a PUT.
     * The bytes are NOT uploaded here; the client PUTs them to {@link UploadUrl#uploadUrl()} then
     * calls {@link #confirm}.
     */
    @Transactional
    public UploadUrl createUploadUrl(UUID loanId, UploadUrlRequest req) {
        Loan loan = loanService.get(loanId);
        accessGuard.assertCanAccess(loan);

        DocumentTypeCatalog type = null;
        if (req.documentTypeId() != null) {
            type = documentTypes.findByIdAndOrgId(req.documentTypeId(), tenantContext.requireOrgId())
                    .orElseThrow(() -> new NotFoundException("Document type", req.documentTypeId()));
            validateMime(type, req.contentType());
        }

        Document doc = new Document();
        doc.setLoanId(loanId);
        doc.setFileName(req.fileName());
        doc.setContentType(blankToNull(req.contentType()));
        doc.setPartyRole(req.partyRole().trim());
        doc.setDocumentTypeId(type != null ? type.getId() : null);
        doc.setDocumentType(mapLegacyType(req.documentType()));
        doc.setDocumentStatus(DocumentStatus.PENDING_UPLOAD);
        doc.setFolderId(resolveFolder(loanId, loan, req.folderId(), type));
        // placeholder key satisfies the NOT NULL column on the first insert; replaced below once the
        // DB-generated id is known so the key embeds the real, non-enumerable doc id.
        doc.setStorageKey("pending-" + UUID.randomUUID());
        documents.saveAndFlush(doc);

        String typeName = type != null ? type.getName() : null;
        String storageKey = StorageKeys.build(loanId, req.partyRole(), typeName, doc.getId(), req.fileName());
        doc.setStorageKey(storageKey);
        documents.save(doc);

        String uploadUrl = objectStorage.presignUpload(storageKey, doc.getContentType(), UPLOAD_TTL);
        return new UploadUrl(doc, uploadUrl, doc.getContentType(), UPLOAD_TTL.toSeconds());
    }

    /**
     * Step 3 — confirm the upload: HEAD the object for its size (400 if absent), enforce the type's
     * {@code max_file_size_bytes}, record size + sha256, tag the object, and flip the status to
     * {@code UPLOADED}.
     */
    @Transactional
    public Document confirm(UUID loanId, UUID docId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        Document doc = loadLive(loanId, docId);

        long size = objectStorage.headSize(doc.getStorageKey());
        if (size < 0) {
            throw new ValidationException("Upload not found in storage");
        }

        if (doc.getDocumentTypeId() != null) {
            DocumentTypeCatalog type = documentTypes
                    .findByIdAndOrgId(doc.getDocumentTypeId(), tenantContext.requireOrgId())
                    .orElse(null);
            if (type != null && type.getMaxFileSizeBytes() != null && size > type.getMaxFileSizeBytes()) {
                throw new ValidationException(
                        "File exceeds max size for " + type.getName() + " ("
                                + type.getMaxFileSizeBytes() + " bytes; uploaded " + size + ")");
            }
        }

        doc.setSizeBytes(size);
        doc.setFileHash(objectStorage.sha256(doc.getStorageKey())); // best-effort, may be null
        objectStorage.tag(doc.getStorageKey(), Map.of(
                "sensitivity", "confidential",
                "retention_class", "standard",
                "source", "staff_upload",
                "application_id", loanId.toString(),
                "loan_id", loanId.toString()));
        doc.setDocumentStatus(DocumentStatus.UPLOADED);
        return documents.save(doc);
    }

    // ── download ─────────────────────────────────────────────────────────────────────────

    public record DownloadUrl(String url, long expiresInSeconds) {
    }

    /** Presign a download URL (attachment named after the stored fileName). */
    @Transactional(readOnly = true)
    public DownloadUrl downloadUrl(UUID loanId, UUID docId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        Document doc = loadLive(loanId, docId);
        String filename = doc.getFileName() != null ? doc.getFileName() : "download";
        String url = objectStorage.presignDownload(doc.getStorageKey(), filename, DOWNLOAD_TTL);
        return new DownloadUrl(url, DOWNLOAD_TTL.toSeconds());
    }

    // ── list + faceted search (query-side) ─────────────────────────────────────────────────

    /**
     * Confirmed (UPLOADED+; excludes PENDING_UPLOAD/DELETED_SOFT), non-deleted docs in the loan,
     * optionally folder-filtered. Filtering is in the query (Specification), newest first.
     */
    @Transactional(readOnly = true)
    public List<Document> listConfirmed(UUID loanId, UUID folderId, boolean unfiled, boolean atRoot) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        Specification<Document> spec = DocumentSpecifications.inLoanNotDeleted(loanId)
                .and(DocumentSpecifications.confirmedOnly());

        if (folderId != null) {
            spec = spec.and(DocumentSpecifications.inFolder(folderId));
        } else if (unfiled || atRoot) {
            // unfiled / atRoot both mean: documents not in any folder (folder_id is null)
            spec = spec.and(DocumentSpecifications.inFolder(null));
        }
        return documents.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    /** Faceted, paged search — every filter applied IN THE QUERY. Tenant + loan scoped, live only. */
    @Transactional(readOnly = true)
    public Page<Document> search(UUID loanId,
                                 DocumentStatus status,
                                 UUID documentTypeId,
                                 UUID folderId,
                                 boolean folderIdProvided,
                                 String uploadedBy,
                                 String partyRole,
                                 String q,
                                 int page,
                                 int size) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<Document> spec = DocumentSpecifications.search(
                loanId, status, documentTypeId, folderId, folderIdProvided, uploadedBy, partyRole, q);
        return documents.findAll(spec, pageable);
    }

    // ── mutations: patch / move / permanent-delete ──────────────────────────────────────────

    /** Partial metadata update. {@code null} fields are left unchanged. Validates the target folder. */
    @Transactional
    public Document patch(UUID loanId, UUID docId, PatchDocumentRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        Document doc = loadLive(loanId, docId);

        if (req.fileName() != null) {
            String fn = req.fileName().trim();
            if (fn.isBlank()) throw new ValidationException("fileName must not be blank");
            doc.setFileName(fn);
        }
        if (req.folderId() != null) {
            assertFolderInLoan(loanId, req.folderId());
            doc.setFolderId(req.folderId());
        }
        if (req.documentType() != null) {
            doc.setDocumentType(req.documentType());
        }
        if (req.description() != null) {
            doc.setDescription(blankToNull(req.description()));
        }
        return documents.save(doc);
    }

    /** Move (or unfile, when {@code toFolderId} is null) a batch of documents. Missing ids are skipped. */
    @Transactional
    public int move(UUID loanId, MoveDocumentsRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        UUID toFolderId = req.toFolderId();
        if (toFolderId != null) {
            assertFolderInLoan(loanId, toFolderId);
        }

        int moved = 0;
        UUID orgId = tenantContext.requireOrgId();
        for (UUID id : req.docIds()) {
            Document doc = documents.findByIdAndOrgId(id, orgId)
                    .filter(d -> d.getDeletedAt() == null)
                    .filter(d -> d.getLoanId().equals(loanId))
                    .orElse(null);
            if (doc == null) continue; // skip foreign / missing / deleted ids
            doc.setFolderId(toFolderId);
            documents.save(doc);
            moved++;
        }
        return moved;
    }

    /**
     * Permanent delete: the document MUST currently be in the loan's Delete folder. Removes the
     * stored object, then soft-deletes the row (sets {@code deletedAt} + {@code DELETED_SOFT}) — the
     * row is retained for audit, never hard-deleted.
     */
    @Transactional
    public void permanentDelete(UUID loanId, UUID docId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        Document doc = loadLive(loanId, docId);

        Folder deleteFolder = folders.findFirstByLoanIdAndDeleteFolderTrueAndDeletedAtIsNull(loanId)
                .orElse(null);
        boolean inDeleteFolder = deleteFolder != null
                && deleteFolder.getId().equals(doc.getFolderId());
        if (!inDeleteFolder) {
            throw new ValidationException("Move to the Delete folder before permanent deletion");
        }

        objectStorage.delete(doc.getStorageKey());
        doc.setDeletedAt(Instant.now());
        doc.setDocumentStatus(DocumentStatus.DELETED_SOFT);
        documents.save(doc);
    }

    // ── review state machine + status history (Task 6) ───────────────────────────────────────

    /**
     * Generic status transition ({@code PUT /status}): validate the edge against the pure
     * {@link DocumentStatusTransitions} table (illegal → 409), set the new status, and append a
     * {@link DocumentStatusHistory} row. NO review-field side effects (use the review actions for that).
     *
     * @param targetName the target {@link DocumentStatus} name (unknown → 400)
     */
    @Transactional
    public Document transition(UUID loanId, UUID docId, String targetName, String note) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        Document doc = loadLive(loanId, docId);

        DocumentStatus target = parseStatus(targetName);
        DocumentStatusTransitions.assertAllowed(doc.getDocumentStatus(), target);

        doc.setDocumentStatus(target);
        appendHistory(doc, target, note);
        return documents.save(doc);
    }

    /** Accept ({@code → ACCEPTED}); notes optional. */
    @Transactional
    public Document accept(UUID loanId, UUID docId, String notes) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        Document doc = loadLive(loanId, docId);
        return reviewDocument(doc, DocumentStatus.ACCEPTED, notes);
    }

    /** Reject ({@code → REJECTED}); notes required (non-blank → 400). */
    @Transactional
    public Document reject(UUID loanId, UUID docId, String notes) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        Document doc = loadLive(loanId, docId);
        requireNotes(notes);
        return reviewDocument(doc, DocumentStatus.REJECTED, notes);
    }

    /** Request revision ({@code → NEEDS_BORROWER_ACTION}); notes required (non-blank → 400). */
    @Transactional
    public Document requestRevision(UUID loanId, UUID docId, String notes) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        Document doc = loadLive(loanId, docId);
        requireNotes(notes);
        return reviewDocument(doc, DocumentStatus.NEEDS_BORROWER_ACTION, notes);
    }

    /**
     * Bulk-review: apply one {@code decision} to many docs in a single call. {@code decision} must be
     * {@code ACCEPTED} / {@code REJECTED} / {@code NEEDS_BORROWER_ACTION} (else 400); notes required for
     * the two non-accept decisions. Each doc is processed in its OWN handling so a per-doc failure
     * (missing/foreign/not-in-a-reviewable-state) is COLLECTED, never aborting the batch.
     */
    @Transactional
    public BulkReviewResult bulkReview(UUID loanId, String decisionName, List<UUID> docIds, String notes) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        DocumentStatus decision = parseStatus(decisionName);
        if (decision != DocumentStatus.ACCEPTED
                && decision != DocumentStatus.REJECTED
                && decision != DocumentStatus.NEEDS_BORROWER_ACTION) {
            throw new ValidationException(
                    "decision must be one of ACCEPTED, REJECTED, NEEDS_BORROWER_ACTION");
        }
        if (decision != DocumentStatus.ACCEPTED) {
            requireNotes(notes);
        }

        UUID orgId = tenantContext.requireOrgId();
        int succeeded = 0;
        List<BulkReviewResult.Failure> failures = new ArrayList<>();
        for (UUID id : docIds) {
            try {
                Document doc = documents.findByIdAndOrgId(id, orgId)
                        .filter(d -> d.getDeletedAt() == null)
                        .filter(d -> d.getLoanId().equals(loanId))
                        .orElseThrow(() -> new NotFoundException("Document", id));
                reviewDocument(doc, decision, notes);
                succeeded++;
            } catch (RuntimeException ex) {
                failures.add(new BulkReviewResult.Failure(id, ex.getMessage()));
            }
        }
        return new BulkReviewResult(docIds.size(), succeeded, failures.size(), decision, failures);
    }

    /** Ordered (oldest-first) status history for a document. */
    @Transactional(readOnly = true)
    public List<DocumentStatusHistory> statusHistory(UUID loanId, UUID docId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        Document doc = loadLive(loanId, docId);
        return statusHistory.findByDocumentIdOrderByTransitionedAtAsc(doc.getId());
    }

    /**
     * Shared review apply: validate the edge ({@code current → target}), set status + reviewer
     * identity / notes / timestamp, append history. In practice the reviewable entry state is
     * {@code READY_FOR_REVIEW}; the transition table enforces it (a doc in e.g. PENDING_UPLOAD → 409).
     */
    private Document reviewDocument(Document doc, DocumentStatus target, String notes) {
        DocumentStatusTransitions.assertAllowed(doc.getDocumentStatus(), target);
        doc.setDocumentStatus(target);
        doc.setReviewedBy(currentUser.id().orElse(null));
        doc.setReviewerNotes(blankToNull(notes));
        doc.setReviewedAt(Instant.now());
        appendHistory(doc, target, notes);
        return documents.save(doc);
    }

    /** Append an append-only status-history row stamped with the current principal + now. */
    private void appendHistory(Document doc, DocumentStatus status, String note) {
        DocumentStatusHistory row = new DocumentStatusHistory();
        row.setDocumentId(doc.getId());
        row.setStatus(status);
        row.setTransitionedAt(Instant.now());
        row.setTransitionedBy(currentUser.id().orElse(null));
        row.setNote(blankToNull(note));
        statusHistory.save(row);
    }

    private static void requireNotes(String notes) {
        if (notes == null || notes.isBlank()) {
            throw new ValidationException("notes are required for this review action");
        }
    }

    /** Parse a {@link DocumentStatus} name from a request body string; unknown / blank → 400. */
    private static DocumentStatus parseStatus(String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("status is required");
        }
        try {
            return DocumentStatus.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new ValidationException("Unknown document status: " + name);
        }
    }

    // ── legacy paths (kept for generated docs / pre-approval / existing multipart contract) ──

    @Transactional
    public Document upload(UUID loanId, MultipartFile file, DocumentType type, String category) throws IOException {
        accessGuard.assertCanAccess(loanService.get(loanId));

        if (file == null || file.isEmpty()) {
            throw new ValidationException("file is required");
        }

        Document doc = new Document();
        doc.setLoanId(loanId);
        doc.setDocumentType(type);
        doc.setCategory(category);
        doc.setFileName(file.getOriginalFilename());
        doc.setContentType(file.getContentType());
        doc.setSizeBytes(file.getSize());
        doc.setStorageKey(UUID.randomUUID().toString());
        doc.setDocumentStatus(DocumentStatus.UPLOADED); // direct-multipart bytes are present now

        documents.save(doc);
        blobPort.store(doc.getStorageKey(), file.getBytes(), doc.getContentType());
        return doc;
    }

    @Transactional(readOnly = true)
    public DownloadResult load(UUID loanId, UUID docId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        Document doc = documents.findByIdAndOrgId(docId, tenantContext.requireOrgId())
                .filter(d -> d.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("Document", docId));
        byte[] bytes = blobPort.load(doc.getStorageKey());
        return new DownloadResult(doc, bytes);
    }

    @Transactional
    public void delete(UUID loanId, UUID docId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        Document doc = documents.findByIdAndOrgId(docId, tenantContext.requireOrgId())
                .filter(d -> d.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("Document", docId));
        blobPort.delete(doc.getStorageKey());
        documents.delete(doc);
    }

    /** Store a server-generated artifact (letters, confirmations) behind the storage port. */
    @Transactional
    public Document storeGenerated(UUID loanId, DocumentType type, String category,
                                   String fileName, String contentType, byte[] bytes) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        Document doc = new Document();
        doc.setLoanId(loanId);
        doc.setDocumentType(type);
        doc.setCategory(category);
        doc.setFileName(fileName);
        doc.setContentType(contentType);
        doc.setSizeBytes((long) bytes.length);
        doc.setStorageKey(UUID.randomUUID().toString());
        doc.setDocumentStatus(DocumentStatus.UPLOADED);
        documents.save(doc);
        blobPort.store(doc.getStorageKey(), bytes, doc.getContentType());
        return doc;
    }

    @Transactional
    public Document generatePreApproval(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        var loan = loanService.get(loanId);
        String name = borrowerNameResolver.primaryBorrowerNamesByLoanIds(List.of(loanId)).get(loanId);
        String html = generator.generate(loan, name);
        return storeGenerated(loanId, DocumentType.PRE_APPROVAL, null,
                "pre-approval-" + loan.getLoanNumber() + ".html", "text/html",
                html.getBytes(StandardCharsets.UTF_8));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    /** Load a single live (not soft-deleted) doc, tenant + loan scoped. 404 otherwise. */
    private Document loadLive(UUID loanId, UUID docId) {
        return documents.findByIdAndOrgId(docId, tenantContext.requireOrgId())
                .filter(d -> d.getDeletedAt() == null)
                .filter(d -> d.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("Document", docId));
    }

    private void assertFolderInLoan(UUID loanId, UUID folderId) {
        folders.findByIdAndOrgId(folderId, tenantContext.requireOrgId())
                .filter(f -> f.getDeletedAt() == null)
                .filter(f -> f.getLoanId().equals(loanId))
                .orElseThrow(() -> new ValidationException("Folder does not belong to this loan"));
    }

    /**
     * Destination folder for a new upload: explicit {@code requestedFolderId} (validated) wins;
     * else auto-route to the live folder whose name matches the type's {@code default_folder_name}
     * (seeding the tree first); else unfiled (null).
     */
    private UUID resolveFolder(UUID loanId, Loan loan, UUID requestedFolderId, DocumentTypeCatalog type) {
        if (requestedFolderId != null) {
            assertFolderInLoan(loanId, requestedFolderId);
            return requestedFolderId;
        }
        if (type != null && type.getDefaultFolderName() != null && !type.getDefaultFolderName().isBlank()) {
            folderService.ensureSeeded(loanId, loan); // materialize the template tree if absent
            // The catalog default_folder_name carries the sort prefix (e.g. "03 Income") but the
            // seeded folder display names do not ("Income"). Match the raw name first, then the
            // prefix-stripped name.
            String raw = type.getDefaultFolderName().trim();
            UUID byRaw = lookupFolderByName(loanId, raw);
            if (byRaw != null) return byRaw;
            return lookupFolderByName(loanId, stripSortPrefix(raw));
        }
        return null;
    }

    /** A live folder id in the loan by case-insensitive display name, or null. */
    private UUID lookupFolderByName(UUID loanId, String name) {
        if (name == null || name.isBlank()) return null;
        return folders.findFirstByLoanIdAndNameNormalizedAndDeletedAtIsNull(loanId, name.trim().toLowerCase())
                .map(Folder::getId)
                .orElse(null);
    }

    /** Drop a leading "NN " numeric sort prefix (e.g. "03 Income" → "Income"). */
    private static String stripSortPrefix(String name) {
        return name == null ? null : name.replaceFirst("^\\s*\\d+\\s+", "");
    }

    private void validateMime(DocumentTypeCatalog type, String contentType) {
        String allowedCsv = type.getAllowedMimeTypes();
        if (allowedCsv == null || allowedCsv.isBlank()) return; // no allowlist → anything goes
        String declared = normalizeMime(contentType);
        if (declared == null) return; // no contentType supplied → cannot validate (allowed)

        Map<String, Boolean> allowed = new LinkedHashMap<>();
        Arrays.stream(allowedCsv.split(","))
                .map(DocumentService::normalizeMime)
                .filter(m -> m != null)
                .forEach(m -> allowed.put(m, Boolean.TRUE));

        if (!allowed.containsKey(declared)) {
            throw new ValidationException(
                    "File type '" + declared + "' is not allowed for " + type.getName()
                            + ". Allowed: " + String.join(", ", allowed.keySet()));
        }
    }

    /** lowercase, drop any {@code ;charset=...} suffix, trim. */
    private static String normalizeMime(String s) {
        if (s == null) return null;
        String m = s.trim().toLowerCase();
        int semi = m.indexOf(';');
        if (semi >= 0) m = m.substring(0, semi).trim();
        return m.isBlank() ? null : m;
    }

    private static DocumentType mapLegacyType(String raw) {
        if (raw == null || raw.isBlank()) return DocumentType.OTHER;
        try {
            return DocumentType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return DocumentType.OTHER;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
