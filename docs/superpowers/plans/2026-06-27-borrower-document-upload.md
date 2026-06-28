# Plan — Borrower document upload into the suite DMS (cutover slice 1)

**Created:** 2026-06-27 · **Status:** IN PROGRESS · **Goal:** clients upload documents into the **system of record** (the suite), so processing/UW see them. Today borrowers upload to the *old mortgage-app* backend/S3 → docs never reach the suite. This closes that gap.

## Scope (this slice — suite backend only)
A borrower linked to a loan can **upload / confirm / list / download their OWN documents** into the suite DMS, via a borrower-scoped seam that mirrors the Stage-2 borrower-self application pattern. Staff see borrower uploads automatically in the existing DMS (the key win). Frontend rewire (point mortgage-app at the suite) + staff→borrower sharing are **separate later slices**.

## Design (mirrors Stage-2 borrower-self application + the documents 3-step presigned flow)

### Endpoints — `BorrowerDocumentController` @ `/api/loans/{loanId}/borrower/documents`
- `POST /upload-url` → `UploadUrlResponse` (reuses `UploadUrlRequest`/`UploadUrlResponse`).
- `PUT /{docId}/confirm` → `DocumentResponse`.
- `GET ` (list own) → `DocumentListResponse`.
- `GET /{docId}/download-url` → `DownloadUrlResponse`.
Distinct from the staff `/api/loans/{loanId}/documents` controller (which stays staff-only).

### `BorrowerDocumentService` (documents module)
- `assertBorrowerOnLoan(loan)` for every call (staff-or-owning-LO OR `ROLE_BORROWER` + `isBorrowerOnLoan`).
- upload-url/confirm/download additionally require **own-doc** (`doc.uploadedBy == me`) for non-staff.
- Forces `partyRole = "borrower"`; stamps `uploadedBy = me`; `source = "borrower_upload"` tag.
- Delegates to **guard-free internal seams** on `DocumentService` (mirrors BorrowerApplicationService's "unguarded internal seams").

### `DocumentService` refactor (no behaviour change for staff)
Extract the body of `createUploadUrl`/`confirm`/`downloadUrl` into package-private `do*` seams that take an explicit `uploadedBy`/`source`; the public staff methods do `assertCanAccess` then call the seam (stamping `uploadedBy = currentUser.id()`, source `staff_upload`). Add `listOwnedBy(loanId, uploadedBy)`.

### Schema — `V27__document_uploaded_by.sql`
`alter table document add column uploaded_by uuid;` + `create index idx_document_loan_uploaded_by on document(loan_id, uploaded_by);`. No new grant (table grant covers new columns); document already has RLS.
- `Document.uploadedBy` (UUID), stamped on create. `DocumentResponse` exposes `uploadedBy`.
- `DocumentSpecifications.uploadedBy(UUID)`.

### Security — `SecurityConfig` (4 matchers, `STAFF_AND_BORROWER`, before the staff catch-all)
POST `…/borrower/documents/upload-url`, PUT `…/borrower/documents/{uuid}/confirm`, GET `…/borrower/documents`, GET `…/borrower/documents/{uuid}/download-url` — all UUID-anchored regex. Per-row authorization re-checked in `BorrowerDocumentService`.

### `LoanAccessGuard.assertBorrowerOnLoan(loan)`
staff-or-owning-LO OR (`ROLE_BORROWER` + `loanLinkageResolver.isBorrowerOnLoan`). Agents excluded (clients = borrowers for this slice).

## Visibility model (slice 1)
- Borrower `GET /borrower/documents` → ONLY docs they uploaded (`uploaded_by = me`).
- Staff DMS (`GET /documents`) → ALL confirmed docs incl. borrower uploads (unchanged; the win).
- Staff→borrower sharing (disclosures to sign, "needs from you") → **later slice**.

## Tests (TDD, RED-first)
`BorrowerDocumentIT` (MockMvc, real presigned round-trip via the local adapter, borrower JWT linked via `borrower_party.user_id`):
1. Borrower upload-url → PUT bytes → confirm → `UPLOADED`, `partyRole=borrower`, `uploadedBy=sub`, routed folder; **staff `GET /documents` sees it**; borrower `GET /borrower/documents` sees own; borrower download-url returns usable URL.
2. Unlinked borrower → 403; borrower acting on a doc they didn't upload → 403; borrower hitting staff `/documents` → 403; cross-tenant → 404.
Keep all existing document ITs green.

## Out of scope (later slices)
Frontend rewire (mortgage-app upload → suite); staff→borrower doc sharing + borrower visibility flags; agent uploads; virus-scan states.
