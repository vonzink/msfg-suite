package com.msfg.los.documents.service;

import com.msfg.los.documents.domain.Document;
import com.msfg.los.documents.domain.DocumentType;
import com.msfg.los.documents.repo.DocumentRepository;
import com.msfg.los.documents.storage.DocumentStoragePort;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.loan.service.PrimaryBorrowerNameResolver;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentService {

    private final DocumentRepository documents;
    private final DocumentStoragePort port;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;
    private final PrimaryBorrowerNameResolver borrowerNameResolver;
    private final PreApprovalLetterGenerator generator;

    public DocumentService(DocumentRepository documents,
                           DocumentStoragePort port,
                           LoanService loanService,
                           LoanAccessGuard accessGuard,
                           TenantContext tenantContext,
                           PrimaryBorrowerNameResolver borrowerNameResolver,
                           PreApprovalLetterGenerator generator) {
        this.documents = documents;
        this.port = port;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
        this.borrowerNameResolver = borrowerNameResolver;
        this.generator = generator;
    }

    private UUID org() {
        return tenantContext.orgId().orElseThrow(() -> new NotFoundException("Tenant", "current"));
    }

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

        documents.save(doc);
        port.store(doc.getStorageKey(), file.getBytes(), doc.getContentType());
        return doc;
    }

    @Transactional(readOnly = true)
    public Page<Document> list(UUID loanId, DocumentType type, Pageable pageable) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        if (type == null) {
            return documents.findByLoanIdOrderByCreatedAtDesc(loanId, pageable);
        } else {
            return documents.findByLoanIdAndDocumentTypeOrderByCreatedAtDesc(loanId, type, pageable);
        }
    }

    @Transactional(readOnly = true)
    public DownloadResult load(UUID loanId, UUID docId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        Document doc = documents.findByIdAndOrgId(docId, org())
                .filter(d -> d.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("Document", docId));
        byte[] bytes = port.load(doc.getStorageKey());
        return new DownloadResult(doc, bytes);
    }

    @Transactional
    public void delete(UUID loanId, UUID docId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        Document doc = documents.findByIdAndOrgId(docId, org())
                .filter(d -> d.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("Document", docId));
        port.delete(doc.getStorageKey());
        documents.delete(doc);
    }

    @Transactional
    public Document generatePreApproval(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        var loan = loanService.get(loanId);
        String name = borrowerNameResolver.primaryBorrowerNamesByLoanIds(List.of(loanId)).get(loanId);
        String html = generator.generate(loan, name);
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        Document doc = new Document();
        doc.setLoanId(loanId);
        doc.setDocumentType(DocumentType.PRE_APPROVAL);
        doc.setFileName("pre-approval-" + loan.getLoanNumber() + ".html");
        doc.setContentType("text/html");
        doc.setSizeBytes((long) bytes.length);
        doc.setStorageKey(UUID.randomUUID().toString());

        documents.save(doc);
        port.store(doc.getStorageKey(), bytes, doc.getContentType());
        return doc;
    }
}
