# Cutover Parity Checklist

Definition-of-done for retiring `mortgage-app/backend` + `mortgage-app/frontend`. Every item must be
**have** (✅) in MSFG-suite (backend) + msfg-suite-web (frontend) before the Phase 6 flip. Tags:
✅ have · 🟡 partial · ❌ missing · ⏭️ intentionally dropped. Phase = owning cutover phase.

> Source of truth = the `mortgage-app` backend (`com.msfg.mortgage`, 13 controllers) + its React
> frontend. MSFG-suite already covers the full 1003 data model, qualification calc, pricing/lock,
> AUS+credit, disclosures, fees, CoC, multi-tenancy, and the pipeline — those are **not** re-listed.

## ✅ Merged so far (the per-item ❌/🟡 tags below predate these — sections now DONE)
- **Phase 1 — Documents/S3** ✅ merged `9998e2e`: S3 `ObjectStoragePort` (S3 + DB-local adapters), 3-step
  presigned upload/confirm/download, folders (tree + per-loan template seeding), doc-type + folder-template
  catalogs (admin CRUD), 10-state review workflow + status-history, query-side faceted search. (V18/V19)
- **Phase 2/3 — Auth & Identity** ✅ merged `c672f06`: MANAGER role + tenant-ADMIN catalog gating; `user_account`
  (materialize-on-`/me`); `GET /me` + `/me/loans`; provider-neutral `PrincipalPort` + Cognito adapter (M1). (V20)
- **Phase 2 — Loan/app + Dashboard** ✅ merged `ba98050`: conditions module + notes module; status backdating,
  soft-delete, lookup-by-number, typeahead search; full query-side pipeline filter set; aggregated
  `/dashboard` payload + edit-terms; clone "Copy to new" (deep applicant-tree copy). (V21–V23)
- **Still open:** Phase 3 admin (beyond catalogs), Phase 4 AI folder-eval, Phase 5 MISMO + integrations,
  Phase 6 deploy + `app.msfgco.com` DNS flip (gated). The deployed-Cognito `org_id` claim provisioning = Phase 6.
  Known follow-ups: clone's `joinedToBorrowerId` not remapped; per-new-org catalog/template seeding.

## Auth / role model
- ❌ `org_id` claim emitted by the deployed Cognito pool (new LOS pool) — Phase 0 design / Phase 6 provision.
- ✅ Reject tenant-less tokens (fail-closed) — **done in Phase 0 (Task 1/2)**.
- 🟡 Role reconciliation (**bidirectional**): mortgage-app groups used in `SecurityConfig` matchers are
  `Admin, Manager, LO, Processor, Borrower, RealEstateAgent` (a `CognitoJwtConverter` maps any `cognito:groups`
  value to `ROLE_*` with **no allowlist**; `External` appears in converter docs but in **no** matcher/handler —
  confirm whether it exists in the live pool). vs MSFG-suite `LO, PROCESSOR, UNDERWRITER, CLOSER, ADMIN, PLATFORM_ADMIN`
  (exact-match allowlist). Deltas both ways: **`Borrower`, `RealEstateAgent`, `Manager` have no MSFG-suite equivalent**
  (borrower/agent self-service + a manager tier), and **`UNDERWRITER`, `CLOSER` are net-new MSFG-suite staff roles**
  with no mortgage-app source. mortgage-app resolves principal by `email`→`sub`; MSFG-suite by `sub`. — Phase 2/3.
- 🟡 Per-loan access policy: mortgage-app `LoanAccessGuard.canAccess` (Admin/Manager superuser; LO/Processor
  by `assigned_lo_id`; Borrower by `borrowers.user_id`; Agent by `loan_agents.user_id`) vs MSFG-suite
  `LoanAccessGuard.hasOrgWideView`. Borrower/agent self-scoping is the gap — Phase 2/3.
- Principal identity: mortgage-app resolves by `email`→`sub`; MSFG-suite by `sub`. Note for Phase 6 (user provisioning).

## Borrower / agent self-service portal (OPEN SCOPE QUESTION)
- ❓ Does the cutover preserve borrower/agent self-service (own-loan view + status timeline + direct doc upload)?
  mortgage-app supports it via `LoanAccessGuard` on shared controllers + `GET /me`, `GET /me/loans`.
  **Decision needed before Phase 2.** If yes: borrower/agent roles + per-loan self-scoping + `/me/loans`.

## Loan / application — Phase 2
- ✅ Create loan · ✅ get one · ✅ pipeline list · ✅ status workflow + history (MSFG-suite has these).
- 🟡 Pipeline filters: mortgage-app `status[], lo, conditionsGt, closingFrom/To, stageAgeGt, loanType[], amountMin/Max, sort` — verify/extend MSFG-suite's `GET /api/loans` filter set.
- ❌ Global typeahead search (`GET /loan-applications/search?q=`) — Phase 2.
- ❌ Lookup by application number; list by status (internal) — Phase 2 (confirm if FE uses them).
- ❌ **Status backdating** (`transitionedAt` on status PATCH) — MSFG-suite has no backdating — Phase 2.
- ❌ Server-side clone ("Copy to new") — Phase 2.
- ❌ Update application (PUT) / delete application — Phase 2.
- 🟡 Pipeline default ordering — MSFG-suite `GET /api/loans` does not return newest-first (Phase 0 D1 drift #3); confirm FE expectation, sort by `updatedAt` desc or expose a sort param — Phase 2.

## Dashboard: terms / conditions / notes — Phase 2
- ❌ Underwriting **conditions** CRUD (`LoanCondition`: add/update/clear/delete) — MSFG-suite has no conditions module — Phase 2.
- ❌ Per-loan **notes** CRUD (`LoanNote`) — MSFG-suite has no notes module — Phase 2.
- 🟡 Aggregated dashboard payload (terms, housing, identifiers, primary borrower, property, status history, agents, closing, purchase credits, outstanding conditions) — assemble from MSFG-suite data — Phase 2.
- ❌ Edit loan terms in place (`PATCH /dashboard/terms`) — Phase 2.

## Documents — Phase 1
- ❌ **3-step direct-to-S3 presigned upload**: `POST upload-url` (presigned PUT, MIME-validated) → client PUT → `PUT {docUuid}/confirm` (S3 HEAD + size + SHA-256 + tags). MSFG-suite stores bytes in DB (`DbDocumentStorageAdapter`) — needs an S3 `DocumentStoragePort` adapter — Phase 1.
- ❌ Presigned **download** URL (`GET {docUuid}/download-url`) — Phase 1.
- ❌ **Folders** (tree, auto-seed root + a default folder set on first GET — verify the exact count/source, in-code list vs DB seed, in Phase 1; do not hard-code a number — create/rename/soft-delete, sibling-collision 400) — Phase 1.
- ❌ **Folder templates** (admin CRUD, `evalPrompt`, singleton flags) — Phase 1/3.
- ❌ **Document types catalog** (table-backed; `GET /document-types`, slug lookup) + MIME/size validation on upload — MSFG-suite has a doc-type **enum** only — Phase 1.
- ❌ **Document review workflow**: `status`, `accept`, `reject`, `request-revision`, `bulk-review`, `status-history`. `DocumentStatus` is exactly **10 states** — `PENDING_UPLOAD, UPLOADED, SCAN_PENDING, SCAN_FAILED, READY_FOR_REVIEW, NEEDS_BORROWER_ACTION, ACCEPTED, REJECTED, ARCHIVED, DELETED_SOFT` (no scan-*passed* state). Key edges: `UPLOADED→{SCAN_PENDING, READY_FOR_REVIEW}`; `SCAN_PENDING/SCAN_FAILED→READY_FOR_REVIEW`; `ACCEPTED/REJECTED/ARCHIVED→READY_FOR_REVIEW` (reopen); `NEEDS_BORROWER_ACTION→UPLOADED` (re-upload). — Phase 1.
- ❌ Document list/search facets (`folderId, unfiled, atRoot, status, documentTypeId, uploadedBy, partyRole, q, page, size`) — Phase 1.
- ❌ Patch metadata · move between folders · permanent delete — Phase 1.
- 🟡 S3 layout + Object Lock/WORM + lifecycle tags (Reg Z retention) — design in Phase 1, provision Phase 6.

## Audit log — Phase 1/3
- ❌ Per-loan audit feed (`GET .../audit-log`, filters) + per-document history (`GET .../documents/{docUuid}/history`). MSFG-suite has `AuditableEntity` timestamps but no audit-log feed/`AuditLog` table — Phase 1/3.

## Folder AI evaluation — Phase 4
- ❌ Provider-agnostic `AiPort` (Anthropic/OpenAI/DeepSeek) + registry, per-tenant provider/model — Phase 4.
- ❌ `POST .../folders/{tpl}/evaluate` + `GET .../evaluation` (11-step guardrail flow) — Phase 4.
- ❌ `app_settings` (`aiEvalEnabled` global toggle, `llmDefaultProvider`, `llmDefaultModel`; DeepSeek prod-gate) — Phase 3/4.
- ❌ PDFBox text extraction (`DocumentParser`) — Phase 4.

## Admin — Phase 3
- ❌ Doc-types admin CRUD (`/admin/document-types`) — Phase 3.
- ❌ Folder-templates admin CRUD (`/admin/folder-templates`) — Phase 3.
- ❌ App-settings admin (`/admin/app-settings`) + public projection (`/app-settings/public`) — Phase 3.
- 🟡 Org/user management — MSFG-suite has `/api/admin/organizations` (PLATFORM_ADMIN); user management UI — Phase 3.

## Identity — Phase 2/3
- ❌ `GET /me` (current-user from JWT, materialize on first call) — Phase 2/3.
- ❌ `GET /me/loans` (caller-scoped, role-filtered loan list) — Phase 2/3 (ties to borrower/agent decision).

## MISMO — Phase 5
- ❌ **MISMO 3.4 export** (`GET .../export/mismo`, `application/xml`) — **REQUIRED** (LendingPad retired but file generation needed) — Phase 5.
- ⏭️ MISMO 3.4 **import** + drift-409/`force=true` (`POST .../import/mismo`, `POST /from-mismo`) — optional/portability only (LendingPad retired) — Phase 5 or deferred.

## Integrations — Phase 5
- 🟡 GoHighLevel sync (`createContact/updateContactStatus/createOpportunity`) — **dormant/unwired** in mortgage-app (no caller; `ghlContactId` never populated; ctor-vs-@Value NPE bug). Confirm whether it's actually wanted before building — Phase 5.
- ⏭️ Borrower-invite / agent-assign routes (`/borrowers/invite`, `/agents/assign`) — referenced in mortgage-app `SecurityConfig` but **no handler exists** (planned-but-unbuilt). Decide if the new backend must provide them — Phase 2/3.

## Health / infra — Phase 6
- ✅ Health probe (MSFG-suite `/actuator/health`).
- ❌ Run app as non-owner DB role to engage RLS (deployment requirement) — Phase 6.
- ❌ Deploy MSFG-suite (Docker) + msfg-suite-web (S3/CloudFront) + DNS flip `app.msfgco.com` — Phase 6.

## Seam / contract hygiene (from Phase 0 D1 — see `phase-0-e2e-evidence.md`)
- ❌ **FE OpenAPI client regeneration.** A fresh `gen:api` against the live backend changed
  `msfg-suite-web/src/lib/api/schema.d.ts` by **+447 / −20** lines — the FE's committed client predates
  current backend endpoints. The cutover must regenerate the FE client against the current backend and
  **pin `gen:api` in CI** so the contract can't silently drift. — Phase 1–3 (per area).
- ❌ **springdoc positional `operationId` churn.** OperationIds are positional (`get → get_1 → get_2 …`);
  adding/reordering endpoints renumbers existing ones, churning the FE typed client across unrelated areas.
  Adopt stable operationIds (`@Operation(operationId=…)`) to localize client diffs. — Phase 2/3.

## No scheduled jobs / inbound webhooks
- mortgage-app has none (`@Scheduled`/`@EventListener`/webhooks absent). Nothing to port.
