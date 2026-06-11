# MSFG LOS — Frontend Integration Guide

> For the **frontend** (a separate repo/app, e.g. `msfg-suite-web`) that consumes this backend's REST API.
> This backend is a stateless Spring Boot JWT resource server. The **API contract is the integration seam** —
> the frontend never edits backend code; backend changes it needs are requested from the backend session.

## TL;DR — fastest way to start
1. Run the backend locally in the **`local` profile** — it auto-authenticates **every** request as a dev ADMIN
   with a tenant already set, so you need **no Cognito and no tokens** to build screens early:
   ```bash
   docker compose up -d         # local Postgres
   ./gradlew :app:bootRun --args='--spring.profiles.active=local'
   # API at http://localhost:8080 · Swagger UI at http://localhost:8080/swagger-ui.html
   ```
2. Point your dev server at `http://localhost:8080`. CORS already allows `http://localhost:5173` (Vite) and
   `http://localhost:3000` (Next) in the `local` profile — see [CORS](#cors).
3. Generate a typed client from the live OpenAPI spec (below). Build against the [stable endpoints](#stable-endpoint-surface).
4. Wire real Cognito auth later (dev/prod) — see [Auth](#auth-cognito--jwt). The contract (envelopes, routes) is identical.

## Base URLs
| Env | API base | Notes |
|---|---|---|
| Local | `http://localhost:8080` | `local` profile = dev ADMIN auto-auth (no IdP). |
| Dev/Prod | TBD (set when deployed) | Real Cognito JWT required on every `/api/**` call. |

## OpenAPI / typed client (the contract)
springdoc is enabled and **public** (no auth):
- Swagger UI: `GET /swagger-ui.html`
- OpenAPI JSON: `GET /v3/api-docs`

Generate a typed client from `/v3/api-docs` (e.g. `openapi-typescript`, `orval`, or `openapi-generator`).
**Treat the generated client as the source of truth for request/response schemas** — this guide stays
high-level on purpose so it can't drift. Existing endpoints are **stable**; new specs (Assets/Liabilities,
REO, etc.) only **add** endpoints, never break shipped ones.

## Response envelopes (every endpoint)
Success:
```json
{ "success": true, "data": <payload> }
```
Paged list:
```json
{ "success": true, "data": { "items": [...], "page": 0, "size": 20, "total": 123, "totalPages": 7 } }
```
Error (any 4xx/5xx — FLAT, not nested):
```json
{ "success": false, "code": "VALIDATION_ERROR", "message": "human-readable", "fields": { "field": "why" }, "timestamp": "2026-06-05T..." }
```
- `401` = no/invalid token · `403` = authenticated but not allowed (wrong role / not your loan) ·
  `404` = not found **or cross-tenant** (existence is never leaked across orgs) · `400` = validation
  (`code: VALIDATION_ERROR`, often with `fields`).

## Auth (Cognito + JWT)
The backend validates **RS256 Cognito JWTs** as a resource server (stateless; send `Authorization: Bearer <token>`).
- **You provision a Cognito app client** (Authorization Code + **PKCE**, SPA) in the LOS user pool (separate from
  the dashboard's pool). Hosted UI or Amplify/oidc-client both fine. The backend does not issue tokens.
- **REQUIRED claim: `org_id`** — the token MUST carry an `org_id` claim (the tenant UUID). Tenancy is enforced
  from it on every call; a token without `org_id` fails closed. Ensure the pool emits `org_id` (custom attribute
  / pre-token-generation Lambda) in the token the SPA sends. *(Backend coordination point — flag if the pool
  isn't emitting it yet.)*
- **Roles** come from the `cognito:groups` claim, mapped to `ROLE_*`. Known roles:
  `LO`, `PROCESSOR`, `UNDERWRITER`, `CLOSER`, `ADMIN`, `PLATFORM_ADMIN`. The UI should gate features by role
  (e.g. only `LO`/`ADMIN` may create loans; `/api/admin/**` is `PLATFORM_ADMIN` only).
- **Local shortcut:** in the `local` profile there is NO real auth — every request is a dev ADMIN
  (`org_id = 00000000-0000-0000-0000-0000000000aa`). Build and demo without Cognito; add the real flow before dev/prod.

## CORS
Configured via `los.cors.allowed-origins` (exact-origin allowlist; `allowCredentials=false` since auth is a
Bearer header, not cookies). Applies to `/api/**`.
- `local` profile already allows `http://localhost:5173` and `http://localhost:3000`.
- For dev/prod, the **backend session** adds your deployed frontend origin (uncomment in
  `application-{dev,prod}.yml` or set the `LOS_CORS_ALLOWED_ORIGINS` env var, comma-separated). Send the request
  to the backend session — don't edit backend config from the frontend repo.

## Stable endpoint surface (today)
All under `/api`, all tenant- + loan-scoped (the JWT's `org_id` filters everything; you only ever see your org's data).
Exact schemas: see `/v3/api-docs`.

- **Loans / pipeline**
  - `POST /api/loans` — `loanOfficerId` is now **optional**; omit it and it defaults to the authenticated principal.
  - `GET /api/loans` (paged) — list rows now include `primaryBorrowerName`, `propertyCity`, `propertyState`, `updatedAt` (in addition to `id`, `loanNumber`, `status`, `loanOfficerId`).
  - `GET /api/loans/{id}` — `LoanSummaryResponse` now also carries `lienPriority`, `amortizationType`, `addressLine1`, `addressLine2`, `postalCode`, `estimatedValue` (so the Loan-Info form pre-fills on reload).
  - `PATCH /api/loans/{id}` · `POST /api/loans/{id}/status` (guarded lifecycle transition `{targetStatus, reason}`).
  - `GET /api/loans/{id}/status/transitions` → `{ currentStatus, allowedTransitions: [LoanStatus…] }` — **role-aware** (only the next states the caller may actually perform; use it to drive the status dropdown so it never offers a 403 target).
- **Borrowers & PII** (1003 §1a)
  - `POST|GET|PATCH|DELETE /api/loans/{loanId}/borrowers[/{borrowerId}]` — SSN is **masked** in responses
    (`ssnLast4`/`ssnMasked`); full SSN only via `POST /api/loans/{loanId}/borrowers/{borrowerId}/reveal-ssn`
    `{reason}` (audited — show a reason prompt in the UI).
  - `POST|GET|PATCH|DELETE /api/loans/{loanId}/borrowers/{borrowerId}/addresses[/{addressId}]` (typed address history).
- **Employment & Income** (1003 §1b–1e)
  - `POST|GET|PATCH|DELETE /api/loans/{loanId}/borrowers/{borrowerId}/employments[/{employmentId}]`.
  - `POST|GET|PATCH|DELETE /api/loans/{loanId}/borrowers/{borrowerId}/income[/{incomeId}]`
    (`incomeType` is a ULAD enum; employment-income items link to an `employmentId`, other-source items don't).
  - `GET /api/loans/{loanId}/income/summary` — the income grid: rows (borrower · type · employer · monthly) +
    `totalMonthlyIncome`.
  - `POST|GET /api/loans/{loanId}/income/verifications` — doc-less VOI/tax-transcript tracker (stub today).
- **Assets & Liabilities** (1003 §2)
  - `POST|GET|PATCH|DELETE /api/loans/{loanId}/borrowers/{borrowerId}/assets[/{assetId}]` + `GET /api/loans/{loanId}/assets/summary` (`totalAssets`).
  - `POST|GET|PATCH|DELETE /api/loans/{loanId}/borrowers/{borrowerId}/liabilities[/{liabilityId}]` — each carries `includeInDti` + `exclusionReason` (DTI include/exclude toggle) — + `GET /api/loans/{loanId}/liabilities/summary` (`totalMonthlyPayments`, `dtiMonthlyPayments`, `totalUnpaidBalance`).
  - `POST|GET /api/loans/{loanId}/assets/verifications` — doc-less VOA tracker (stub).
- **Real Estate Owned** (1003 §3)
  - `POST|GET|PATCH|DELETE /api/loans/{loanId}/reo[/{reoId}]` (loan-scoped) + `GET /api/loans/{loanId}/reo/summary` (value/rental/expense/mortgage totals).
- **Loan Information** (1003 §4)
  - Carried on `GET|PATCH /api/loans/{id}`: the §4 fields (rate/term; base/financed/second loan amounts; sales price; appraised value; down payment; qualifying score; documentation type) + the embedded subject property (property type/occupancy/units/values).
- **Qualification calc** (read-only, computed live — 1003 underwriting math)
  - `GET /api/loans/{loanId}/calculations` → LTV/CLTV/TLTV, monthly P&I, proposed housing PITI, net rental, **DTI front/back** + the inputs used. **Any figure may be `null`** when its inputs are missing — never a 500. (Spec 6B.)
- **Declarations & HMDA** (1003 §5 + §8) — 1:1 per borrower, **PUT-upsert** (not collection CRUD)
  - `GET|PUT /api/loans/{loanId}/borrowers/{borrowerId}/declarations` (the §5 yes/no questions + `bankruptcyTypes` set).
  - `GET|PUT /api/loans/{loanId}/borrowers/{borrowerId}/demographics` (`ethnicity`/`race` multi-select, `sex`, `applicationTakenMethod`). **GET before any PUT → 200 with empty/null fields** (not 404).
- **Fees** (processing — loan-scoped fee ledger; mirrors `src/features/fees/model.ts`)
  - **`PUT /api/loans/{loanId}/fees` — upsert keyed by `(section,label)`, the recommended write path.** It IS your Record key `${sectionId}:${label}`: same body as POST, creates the row or replaces its `amount`/`sellerConcession`/`percent` in place (same `id`, same `ordinal`), 200 both ways, idempotent — no id bookkeeping, no 409s.
  - `POST|GET|PATCH|DELETE /api/loans/{loanId}/fees[/{feeId}]` — id-based CRUD also available. POST returns the row with its `id`; **POST with a duplicate `section`+`label` → 409**. (`section` is the `FeeSection` enum: SELLER_CONCESSIONS, A, B, C, E, F, G, PRORATIONS, H, K, L, REC.)
  - **Negative `amount`/`sellerConcession` are accepted** (proration and section-L rows are credits) and flow into totals. `percent` must be ≥ 0; `label` must be non-blank.
  - `GET /api/loans/{loanId}/fees/totals` → `{ sectionTotals: { "A": …, "B": …, … }, categoryTotals: { origination, didNotShop, didShop, taxesGov, escrowPrepaids } }` — **server-computed**, mirrors your `categoryTotals` exactly (`escrowPrepaids = F+G`; sums `amount` only). Every section present (0 if empty).
  - `GET /api/loans/{loanId}/fees/invoices` · `PUT /api/loans/{loanId}/fees/invoices` — invoice entries, **upsert by `feeLabel`**. Body/response include `"final": boolean` (the JSON field is literally `final`).
- **Change of Circumstance** (processing — mirrors `src/features/coc/cocModel.ts`)
  - `GET|PUT /api/loans/{loanId}/coc/draft` — the 1:1 editable draft `{dateOfDiscovery?, reason?, structureChanges[], feeChanges[]}` (upsert; GET-before-save → 200 empty arrays, not 404). `reason` is the `CocReason` enum; `feeChange.reason`/`hasInvoice` are free-form strings.
  - `POST /api/loans/{loanId}/coc/submit` `{reason (required), dateOfDiscovery?, structureChanges[], feeChanges[]}` → 201 a **PENDING** `CocHistoryEntry`; **clears the draft** (GET draft → empty afterward). Missing `reason` → **400** `VALIDATION_ERROR` with `fields.reason`.
  - `GET /api/loans/{loanId}/coc/history` → entries newest-first (`status`, `submittedAt`, `submittedBy`, `decisionBy`, `decisionDate`).
  - `POST /api/loans/{loanId}/coc/history/{entryId}/decision` `{decision: "ACCEPT"|"DENY"}` → **UNDERWRITER/ADMIN only** (LO → 403); only on a PENDING entry (already-decided → 409). Sets `status` + `decisionBy` + `decisionDate`.
- **Document Manager** (processing — unblocks the `PreApprovalPage` "Generate" button + invoice uploads)
  - `POST /api/loans/{loanId}/documents` — **`multipart/form-data`**: `file`, `documentType`, `category?` → 201 `DocumentResponse`. `documentType` is the `DocumentType` enum (`PRE_APPROVAL, INVOICE, APPRAISAL, CREDIT_REPORT, ASSET_STATEMENT, INCOME_DOC, INSURANCE, CONDITION, OTHER`). Empty file → 400; max 25 MB.
  - `GET /api/loans/{loanId}/documents` — **paginated** (`page`,`size`), optional `?type=PRE_APPROVAL` → `PagedResponse<DocumentResponse>` (`id, documentType, category, fileName, contentType, sizeBytes, generatedOn, requestedBy`), newest-first. These are your **Generated On · Document Type · Requested By** table columns.
  - `GET /api/loans/{loanId}/documents/{docId}/content` → the **raw file bytes** (`Content-Type` + `Content-Disposition: attachment`) — a binary download, **not** the `ApiResponse` envelope. Use an `<a download>`/blob fetch, not the typed client's JSON unwrap.
  - `DELETE /api/loans/{loanId}/documents/{docId}` → 204.
  - `POST /api/loans/{loanId}/documents/pre-approval` → generates + stores an HTML pre-approval letter from the loan, returns the `DocumentResponse` (it then appears in `GET …/documents?type=PRE_APPROVAL`). **This is your disabled "Generate Pre-Approval Letter" button.**
- **Admin** (platform)
  - `/api/admin/**` — org/tenant provisioning etc. (`PLATFORM_ADMIN` only).

**✅ The full 1003 (URLA) is merged and live** — every screen in your build plan is buildable now (Specs 1–7).
*Processing-stage modules: **Fees ✅ · Change of Circumstance ✅ · Document Manager ✅ shipped.** Coming next
(additive): Pricing/Lock → AUS → disclosures; plus small deferred bits — Details-of-Transaction/cash-to-close
(Spec 6C), down-payment-source checkboxes, multi-lien/joint REO. Watch `/v3/api-docs` + `docs/ROADMAP.md`.*

## Design inputs (use these)
The UI is modeled on **UWM EASE**. Rich design material already lives in this repo:
- `docs/reference/uwm-ease-frontend-schematic.md` — page/section structure (incl. the 1003 sections).
- `docs/reference/uwm-ease-wireframe-spec.md` — ASCII wireframes (pipeline, 1003 forms, the income grid, etc.).
- `docs/reference/uwm-ease-master-reference.md` — the route/feature registry.

## Working rule (avoids confusion with the backend session)
**One owner per area.** The frontend repo never edits backend Java, Gradle, or config. Anything you need from
the backend — a new endpoint, a field, CORS origin, the `org_id` token claim — is a request to the **backend
session**, which owns and ships it. The OpenAPI spec is the shared contract between the two.
