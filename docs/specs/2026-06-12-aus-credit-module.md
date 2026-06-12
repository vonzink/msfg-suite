# MSFG LOS — AUS + Credit Vendor Module (frontend §4)

> DU/LPA runs + credit-report ordering with **vendor edges shaped 1:1 to the real wire surfaces**
> (researched 2026-06: Fannie DU MISMO 3.4 + DU Wrapper / casefile IDs; Freddie LPA S2S v6.1 / LPA Key;
> credit vendors via MISMO 2.x credit XML, Xactus as reference) so the production adapters are a swap,
> not a redesign. Stub-first behind ports. The FE's AusPage (issue-mode, credit company, per-borrower
> Credit Refs, FHA case number, LPA branch number) is the contract to serve. Security-critical
> (vendor secrets, FCRA credit data) → opus pass before merge.

## Decisions (Zack, 2026-06-11/12)
- **Scope:** AUS **and** credit vendors together (the reissue flow couples them: credit pulled once →
  `creditReportIdentifier` travels into DU/LPA).
- **Credentials: org defaults + per-loan override.** Encrypted at rest (AES-GCM converter), **masked in
  every response, write-only** (no reveal endpoint), replace-only. Resolution at run time:
  **inline-on-run > loan override > org default**; none → 409 `MISSING_CREDENTIALS`.
- Per-borrower credit reference numbers, issue mode, FHA case number, provider code = **loan facts**
  (not secrets) — stored on a per-loan AUS profile.

## Locked decisions
| Area | Decision |
|---|---|
| Module | ONE new **`aus`** gradle module (`com.msfg.los.aus`) holding vendor credentials, credit orders, AUS runs (couple via reissue; promote `credit` out later only if a non-AUS consumer appears). Deps: `:platform`, `:loan-core`, `:documents` (artifact storage via `storeGenerated`). |
| Ports | **`AusVendorPort.submit(AusSubmission) → AusVendorResult`** and **`CreditVendorPort.order(CreditOrderRequest) → CreditOrderResult`** — field-for-field per the research's port-shaping summary (below). Stub adapters are the only `@Component`s. |
| Vendor identity | Store vendor-assigned ids verbatim: DU **casefile id** / LPA **Key** in `aus_run.vendor_case_id` (+ `vendor_transaction_id` for LPA transaction numbers). Resubmits reuse the case id; never across loans. |
| Recommendations | Store **raw vendor strings** (`raw_recommendation`, `raw_eligibility`) + a normalized enum `AusRecommendation { APPROVE_ELIGIBLE, APPROVE_INELIGIBLE, REFER_WITH_CAUTION, REFER_INELIGIBLE, OUT_OF_SCOPE, ACCEPT, CAUTION, ERROR }` (DU's six + LPA's risk classes; never lossy). |
| Artifacts | Findings (XML + HTML) and credit reports stored as **documents** via `DocumentService.storeGenerated` — new `DocumentType.AUS_FINDINGS` (additive); `CREDIT_REPORT` exists. `aus_run`/`credit_order` carry document ids; download via the existing binary endpoint. |
| Credit wiring | `CreditWiring { creditProviderCode, creditAffiliateCode?, perBorrower[{borrowerId, action ORDER\|REISSUE, creditReportIdentifier?}] }`. REISSUE requires the reference (400 `fields.creditReference` naming the borrower); ORDER lets the stub mint one. |
| Sync now, async-safe | Runs/orders execute synchronously (stubs are instant) but rows carry `status (PENDING\|COMPLETE\|ERROR)` so real slow vendors can go async later with zero contract change. |
| Tenancy | All five tables RLS per the V7–V14 pattern; loan-scoped tables guarded by `assertCanAccess(loanService.get(loanId))`; org-credential endpoints **ROLE_ADMIN-gated** (NOT `/api/admin/**` — that's PLATFORM_ADMIN; use `/api/org/vendor-credentials`, and PLATFORM_ADMIN gets nothing here). |
| Credit scores | FCRA-sensitive but displayable: tenant+loan scoping protects them (income precedent); no column encryption; never logged. |

## Data model — migration **V15** (four tables)
1. **`vendor_credential`** — org default + loan override in one table: `org_id`, `loan_id uuid NULL`
   (NULL = org default), `vendor varchar(20)` (`DU|LPA|CREDIT`), plaintext identity columns
   (`institution_id`, `seller_servicer_number`, `tpo_number`, `branch_number`, `credit_provider_code`,
   `credit_affiliate_code`), **encrypted** secret columns (`username`, `password`, `credit_username`,
   `credit_password` — `EncryptedStringConverter`), audit. Partial uniques:
   `UNIQUE (org_id, vendor) WHERE loan_id IS NULL` and `UNIQUE (org_id, vendor, loan_id) WHERE loan_id IS NOT NULL`.
2. **`aus_profile`** — 1:1 per loan (CoC-draft pattern): `org_id`, `loan_id UNIQUE`,
   `du_settings jsonb`, `lpa_settings jsonb` (`@JdbcTypeCode(SqlTypes.JSON)` records:
   `{issueMode ORDER|REISSUE, creditProviderCode, fhaCaseNumber (DU only), branchNumber (LPA only),
   creditReferences[{borrowerId, reference}]}`) — mirrors the FE form; **no secrets in jsonb** (creds
   live only in `vendor_credential`).
3. **`credit_order`** — loan-scoped: `vendor_provider_code`, `action (SUBMIT|FORCE_NEW|REISSUE|UPGRADE)`,
   `request_type (INDIVIDUAL|JOINT)`, `bureaus` (3 bools), `borrower_ids jsonb`, `status`,
   `credit_report_identifier` (THE id that travels to AUS), `scores jsonb`
   (`[{borrowerId, bureau EQUIFAX|EXPERIAN|TRANSUNION, score, model}]`), `report_document_id`,
   `requested_by`, `requested_at`, `error_message`.
4. **`aus_run`** — loan-scoped: `vendor (DU|LPA)`, `status`, `vendor_case_id`, `vendor_transaction_id`,
   `recommendation` (normalized), `raw_recommendation`, `raw_eligibility`, `credit_report_identifier`,
   `findings_html_document_id`, `findings_xml_document_id`, `messages jsonb`, `requested_by`,
   `requested_at`, `error_message`. Index `(org_id, loan_id, requested_at)`.
All four tables: RLS FORCE + WITH CHECK + NULLIF GUC + `app_user` grants per the V7–V14 pattern
(standard CRUD grants; `aus_run`/`credit_order` are append-mostly by code, not by grant).

## API (all `ApiResponse`-enveloped; cross-org 404; no token 401)
**Org credentials (ROLE_ADMIN):**
- `GET /api/org/vendor-credentials` → list of `VendorCredentialResponse` (identity fields plain;
  secrets as `usernameSet/passwordSet/creditUsernameSet/creditPasswordSet booleans` + `usernameMasked`
  ("j•••n") — **raw secrets never serialized**).
- `PUT /api/org/vendor-credentials/{vendor}` — upsert; omitted/null secret fields keep current values
  (replace-only when provided); blank string clears.

**Loan surface:**
- `GET /api/loans/{loanId}/aus/profile` → `AusProfileResponse { du, lpa }` (settings + refs; plus
  `credentialSource: ORG|LOAN|NONE` per vendor so the FE can show what a run would use).
- `PUT /api/loans/{loanId}/aus/profile` — upsert settings/refs (the FE's save).
- `PUT /api/loans/{loanId}/aus/credentials/{vendor}` — loan-level credential override (same masked
  semantics as org); `DELETE` removes the override (falls back to org).
- `POST /api/loans/{loanId}/aus/run` — body `AusRunRequest { vendor: DU|LPA|ONE_CLICK }` → **201**
  `List<AusRunResponse>` (one entry; two for ONE_CLICK). Flow per vendor: resolve credentials
  (loan > org → 409 `MISSING_CREDENTIALS` naming the vendor) → resolve credit wiring from profile
  (REISSUE without a reference for any borrower → 400 `fields.creditReferences`) → if ORDER mode and
  no reference: invoke `CreditVendorPort` first (persists a `credit_order`, mints the reference) →
  `AusVendorPort.submit` → persist run + store artifacts as documents.
- `GET /api/loans/{loanId}/aus/history` → `List<AusRunResponse>` newest-first (`requestedAt DESC, id`).
- `POST /api/loans/{loanId}/credit/order` — body `CreditOrderRequest { action, requestType, bureaus,
  borrowerIds, creditReportIdentifier? }` → 201 `CreditOrderResponse` (reference + scores + report doc id).
- `GET /api/loans/{loanId}/credit/orders` → history newest-first.

## Stub adapters (deterministic — seeded by loanId+vendor hash)
- **`StubAusVendorAdapter`**: mints `vendor_case_id` (`DU-` / `LPA-` + 10 digits, stable per loan+vendor);
  recommendation rules (documented in code): missing rate/amount/borrower → `OUT_OF_SCOPE`; LTV > 97 →
  `APPROVE_INELIGIBLE` (DU) / `CAUTION` (LPA); else `APPROVE_ELIGIBLE` / `ACCEPT`. Generates findings
  HTML (templated: recommendation, casefile, credit refs, message list) + a minimal codified XML; both
  stored via `storeGenerated` as `AUS_FINDINGS`.
- **`StubCreditVendorAdapter`**: mints `creditReportIdentifier` (`XS-` + 8 digits); per-borrower
  per-bureau scores deterministic in 660–790; tri-merge HTML report stored as `CREDIT_REPORT`.
- Real-adapter seam notes live in the port javadoc: DU = MISMO 3.4 ULAD + DU Wrapper POST (casefile
  lifecycle, Technology Manager creds), LPA = S2S v6.1 (Key/transaction, OAuth tokens 4h), credit =
  MISMO 2.x XML POST (Xactus-style operator auth). Fannie CTE/Test Credit Agency + Freddie CTE +
  Xactus test creds validate the real adapters later.

## Testing (crown jewels)
- **Run E2E (DU, REISSUE):** save profile w/ per-borrower refs + org creds → `POST aus/run {DU}` →
  201, run row has casefile id + `APPROVE_ELIGIBLE` + two `AUS_FINDINGS` documents that download via
  the binary endpoint; JDBC cross-check the run row. **ONE_CLICK** → exactly 2 runs (DU+LPA), history
  returns both newest-first.
- **Credit order E2E:** `POST credit/order` (ORDER, JOINT, 3 bureaus) → reference minted, scores for
  each borrower×bureau (JDBC count = borrowers×3), `CREDIT_REPORT` document downloadable; REISSUE with
  the returned reference → a NEW order row reusing the SAME `creditReportIdentifier`, with its own
  regenerated report document (each order row owns its artifact — audit-friendly).
- **Credential resolution:** org-only → run uses ORG (`credentialSource`); loan override wins; neither →
  409 `MISSING_CREDENTIALS`; ORDER-mode run with no refs mints one and links the credit_order.
- **Secrecy:** GET org/loan credentials NEVER contains the stored secret (assert raw value absent from
  the entire response body); PUT with omitted password keeps old (verify via a subsequent successful run,
  not via reveal); encrypted-at-rest JDBC check (`password NOT LIKE` plaintext).
- **Validation per branch:** REISSUE w/o refs → 400 `fields.creditReferences`; bad vendor enum → 400
  (handler); unknown borrowerId in refs/order → 400 naming it.
- Cross-org 404 on every endpoint; ADMIN-gate 403s (LO PUT org creds → 403; PLATFORM_ADMIN → 403 on
  loan endpoints per access model); RLS IT for all four tables; `OpenApiDocsIT` + duplicate-simple-name
  sweep (all DTOs `Aus*`/`Credit*`/`VendorCredential*`-prefixed).

## Review gates
Two-stage per task + **opus security pass** (vendor secrets, FCRA data, credential resolution) before
merge. Post-merge: restart bootRun, FE handoff (endpoints + the credentialSource/masking contract +
"re-run gen:api"), docs/frontend-integration.md, CLAUDE.md/memory, ROADMAP.

## Out of scope / deferred
Real DU/LPA/credit adapters (separate milestone: MISMO 3.4 builder + vendor onboarding/CTE);
MISMO export; async run polling UI; per-branch credentials; FHA Total Scorecard;
credit-report parsing into liabilities (today: liabilities stay manually entered).
