# Cutover Phase 1 — Documents / S3 (spec)

**Goal:** bring msfg-suite's `documents` module to parity with `mortgage-app`'s document subsystem
(see the source map for exact mortgage-app behavior), adapted for **multi-tenant** + **staff-only**.
Source of truth = mortgage-app backend. Migration takes **V18** (next free; V17 is the last).

## Design adaptations (msfg-suite vs mortgage-app source)
1. **Multi-tenant.** Every new table carries `org_id uuid not null references organization(id)` + FORCE
   RLS (`tenant_isolation` policy, the V13 pattern) + `grant … to app_user`. The existing `document.id`
   (uuid, from `TenantScopedEntity`) **is** the public, non-enumerable handle — **no `doc_uuid` column**
   (mortgage-app needed it because its PK is bigserial; ours is already a uuid).
2. **Staff-only.** DROP: `visible_to_borrower` / `visible_to_agent`, `borrower_visible_default`,
   the `source=borrower_portal` / `loan_state=incomplete` hard-coded tags, and the borrower-facing
   semantics. KEEP the `DocumentStatus` enum intact (incl. `NEEDS_BORROWER_ACTION` as an internal
   "needs revision" marker) so the state machine stays parity-complete. **Gate every endpoint to staff**
   via the existing `LoanAccessGuard` (LO owner-scoped; PROCESSOR/UNDERWRITER/CLOSER org-wide; ADMIN).
3. **Envelope.** Use msfg-suite's `ApiResponse`/`PagedResponse`/`ApiError` (NOT mortgage-app's raw Maps).
4. **Better-than-source.** List/search push filters into the query (JPA `Specification` or `@Query` with
   optional params) — NOT mortgage-app's "load all uploaded then filter in memory". Enforce
   `max_file_size_bytes` on confirm (mortgage-app left it advisory). State machine = a pure
   `DocumentStatusTransitions` class, unit-tested matrix (mirror `pricing/LockTransitions`).
5. **Routes** live under `/api/loans/{loanId}/...` (msfg-suite convention), not `/loan-applications/...`.

## Schema (migration V18 — alter `document` + 4 new tables, all org-scoped + RLS + grants)
**ALTER `document` ADD:** `folder_id uuid` (FK→folder, null = unfiled/root), `document_type_id uuid`
(FK→document_type, null ok), `document_status varchar(30) not null default 'PENDING_UPLOAD'`,
`party_role varchar(20)` (organization only — borrower/coborrower/lo/system), `reviewed_by varchar(120)`,
`reviewer_notes varchar(2000)`, `reviewed_at timestamptz`, `file_hash varchar(64)`, `description varchar(1000)`,
`deleted_at timestamptz`. (Existing: id, org_id, loan_id, document_type[enum], category, file_name,
content_type, size_bytes, storage_key, audit cols.) Add index `(org_id, loan_id, document_status)`.
Soft-delete = `deleted_at is not null`; all reads filter it out.

**`folder`:** id, org_id, loan_id, parent_id uuid (null=root), display_name, name_normalized
(`lower(trim(display_name))`), sort_key varchar(64), is_system bool, is_old_loan_archive bool,
is_delete_folder bool, folder_template_id uuid, created_by, timestamps, deleted_at. **Partial unique
indexes (Postgres-only, so we DO enforce in DB, improving on the source):**
`unique(org_id, loan_id) where parent_id is null and deleted_at is null` (one live root/loan) and
`unique(org_id, loan_id, parent_id, name_normalized) where deleted_at is null` (case-insensitive siblings).

**`document_type`** (org-scoped catalog): id, org_id, name, slug, default_folder_name,
required_for_milestones, allowed_mime_types (csv), max_file_size_bytes bigint, is_active bool default true,
sort_order int. `unique(org_id, slug)`.

**`folder_template`** (org-scoped): id, org_id, display_name, sort_key, is_old_loan_archive bool,
is_delete_folder bool, is_active bool default true, sort_order int, eval_prompt text (Phase-4 AI column;
nullable). `unique(org_id, display_name)`. **Singletons** (app-enforced): ≤1 `is_delete_folder`, ≤1
`is_old_loan_archive` per org.

**`document_status_history`** (append-only): id, org_id, document_id uuid (FK→document, on delete cascade),
status varchar(30), transitioned_at timestamptz default now, transitioned_by varchar(120), note varchar(1000).
Index `(org_id, document_id, transitioned_at)`.

**Seeding (V18):** seed the 17 folder_templates + 16 document_types **for every existing `organization` row**
(`insert … select … from organization`). New-org seeding (when tenancy provisions a new org) is a
**follow-up** noted for Phase 2/admin — folder seeding falls back to root-only if an org has no templates.

### Seed: folder_templates (17; sort_key "01".."17")
`01 Submission · 02 Borrower Documents · 03 Income · 04 Assets · 05 Credit · 06 Property · 07 Title ·
08 Insurance · 09 Disclosures · 10 Conditions · 11 Underwriting · 12 Closing · 13 Post Closing ·
14 Invoices · 15 Correspondence · 16 Old Loan Files (is_old_loan_archive) · 17 Delete (is_delete_folder)`

### Seed: document_types (16; name / slug / default_folder_name / allowed_mime / max_bytes / sort)
```
W-2 / w-2 / 03 Income / application/pdf,image/jpeg,image/png / 10485760 / 1
Pay Stub / pay-stub / 03 Income / application/pdf,image/jpeg,image/png / 10485760 / 2
Tax Return / tax-return / 03 Income / application/pdf / 52428800 / 3
Bank Statement / bank-statement / 04 Assets / application/pdf,image/jpeg,image/png / 20971520 / 4
Investment Statement / investment-statement / 04 Assets / application/pdf / 20971520 / 5
Gift Letter / gift-letter / 04 Assets / application/pdf,image/jpeg,image/png / 10485760 / 6
ID / Driver's License / drivers-license / 02 Borrower Documents / application/pdf,image/jpeg,image/png / 10485760 / 7
Explanation Letter / explanation-letter / 02 Borrower Documents / application/pdf,image/jpeg,image/png / 10485760 / 8
Credit Report / credit-report / 05 Credit / application/pdf / 20971520 / 9
Purchase Agreement / purchase-agreement / 06 Property / application/pdf / 52428800 / 10
Appraisal / appraisal / 06 Property / application/pdf / 52428800 / 11
Title Report / title-report / 07 Title / application/pdf / 52428800 / 12
Homeowners Insurance / homeowners-insurance / 08 Insurance / application/pdf,image/jpeg,image/png / 20971520 / 13
Disclosure / disclosure / 09 Disclosures / application/pdf / 52428800 / 14
Closing Document / closing-document / 12 Closing / application/pdf / 52428800 / 15
Other / other / (null) / (null) / 52428800 / 99
```

## Storage port (presigned, port-and-adapter — the testability seam)
Extend storage beyond `BlobStoragePort` (store/load/delete, kept for generated docs). New
`platform.storage.ObjectStoragePort`:
`String presignUpload(key, contentType, Duration ttl)` · `String presignDownload(key, filename, Duration ttl)`
· `long headSize(key)` (−1 if absent) · `String sha256(key)` (nullable, best-effort) ·
`void tag(key, Map<String,String>)` · `void delete(key)`.
- **`S3ObjectStorageAdapter`** (prod): AWS SDK v2 `S3Client` + `S3Presigner` (bucket/region/TTL from
  `los.storage.s3.*` props; default credential chain). Key layout
  `applications/{loanId}/{partyRole}/{typeName}/{docId}-{safeFilename}`; tags
  `sensitivity=confidential, retention_class=standard, source=staff_upload, application_id, loan_id`.
  WORM/Object-Lock/lifecycle = bucket/CDK (Phase 6) — we only emit tags. Unit-test with mocked presigner.
- **`LocalObjectStorageAdapter`** (local + `test` profiles, default): stores bytes in the existing
  `document_content` table; `presignUpload` returns a URL to a profile-gated receive endpoint
  `PUT /api/_local-blob/{token}` that stores the bytes; `headSize`/`sha256` read them back; `tag` is a no-op.
  Lets ITs run the real POST upload-url → PUT → confirm round-trip with no S3. Select adapter via
  `@ConditionalOnProperty los.storage.driver` (db|s3, default db) + profile.

## Endpoints (all under `/api`, ApiResponse envelope, staff-gated via LoanAccessGuard)
**`/loans/{loanId}/documents`** — `POST /upload-url`(create PENDING_UPLOAD doc + key + presigned PUT;
MIME-validate vs type) · `PUT /{docId}/confirm`(HEAD size→enforce maxBytes, sha256, tag, → UPLOADED) ·
`GET /`(folderId?,unfiled?,atRoot?) · `GET /search`(status,documentTypeId,folderId,uploadedBy,partyRole,q,
page,size≤200 — **query-side filters**) · `GET /{docId}/download-url` · `GET /{docId}/content`(keep, for
DB/generated) · `PATCH /{docId}`(fileName,folderId,documentType,description) · `POST /move`(docIds,toFolderId)
· `PUT /{docId}/status` · `POST /{docId}/accept|reject|request-revision`(reject/revision require notes) ·
`POST /bulk-review`(decision∈ACCEPTED|REJECTED|NEEDS_BORROWER_ACTION; per-doc failures collected) ·
`GET /{docId}/status-history` · `DELETE /{docId}/permanent`(must be in Delete folder first) ·
`POST /pre-approval`(keep existing). Review actions + permanent-delete restricted to staff roles with loan access.
**`/loans/{loanId}/folders`** — `GET /`(tree, auto-seed from org templates) · `POST /seed-defaults` ·
`POST /`(parentId,displayName) · `PATCH /{folderId}`(rename) · `DELETE /{folderId}`(soft; system→400).
**`/document-types`** `GET /` + `GET /{slug}` (any staff). **`/admin/document-types`** + **`/admin/folder-templates`**
full CRUD, `@PreAuthorize ADMIN`, dup-slug/name→400, template singleton enforcement, Delete-template undeletable.

## DocumentStatus state machine (pure `DocumentStatusTransitions`, unit-tested matrix)
States: `PENDING_UPLOAD, UPLOADED, SCAN_PENDING, SCAN_FAILED, READY_FOR_REVIEW, NEEDS_BORROWER_ACTION,
ACCEPTED, REJECTED, ARCHIVED, DELETED_SOFT`. Transitions (verbatim from source):
```
PENDING_UPLOAD→{UPLOADED,SCAN_FAILED}; UPLOADED→{SCAN_PENDING,READY_FOR_REVIEW,DELETED_SOFT};
SCAN_PENDING→{SCAN_FAILED,READY_FOR_REVIEW}; SCAN_FAILED→{READY_FOR_REVIEW,DELETED_SOFT};
READY_FOR_REVIEW→{ACCEPTED,REJECTED,NEEDS_BORROWER_ACTION,ARCHIVED,DELETED_SOFT};
NEEDS_BORROWER_ACTION→{UPLOADED,DELETED_SOFT}; ACCEPTED→{ARCHIVED,READY_FOR_REVIEW};
REJECTED→{READY_FOR_REVIEW,DELETED_SOFT}; ARCHIVED→{READY_FOR_REVIEW}; DELETED_SOFT→{} (terminal)
```
`confirm` sets UPLOADED directly. Review actions go READY_FOR_REVIEW→target, set reviewed_by/notes/at,
append status_history. Virus-scan states kept as an unwired seam (confirm → UPLOADED; no scanner yet).

## Build sequence (each its own TDD'd commit on a `feat/cutover-phase1-documents` branch)
1. **V18 migration + entities + repos** (alter document, 4 tables, RLS+grants, per-org seed) + RLS ITs.
2. **Storage ports + adapters** (`ObjectStoragePort`, S3 + Local adapters, local receive endpoint, config) + S3 unit test.
3. **Folders** (service: tree/ensureSeeded/create/rename/soft-delete/uniqueness; controller; ITs).
4. **Catalogs** (document_type + folder_template services, read + admin CRUD controllers, singleton rules; ITs).
5. **Documents core** (3-step upload/confirm, download-url, list, query-side search, patch, move, permanent-delete; controller; ITs).
6. **Review** (`DocumentStatusTransitions` unit matrix; accept/reject/request-revision/bulk/status/status-history; ITs).
7. **Verify**: full `./gradlew build` green, OpenApiDocsIT green (no springdoc dup-name collisions), RLS ITs for all new tables.
Checkpoint with Zack at the phase boundary.
```
