# MSFG LOS — Spec 4: Employment & Income

> The 1003's **Employment & Income** section (URLA Sections 1b–1e), UWM EASE's `Income` analog. The first
> 1003 section that **aggregates across borrowers** (a loan-level grid + TOTAL) and the first to introduce a
> **doc-less verification tracker** behind a vendor port. Builds on the multi-tenant + loan/borrower-scoped
> spine (Specs 1–3): all new entities are tenant-scoped, all loads are org-safe.

## Context
Captures each borrower's **employment history** (current, additional, previous, self-employment) and **all
income** (employment income + other-source income), then surfaces the UWM grid —
**Borrower · Income Type · Employer · Monthly Income** with a bold **TOTAL INCOME** — at loan level. UWM's
`Income` model is ULAD/URLA-3.4-aware (`IsSelfEmployed`, `IsCurrentEmployment`, `EmploymentId`,
`IsURLA34Loan`); we mirror that with a MISMO/ULAD-aligned model so 3.4 export stays mechanical. A thin
**doc-less income-verification tracker** (VOI / tax-transcript status) lands behind an `IncomeVerificationPort`
— a stub today, the real VOE/VOI vendor in the Processing milestone — planting the ports-first seam.

This is a **new `income` module** mirroring `parties` exactly (domain/repo/service/web/dto), depending on
`:platform`, `:loan-core`, `:parties`. Income is financial data but **not SSN-class column-encrypted** (UWM
doesn't; it is protected by tenant isolation + `LoanAccessGuard` + auth) — so **no new crypto**, just the
inherited `org_id`/`@TenantId`/RLS.

## Locked decisions
| Area | Decision |
|---|---|
| Scope | **URLA §1b/1c/1d/1e** — employment (current/additional/previous + self-employment) + employment income + other-source income + loan-level grid/TOTAL + **thin doc-less verification tracker** |
| Income model | **One unified `IncomeItem` table** (ULAD `IncomeType` + `monthlyAmount`, nullable `employmentId`) — employment income links to an `Employment`, other income stands alone on the borrower. MISMO/ULAD-aligned; feeds the S6 DTI calc engine |
| Verification | **Thin read-only tracker** (status enum) + a **stubbed `IncomeVerificationPort`** ("Order Income" records `ORDERED`); **no real vendor** |
| Module | **New `income` module** (deps `:platform`,`:loan-core`,`:parties`); mirrors `parties` layout |
| Tenancy | New entities **extend `TenantScopedEntity`** (auto `org_id` + RLS); **loads use `findByIdAndOrgId`** (Spec-2 rule: `@TenantId` does not filter find-by-PK) |
| Crypto | **None** — income is not column-encrypted; tenant + loan/borrower scoping + auth protect it |
| Deferred | **Income Calculator** (variable-income averaging / YTD worksheet — its own subsystem); **DTI** (needs liabilities → S6); **real VOI/VOE vendor**; "Order Income" real integration |

## Data model (module `income`, package `com.msfg.los.income`)

### `Employment` (new — **extends `TenantScopedEntity`**)
Borrower-scoped, with `loanId` + `borrowerId` denormalized (same pattern as `BorrowerAddress`):
- **Linkage:** `loanId` (UUID) · `borrowerId` (UUID) · `ordinal` (orders a borrower's jobs).
- **Employer:** `employerName` · `employerPhone` · `employerAddressLine1` · `employerAddressLine2` ·
  `employerCity` · `employerState` (`UsStateCode` enum) · `employerPostalCode` · `positionTitle`.
- **Classification:** `employmentStatus` (enum **`CURRENT`/`PREVIOUS`**) · `classification`
  (enum **`PRIMARY`/`SECONDARY`** — URLA "additional" = secondary) · `selfEmployed` (boolean) ·
  `ownershipShare` (enum **`LESS_THAN_25`/`GREATER_OR_EQUAL_25`**, nullable — self-employed only) ·
  `employedByPartyToTransaction` (boolean — URLA family/interested-party flag).
- **Dates/tenure:** `startDate` (LocalDate) · `endDate` (LocalDate, nullable — PREVIOUS) ·
  `monthsInLineOfWork` (Integer).
- *(Self-employment **net income/loss** is captured as a `SELF_EMPLOYMENT_INCOME` `IncomeItem` linked to this
  employment — not a column here — so the grid + TOTAL stay a single uniform sum over `IncomeItem`.)*

### `IncomeItem` (new — **extends `TenantScopedEntity`**) — the grid's unit
- `loanId` (UUID) · `borrowerId` (UUID) · `employmentId` (UUID, **nullable** — null = other-source income) ·
  `incomeType` (enum `IncomeType`) · `monthlyAmount` (BigDecimal) · `description` (String, for `OTHER`) ·
  `ordinal`.
- Employment income (`isEmployment()` types) **must** reference an `Employment` of the same borrower;
  other-source income (`isEmployment()` false) **must** have a null `employmentId`. Enforced in the service.

### `IncomeType` (new enum — ULAD-aligned, with `boolean isEmployment()`)
- **Employment** (`isEmployment()` = true): `BASE`, `OVERTIME`, `BONUS`, `COMMISSION`, `MILITARY_BASE_PAY`,
  `MILITARY_ENTITLEMENTS`, `SELF_EMPLOYMENT_INCOME` (may be **negative** = a loss), `OTHER_EMPLOYMENT`.
- **Other-source** (`isEmployment()` = false): `ALIMONY`, `CHILD_SUPPORT`, `SOCIAL_SECURITY`, `PENSION`,
  `DISABILITY`, `DIVIDENDS_INTEREST`, `NOTES_RECEIVABLE`, `ROYALTIES`, `TRUST`, `UNEMPLOYMENT`,
  `VA_BENEFITS_NON_EDUCATIONAL`, `PUBLIC_ASSISTANCE`, `FOSTER_CARE`, `SEPARATE_MAINTENANCE`,
  `AUTOMOBILE_ALLOWANCE`, `BOARDER_INCOME`, `HOUSING_ALLOWANCE`, `CAPITAL_GAINS`, `OTHER`.
  (Rental income is REO-side → S6, not here.)

### `IncomeVerification` (new — **extends `TenantScopedEntity`**) — the doc-less tracker
- `loanId` (UUID) · `borrowerId` (UUID, **nullable** — loan-level allowed) · `verificationType`
  (enum **`VOI`/`TAX_TRANSCRIPT`**) · `status` (enum **`NOT_ORDERED`/`ORDERED`/`IN_PROGRESS`/`COMPLETED`/`FAILED`**) ·
  `provider` (String) · `referenceNumber` (String) · `orderedAt` (Instant) · `completedAt` (Instant).
- Written via `IncomeVerificationPort` (stub adapter sets `ORDERED` + a synthetic reference); read back for
  the "Doc-Less Income Verification Results" panel.

## Ports-and-adapters (the new seam)
- **`IncomeVerificationPort`** (interface, `income` module): `order(OrderIncomeVerificationCommand) →
  IncomeVerificationResult`. **`StubIncomeVerificationAdapter`** (returns `ORDERED` + `"STUB-<uuid>"`
  reference, provider `"STUB"`). Real VOE/VOI vendor adapter is a later milestone — only the port is
  committed now, consistent with the Spec-1 ports-first convention.

## API (mirrors the address sub-resource; all tenant + loan scoped)
Employment & income are **borrower-scoped** sub-resources; the grid is **loan-level**:
- `POST/GET/PATCH/DELETE /api/loans/{loanId}/borrowers/{borrowerId}/employments[/{employmentId}]` —
  employment CRUD (DELETE cascades its `IncomeItem`s).
- `POST/GET/PATCH/DELETE /api/loans/{loanId}/borrowers/{borrowerId}/income[/{incomeId}]` — `IncomeItem` CRUD.
- `GET /api/loans/{loanId}/income/summary` — **the grid**: rows
  `{ borrowerId, borrowerName, incomeType, employerName, monthlyAmount }` across all borrowers +
  **`totalMonthlyIncome`** (sum). Loan-authorized.
- `POST /api/loans/{loanId}/income/verifications` `{ verificationType, borrowerId? }` — stub "Order Income"
  → returns the tracker row. `GET /api/loans/{loanId}/income/verifications` — results panel.

## Validation
`monthlyAmount` ≥ 0 (except `incomeType = SELF_EMPLOYMENT_INCOME`, which may be negative) · `employerName`
required when `!selfEmployed` · `ownershipShare` required when `selfEmployed`, else must be null · `endDate` required
when `employmentStatus = PREVIOUS` and must be ≥ `startDate` · `incomeType` + `employmentId` consistency
(employment type ⇒ employment of same borrower; other type ⇒ null) · `state` via `UsStateCode` enum ·
`verificationType` via enum. Fields stay **optional at capture** (early data entry; required-for-submission
enforced later).

## Testing
- **Crown jewel — "TOTAL across borrowers":** two borrowers on one loan, each with employment + other income;
  `GET …/income/summary` returns every row and a `totalMonthlyIncome` equal to the exact sum (assert against
  the JDBC-summed amounts) — proves the loan-level aggregate over the tenant-scoped rows.
- Employment CRUD + ordinal; self-employed rules (ownership required/forbidden); PREVIOUS endDate rule.
- `IncomeItem` CRUD; **employment-vs-other consistency** (employment type with null `employmentId` → 400;
  other type with an `employmentId` → 400; employment of a *different* borrower → 400/404).
- DELETE employment **cascades** its income items.
- **Tenant + loan scope:** cross-org → 404 (via `findByIdAndOrgId`), cross-loan borrower → 404, no token → 401.
- **Verification:** stub order returns `ORDERED` + reference; results list reads back; tenant-scoped.
- **RLS:** new tables (`employment`, `income_item`, `income_verification`) covered by the Spec-2 RLS IT
  pattern (`SET ROLE app_user` → cross-org rows invisible).
- Unit: `IncomeType.isEmployment()` partitioning; summary total math. All Spec-1/2/3 tests stay green.

## Migration `V7__employment_income.sql`
- `CREATE TABLE employment (… org_id …)` — columns above; index on `(org_id, borrower_id)` + `(org_id, loan_id)`;
  FK `borrower_id → borrower_party(id)`. **RLS FORCE + `WITH CHECK` `tenant_isolation` policy** (NULLIF-empty
  GUC pattern from V5/V6); grant `app_user` SELECT/INSERT/UPDATE/DELETE.
- `CREATE TABLE income_item (… org_id …)` — index `(org_id, borrower_id)`, `(org_id, loan_id)`,
  `(org_id, employment_id)`; FK `employment_id → employment(id) ON DELETE CASCADE`. RLS + grants as above.
- `CREATE TABLE income_verification (… org_id …)` — index `(org_id, loan_id)`. RLS + grants as above.

## Module placement
- **`income` (new):** `Employment`, `IncomeItem`, `IncomeType`, `IncomeVerification` + their enums; repos;
  `EmploymentService`, `IncomeService`, `IncomeSummaryService`, `IncomeVerificationService`;
  `IncomeVerificationPort` + `StubIncomeVerificationAdapter`; controllers + DTOs.
- **`app`:** add `include("income")` (settings), `:income` dependency, `V7` migration; `@ComponentScan`/
  `@EntityScan`/`@EnableJpaRepositories` already cover `com.msfg.los` (verify the new module is picked up).
- **`platform`:** reuse `UsStateCode`, `TenantScopedEntity`, `LoanAccessGuard`, `TenantContext` — no changes.

## Out of scope / deferred
Income Calculator (variable-income averaging / YTD worksheet); DTI (S6 calc engine — needs liabilities);
real VOI/VOE vendor integration; required-for-submission validation; rental income (REO-side, S6).

**Implementation plan:** `docs/superpowers/plans/2026-06-05-los-spec4-employment-income.md` (next).
