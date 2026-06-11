# MSFG LOS — Document Manager Module

> Loan-scoped document store: upload / list / download / delete + **pre-approval letter generation**. Storage
> sits behind a **`DocumentStoragePort`** (stub = DB `bytea`; real S3 swaps later, per the cloud-portability
> architecture). Unblocks the frontend's disabled "Generate Pre-Approval Letter" + the Fees invoice-upload binding.
> Mirrors EASE §5.14 (Pre-Approval table: **Generated On · Document Type · Requested By**, paginated).

## Context
The frontend `PreApprovalPage` is a static stub awaiting this module. New **`documents`** module (deps
`:platform`, `:loan-core`), loan-scoped, migration **V13**. Document **metadata** is a queryable table; the
**bytes** live behind `DocumentStoragePort` — the stub adapter stores them in a separate `document_content`
(`bytea`) table (RLS-scoped); the real S3 adapter (later) stores only the key. Pre-approval letters are generated
as **templated HTML** now (no PDF dependency; PDF is a later swap).

## Locked decisions
| Area | Decision |
|---|---|
| Module | New **`documents`** (deps platform, loan-core); loan-scoped |
| Storage | **`DocumentStoragePort`** (interface) + **`DbDocumentStorageAdapter`** stub writing a `document_content` (`bytea`) table; real S3 adapter swaps later (zero service change). `storageKey` = generated UUID (never user input → no path traversal) |
| Metadata vs bytes | `Document` row = metadata (list never loads bytes); content in a separate `document_content` table, loaded only on download |
| Pre-approval | **Templated HTML letter** from loan data, stored as a `PRE_APPROVAL` document (`text/html`); real PDF deferred |
| Audit → columns | `generatedOn` = `createdAt`, `requestedBy` = `createdBy` (the principal) — the EASE table's 3 columns; no extra fields |
| Download | `GET …/{docId}/content` returns the **raw bytes** (`ResponseEntity<byte[]>` + `Content-Type`/`Content-Disposition`) — the one intentional non-`ApiResponse` endpoint (binary, not JSON) |
| Tenancy | entities extend `TenantScopedEntity`; loads via `findByIdAndOrgId`; RLS on both tables |
| Deferred | real S3 adapter; PDF letters; Fees invoice→document binding (additive later); doc e-sign / expiring-docs widget / conditions attachment |

## Data model (module `documents`, package `com.msfg.los.documents`)

### `DocumentType` enum
`PRE_APPROVAL, INVOICE, APPRAISAL, CREDIT_REPORT, ASSET_STATEMENT, INCOME_DOC, INSURANCE, CONDITION, OTHER`.

### `Document` (new — **extends `TenantScopedEntity`**, loan-scoped, metadata only)
`loanId` · `documentType` (`@Enumerated(STRING)`, not null) · `category` (String, nullable) · `fileName` (String) ·
`contentType` (String) · `sizeBytes` (Long) · `storageKey` (String, not null). (`createdAt`/`createdBy` from the
audit base → `generatedOn`/`requestedBy`.)

### `DocumentContent` (new — **extends `TenantScopedEntity`**, the stub's blob table)
`storageKey` (String, not null) · `content` (`byte[]`, `@Lob`/`bytea`). **Unique `(org_id, storage_key)`.** Only the
stub `DbDocumentStorageAdapter` touches this — services depend on the port, never this entity.

### `DocumentStoragePort` (interface, `documents`)
`void store(String storageKey, byte[] bytes, String contentType)` · `byte[] load(String storageKey)` (→
`NotFoundException` if absent) · `void delete(String storageKey)`. **`DbDocumentStorageAdapter`** (`@Component`,
only impl) uses a `DocumentContentRepository`.

## API (loan-scoped; cross-org → 404, no token → 401)
- `POST /api/loans/{loanId}/documents` — **`multipart/form-data`**: `file` (the upload), `documentType`, `category?`
  → 201 `DocumentResponse`. Rejects an empty file → 400.
- `GET /api/loans/{loanId}/documents` — **paginated** (`page`, `size`), optional `?type=PRE_APPROVAL` filter →
  `ApiResponse<PagedResponse<DocumentResponse>>` (`{id, documentType, category, fileName, contentType, sizeBytes,
  generatedOn, requestedBy}`), newest-first by `createdAt`.
- `GET /api/loans/{loanId}/documents/{docId}/content` → **raw bytes**: `ResponseEntity<byte[]>` with `Content-Type`
  = the stored `contentType` and `Content-Disposition: attachment; filename="<fileName>"`.
- `DELETE /api/loans/{loanId}/documents/{docId}` → 204 (deletes metadata **and** the stored bytes via the port).
- `POST /api/loans/{loanId}/documents/pre-approval` → generates a templated HTML pre-approval letter from the
  loan (loan number, primary borrower name, base/total loan amount, property, date), stores it as a
  `PRE_APPROVAL` document, returns the `DocumentResponse` (201).

## Pre-approval generation
`PreApprovalLetterGenerator` (component): takes the `Loan` (+ the primary borrower name via the existing
`loan-core` `PrimaryBorrowerNameResolver` for one loan id) → returns an HTML `String` (a simple letterhead +
borrower + amount + property + date template). `DocumentService.generatePreApproval` stores it via the port as
`fileName = "pre-approval-<loanNumber>.html"`, `contentType = "text/html"`.

## Config
`application.yml`: `spring.servlet.multipart.max-file-size: 25MB`, `max-request-size: 25MB` (a sane cap; tune later).

## Validation
Upload: `file` not empty (else 400), `documentType` required (multipart `@RequestParam` → 400 if missing). Bytes
size matches `file.getSize()`. `storageKey` is server-generated (UUID) — never from the request.

## Testing
- **Upload→list→download→delete (crown jewel):** `POST` a `MockMultipartFile` (bytes `"hello-pdf"`, type INVOICE)
  → 201; `GET …/documents` → paged, contains it (`fileName`, `documentType=INVOICE`, `sizeBytes`); **`GET
  …/{id}/content` → the response body bytes EQUAL the uploaded bytes** (round-trip through the port/DB), correct
  `Content-Type` + `Content-Disposition`; `DELETE` → 204; `GET …/content` after delete → 404; `?type=` filter
  returns only matching; empty file → 400; cross-org → 404; no token → 401.
- **Pre-approval:** `POST …/documents/pre-approval` → 201 `documentType=PRE_APPROVAL`, `contentType=text/html`,
  `requestedBy` = caller; `GET …/documents?type=PRE_APPROVAL` → contains it; download → the HTML **contains the
  loan number** (and borrower name if set).
- **RLS:** `document` + `document_content` covered by the Spec-2 `RlsIT` pattern — and a **content-isolation check**:
  org B cannot read org A's bytes (the content table is org-scoped + RLS).
- **`OpenApiDocsIT` green:** the multipart upload + the `byte[]` binary download don't break springdoc; new simple
  names (`Document`, `DocumentType`, `DocumentResponse`, `DocumentContent`, `DocumentStoragePort`) are unique repo-wide.

## Migration `V13__documents.sql`
- `CREATE TABLE document (… org_id …, loan_id uuid not null, document_type varchar(40) not null, category
  varchar(120), file_name varchar(500), content_type varchar(200), size_bytes bigint, storage_key varchar(120) not
  null, … audit)`; index `(org_id, loan_id)` + `(org_id, loan_id, document_type)`; RLS FORCE + `tenant_isolation` +
  grant app_user CRUD.
- `CREATE TABLE document_content (… org_id …, storage_key varchar(120) not null, content bytea, … audit)`;
  `unique (org_id, storage_key)`; RLS + grants.

## Module placement
- **`documents` (new):** `DocumentType`, `Document`, `DocumentContent`; repos; `DocumentStoragePort` +
  `DbDocumentStorageAdapter`; `PreApprovalLetterGenerator`; `DocumentService`; `DocumentController` + DTOs.
- **`app`:** `include("documents")` + dep; `V13` migration; multipart config.

## Out of scope / deferred
Real S3 storage adapter; PDF letter rendering; Fees `invoice_entry`→`document` binding (additive later, references a
`documentId`); e-sign / UCD / conditions-attachment / expiring-docs widget; virus scanning.

**Implementation plan:** `docs/superpowers/plans/2026-06-09-documents-module.md` (next).
