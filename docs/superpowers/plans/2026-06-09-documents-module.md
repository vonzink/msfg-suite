# Document Manager Module — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development + test-driven-development.
> Spec: `docs/specs/2026-06-09-documents-module.md`. Loan-scoped; storage behind a port; V13. Keep `/v3/api-docs` green.

**Goal:** loan-scoped document upload/list/download/delete + pre-approval letter generation, storage behind `DocumentStoragePort` (stub = DB `bytea`).

**Architecture:** new `documents` module (deps `:platform`, `:loan-core`), loan-scoped (mirror `fees`/`reo` guard).
Metadata table + separate `document_content` blob table behind the port. Migration `V13`.

## Templates to mirror
- Loan-scoped service guard: `fees/.../service/FeeService.java`.
- Port + stub adapter pattern: `income/.../verification/{IncomeVerificationPort,StubIncomeVerificationAdapter}.java`.
- jsonb/blob entity is NOT needed — `byte[]` maps to `bytea` via plain `@Column` (Postgres) — confirm with LosApplicationTests.
- Primary-borrower name for the letter: `loan-core` `PrimaryBorrowerNameResolver` (inject it; call `primaryBorrowerNamesByLoanIds(List.of(loanId)).get(loanId)`).
- V13 RLS idiom: `V12__coc.sql`. RLS IT: `financials/.../AssetsLiabilitiesRlsIT.java`.
- `documents/build.gradle.kts` = deps `:platform`, `:loan-core`, web/data-jpa/validation.

---

## Task 0: Scaffold + `DocumentType` + V13 + multipart config
- [ ] settings/app/build wiring (deps platform + loan-core). `DocumentType` enum (per spec).
- [ ] `app/.../db/migration/V13__documents.sql` (per spec — `document` + `document_content` tables; `content bytea`; `document_content` unique `(org_id, storage_key)`; indexes; FORCE RLS + tenant_isolation + grants — mirror `V12`).
- [ ] `app/src/main/resources/application.yml`: add under `spring:` → `servlet.multipart.max-file-size: 25MB` + `max-request-size: 25MB`.
- [ ] `:documents:classes :app:compileJava` + `:app:test --tests "*LosApplicationTests"` → PASS. Commit `chore(documents): scaffold module + DocumentType + V13 + multipart config`.

## Task 1: Entities + repos + storage port + stub adapter
- [ ] `Document` (extends `TenantScopedEntity`): `UUID loanId`(not null), `@Enumerated(STRING) DocumentType documentType`(not null), `String category`, `String fileName`, `String contentType`, `Long sizeBytes`, `String storageKey`(not null).
- [ ] `DocumentContent` (extends `TenantScopedEntity`): `String storageKey`(not null), `@Column(columnDefinition="bytea") byte[] content`.
- [ ] `DocumentRepository`: `Page<Document> findByLoanIdOrderByCreatedAtDesc(UUID, Pageable)`, `Page<Document> findByLoanIdAndDocumentTypeOrderByCreatedAtDesc(UUID, DocumentType, Pageable)`, `Optional<Document> findByIdAndOrgId(UUID,UUID)`. `DocumentContentRepository`: `Optional<DocumentContent> findByStorageKey(String)`, `findByIdAndOrgId`.
- [ ] **Port** `documents/.../storage/DocumentStoragePort.java`:
```java
package com.msfg.los.documents.storage;
public interface DocumentStoragePort {
    void store(String storageKey, byte[] bytes, String contentType);
    byte[] load(String storageKey);   // NotFoundException if absent
    void delete(String storageKey);
}
```
- [ ] **Stub adapter** `documents/.../storage/DbDocumentStorageAdapter.java` (`@Component`, the only impl):
```java
package com.msfg.los.documents.storage;
import com.msfg.los.documents.domain.DocumentContent;
import com.msfg.los.documents.repo.DocumentContentRepository;
import com.msfg.los.platform.error.NotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
@Component
public class DbDocumentStorageAdapter implements DocumentStoragePort {
    private final DocumentContentRepository contents;
    public DbDocumentStorageAdapter(DocumentContentRepository contents) { this.contents = contents; }
    @Override @Transactional public void store(String storageKey, byte[] bytes, String contentType) {
        DocumentContent c = contents.findByStorageKey(storageKey).orElseGet(DocumentContent::new);
        c.setStorageKey(storageKey); c.setContent(bytes); contents.save(c);
    }
    @Override @Transactional(readOnly = true) public byte[] load(String storageKey) {
        return contents.findByStorageKey(storageKey).map(DocumentContent::getContent)
            .orElseThrow(() -> new NotFoundException("Document content", storageKey));
    }
    @Override @Transactional public void delete(String storageKey) {
        contents.findByStorageKey(storageKey).ifPresent(contents::delete);
    }
}
```
  (`findByStorageKey` is `@TenantId`-filtered → org-scoped; RLS backs it.)
- [ ] `:app:test --tests "*LosApplicationTests"` → PASS (entities + bytea validate vs V13). Commit `feat(documents): Document + DocumentContent entities, repos, DocumentStoragePort + DB stub adapter`.

## Task 2: Upload / list / download / delete
- [ ] DTO `DocumentResponse(UUID id, DocumentType documentType, String category, String fileName, String contentType, Long sizeBytes, java.time.Instant generatedOn, String requestedBy)` + `from(Document d)` (`generatedOn = d.getCreatedAt()`, `requestedBy = d.getCreatedBy()`).
- [ ] `DocumentService` (loan-scoped guard; inject `DocumentRepository`, `DocumentStoragePort port`, `LoanService`, `LoanAccessGuard`, `TenantContext`):
  - `upload(loanId, MultipartFile file, DocumentType type, String category)`: guard; `if (file == null || file.isEmpty()) throw new ValidationException("file is required");` build `Document` (loanId, type, category, `fileName=file.getOriginalFilename()`, `contentType=file.getContentType()`, `sizeBytes=file.getSize()`, `storageKey=UUID.randomUUID().toString()`); `documents.save(doc)`; `port.store(doc.getStorageKey(), file.getBytes(), doc.getContentType())`; return doc. (`file.getBytes()` throws IOException — let the controller/handler surface it.)
  - `list(loanId, DocumentType type, Pageable p)`: guard; `type == null ? findByLoanIdOrderByCreatedAtDesc(loanId, p) : findByLoanIdAndDocumentTypeOrderByCreatedAtDesc(loanId, type, p)`.
  - `load(loanId, docId)`: guard; `Document d = documents.findByIdAndOrgId(docId, org()).filter(x -> x.getLoanId().equals(loanId)).orElseThrow(new NotFoundException("Document", docId)); byte[] bytes = port.load(d.getStorageKey()); return (d, bytes);` (return a small record `DownloadResult(Document doc, byte[] bytes)`).
  - `delete(loanId, docId)`: guard; load `d`; `port.delete(d.getStorageKey()); documents.delete(d);`.
- [ ] `DocumentController` `@RequestMapping("/api/loans/{loanId}/documents")`:
  - `@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)` params `@PathVariable UUID loanId, @RequestParam("file") MultipartFile file, @RequestParam("documentType") DocumentType documentType, @RequestParam(value="category", required=false) String category` → 201 `ApiResponse.ok(DocumentResponse.from(service.upload(...)))`. (Method may `throws IOException`.)
  - `@GetMapping` `@RequestParam(required=false) DocumentType type, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size` → `ApiResponse.ok(PagedResponse.from(service.list(loanId, type, PageRequest.of(page, size)).map(DocumentResponse::from)))`.
  - `@GetMapping("/{docId}/content")` → `var r = service.load(loanId, docId); return ResponseEntity.ok().contentType(MediaType.parseMediaType(r.doc().getContentType() != null ? r.doc().getContentType() : "application/octet-stream")).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + r.doc().getFileName() + "\"").body(r.bytes());` (returns `ResponseEntity<byte[]>` — NOT wrapped in ApiResponse).
  - `@DeleteMapping("/{docId}")` → `service.delete(...); return ResponseEntity.noContent().build();`.
- [ ] **IT (crown jewel)** `DocumentControllerIT` (use `MockMultipartFile`): `POST` multipart (bytes `"hello-pdf".getBytes()`, `documentType=INVOICE`, fileName `inv.pdf`) → 201 `$.data.documentType=="INVOICE"`, `$.data.fileName=="inv.pdf"`; `GET /documents` → `$.data.items` contains it; **`GET /documents/{id}/content`** → response bytes `== "hello-pdf"` (assert `andReturn().getResponse().getContentAsByteArray()`), `Content-Type` matches, `Content-Disposition` has the filename; `DELETE` → 204; `GET …/content` after → 404; upload a PRE_APPROVAL too + `GET ?type=INVOICE` → only the invoice; empty file → 400; cross-org → 404; no token → 401. (multipart with a JWT: `multipart("/api/loans/{l}/documents", loanId).file(mockFile).param("documentType","INVOICE").with(lo())`.)
- [ ] `:app:test --tests "*DocumentControllerIT"` + `:documents:test` → PASS. Commit `feat(documents): upload/list/download/delete (multipart + binary, storage port)`.

## Task 3: Pre-approval letter generation
- [ ] `PreApprovalLetterGenerator` (`@Component`): `String generate(Loan loan, String primaryBorrowerName)` → an HTML string (letterhead "MSFG", borrower name, loan number, base/total loan amount, property city/state, a date placeholder from loan data — keep it simple + deterministic; **do not** use `Instant.now()` in a way that makes the test non-deterministic — it's fine to include a generated date, just don't assert on it). MUST include the loan number in the HTML.
- [ ] `DocumentService.generatePreApproval(loanId)`: guard; `Loan loan = loanService.get(loanId)` (already guarded); `String name = borrowerNameResolver.primaryBorrowerNamesByLoanIds(java.util.List.of(loanId)).get(loanId);` `String html = generator.generate(loan, name);` `byte[] bytes = html.getBytes(StandardCharsets.UTF_8);` build `Document` (type PRE_APPROVAL, `fileName="pre-approval-"+loan.getLoanNumber()+".html"`, `contentType="text/html"`, `sizeBytes=(long)bytes.length`, `storageKey=UUID`); save; `port.store(...)`; return doc. (Inject `PrimaryBorrowerNameResolver` + `PreApprovalLetterGenerator`.)
- [ ] `DocumentController` `@PostMapping("/pre-approval")` → 201 `ApiResponse.ok(DocumentResponse.from(service.generatePreApproval(loanId)))`.
- [ ] **IT** `PreApprovalIT`: create a loan (+ a primary borrower); `POST …/documents/pre-approval` → 201 `$.data.documentType=="PRE_APPROVAL"`, `$.data.contentType=="text/html"`, `$.data.requestedBy` == caller; `GET …/documents?type=PRE_APPROVAL` → contains it; `GET …/{id}/content` → the HTML body **contains the loan number** (read it from the create-loan response); cross-org → 404; no token → 401.
- [ ] `:app:test --tests "*PreApprovalIT"` → PASS. Commit `feat(documents): pre-approval letter generation (templated HTML, stored via port)`.

## Task 4: RLS IT + full build + finish
- [ ] `DocumentsRlsIT` — copy `AssetsLiabilitiesRlsIT`; fresh orgs `…00d7`/`…00d8` (distinct from all others); seed a loan per org; under `app_user`+GUC=ORG_X insert one `document` + one `document_content` (read V13 NOT-NULL cols: `document_type`+`storage_key` for document, `storage_key` for content); assert ORG_Y → 0 / ORG_X → ≥1 over BOTH tables (proves bytes are org-isolated too) / fail-closed on `document`.
- [ ] `:app:test --tests "*DocumentsRlsIT" --tests "*OpenApiDocsIT"` → PASS, then FULL `./gradlew build` → SUCCESSFUL (report total test count).
- [ ] Commit `test(documents): RLS coverage for document + document_content`. Update `docs/frontend-integration.md`/`ROADMAP.md`. Then **superpowers:finishing-a-development-branch**.

## Self-Review
Loan-scoped guard; storage behind `DocumentStoragePort` (DB-bytea stub, S3-swappable); metadata/bytes split (list
never loads bytes); download is binary `ResponseEntity<byte[]>` (intentional non-envelope); `storageKey` is a
server UUID (no path traversal); pre-approval letter = templated HTML via the port; RLS on BOTH tables (bytes
org-isolated); `OpenApiDocsIT` green (multipart + binary OK, unique simple names); V13 sequential; additive. ✓
