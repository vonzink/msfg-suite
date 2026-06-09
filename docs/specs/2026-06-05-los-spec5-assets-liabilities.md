# MSFG LOS — Spec 5: Assets & Liabilities

> The 1003's **Financial Information** section (URLA Section 2), UWM EASE's `Asset` / `LoanLiabilities`
> analog. Two more entity-grid sections on the multi-tenant / loan-scoped spine, and the first to carry the
> **DTI include/exclude inputs** the Spec-6 calc engine will consume. Structurally a near-clone of the Spec-4
> `income` module — `Asset`≈`IncomeItem`, `Liability`≈`IncomeItem`+DTI, `AssetVerification`≈`IncomeVerification`.

## Context
Captures each borrower's **assets** (bank/retirement/investment accounts + other assets & credits like
earnest money / gifts) and **liabilities** (revolving/installment/lease/mortgage debts + other monthly
obligations like alimony/child support), then surfaces the two UWM grids —
**ASSET TYPE · ACCOUNT# · BALANCE · DEPOSITOR · VERIFIED → TOTAL ASSETS** and
**LIABILITY TYPE · ACCOUNT# · BALANCE · PAYMENT · CREDITOR · DTI(Include/Exclude) → TOTAL LIABILITIES–PAYMENTS** —
at loan level. Liabilities carry the **DTI exclusion decision** (include flag + reason + months-remaining) that
Spec 6 reads to compute the ratio. A thin **doc-less asset-verification tracker** (VOA) lands behind a port
(stub today), honoring UWM's "Doc-Less Asset Verification Results" panel.

New **`financials` module** mirroring `income` exactly (domain/repo/service/web/dto + verification/), depending
on `:platform`, `:loan-core`, `:parties`. Financial data is **not SSN-class** → **no column-encryption**; tenant
isolation + loan/borrower scoping + auth protect it. Inherits `org_id`/`@TenantId`/RLS. Migration **V8**.

## Locked decisions
| Area | Decision |
|---|---|
| Scope | **URLA §2a–2d** — assets (accounts + other-credits), liabilities (credit debts + other-expenses), loan-level grids/totals, **DTI include/exclude inputs**, thin VOA tracker |
| Tables | **Unified** — one `Asset` table (`AssetType` spans account + other-credit types) + one `Liability` table (`LiabilityType` spans credit + other-expense types). Mirrors the Spec-4 unified `IncomeItem` decision |
| DTI inputs | Liability carries `includeInDti` (boolean, default true) + `exclusionReason` (enum, required when excluded) + `monthsRemaining` (Integer, nullable). **The DTI *ratio* itself is Spec 6** — S5 only captures inputs |
| Verification | **Thin VOA tracker** behind `AssetVerificationPort` + `StubAssetVerificationAdapter` ("Order Assets" → `ORDERED`); no real vendor |
| Module | New **`financials`** module (deps `:platform`,`:loan-core`,`:parties`); mirrors `income` layout |
| Tenancy | Entities **extend `TenantScopedEntity`** (auto `org_id` + RLS); single-entity loads use `findByIdAndOrgId` |
| Crypto | **None** (financial data, not SSN-class) |
| Deferred | Joint/co-borrower ownership (borrower-scoped for now); **REO** (Spec 6, separate); the **DTI ratio calc** (Spec 6 engine); real VOA vendor |

## Data model (module `financials`, package `com.msfg.los.financials`)

### `Asset` (new — **extends `TenantScopedEntity`**) — unified, mirrors `IncomeItem`
Borrower-scoped (`loanId`+`borrowerId` denormalized): `ordinal` · `assetType` (enum `AssetType`) ·
`financialInstitution` (the "depositor") · `accountNumber` (nullable — non-account assets) ·
`cashOrMarketValue` (BigDecimal) · `verified` (boolean).

### `Liability` (new — **extends `TenantScopedEntity`**) — unified, with DTI inputs
`ordinal` · `liabilityType` (enum `LiabilityType`) · `creditorName` · `accountNumber` (nullable) ·
`unpaidBalance` (BigDecimal, **nullable** — expense-type liabilities have no balance) · `monthlyPayment`
(BigDecimal) · **`includeInDti`** (boolean, default true) · **`exclusionReason`** (enum `DtiExclusionReason`,
nullable) · **`monthsRemaining`** (Integer, nullable — supports the <10-payments rule).

### `AssetVerification` (new — **extends `TenantScopedEntity`**) — doc-less VOA tracker
`loanId` · `borrowerId` (nullable) · `verificationType` (enum `AssetVerificationType` { `VOA` }) · `status`
(enum `VerificationStatus`) · `provider` · `referenceNumber` · `orderedAt` (Instant) · `completedAt` (Instant).

### Enums (ULAD-aligned)
- **`AssetType`** (with `boolean isAccount()`): *accounts* (`isAccount()`=true) — `CHECKING`, `SAVINGS`,
  `MONEY_MARKET`, `CERTIFICATE_OF_DEPOSIT`, `MUTUAL_FUND`, `STOCKS`, `BONDS`, `RETIREMENT`, `TRUST_ACCOUNT`,
  `BRIDGE_LOAN_NOT_DEPOSITED`, `CASH_VALUE_OF_LIFE_INSURANCE`, `INDIVIDUAL_DEVELOPMENT_ACCOUNT`; *other assets
  & credits* (false) — `EARNEST_MONEY`, `EMPLOYER_ASSISTANCE`, `GIFT`, `GIFT_OF_EQUITY`, `GRANT`,
  `PROCEEDS_FROM_SALE_OF_NON_REAL_ESTATE`, `PROCEEDS_FROM_SALE_OF_REAL_ESTATE`, `SECURED_BORROWED_FUNDS`,
  `UNSECURED_BORROWED_FUNDS`, `RENT_CREDIT`, `SWEAT_EQUITY`, `TRADE_EQUITY`, `OTHER`.
- **`LiabilityType`** (with `boolean isExpense()`): *credit* (`isExpense()`=false) — `REVOLVING`, `INSTALLMENT`,
  `LEASE`, `OPEN_30_DAY`, `MORTGAGE_LOAN`, `HELOC`, `TAXES`; *other liabilities & expenses* (true) — `ALIMONY`,
  `CHILD_SUPPORT`, `SEPARATE_MAINTENANCE`, `JOB_RELATED_EXPENSES`, `OTHER`.
- **`DtiExclusionReason`**: `PAID_AT_OR_BEFORE_CLOSING`, `PAID_BY_OTHER_PARTY`, `LESS_THAN_10_MONTHS_REMAINING`,
  `OMITTED_DUPLICATE`, `OTHER`.
- **`AssetVerificationType`** { `VOA` } · **`VerificationStatus`** { `NOT_ORDERED`, `ORDERED`, `IN_PROGRESS`,
  `COMPLETED`, `FAILED` } (financials-local; *future cleanup: promote a shared `VerificationStatus` to
  `platform` once a third verification type appears — out of scope here, would touch the `income` module*).

## Ports-and-adapters (the new seam)
**`AssetVerificationPort`** (interface, `financials`): `order(OrderAssetVerificationCommand) →
AssetVerificationResult`. **`StubAssetVerificationAdapter`** (`@Component`, only impl) returns `ORDERED` +
`"STUB-<uuid>"` + provider `"STUB"`. Real VOA vendor is a later milestone. Mirrors `IncomeVerificationPort`.

## API (borrower-scoped sub-resources + loan-level summaries; all tenant + loan scoped)
- `POST/GET/PATCH/DELETE /api/loans/{loanId}/borrowers/{borrowerId}/assets[/{assetId}]`
- `POST/GET/PATCH/DELETE /api/loans/{loanId}/borrowers/{borrowerId}/liabilities[/{liabilityId}]`
- `GET /api/loans/{loanId}/assets/summary` → rows `{borrowerId, borrowerName, assetType, financialInstitution,
  cashOrMarketValue}` + **`totalAssets`** (Σ value across borrowers).
- `GET /api/loans/{loanId}/liabilities/summary` → rows `{borrowerId, borrowerName, liabilityType, creditorName,
  monthlyPayment, includeInDti}` + **`totalMonthlyPayments`** (Σ all) + **`dtiMonthlyPayments`** (Σ where
  `includeInDti`) + **`totalUnpaidBalance`** (Σ balances). Both payment totals because excluded liabilities
  don't count toward DTI.
- `POST /api/loans/{loanId}/assets/verifications` `{verificationType, borrowerId?}` (stub VOA) · `GET` (list).

## Validation (mirror the Spec-4 rules)
- **Liability DTI pairing:** `includeInDti = false` ⇒ `exclusionReason` **required** (else 400); `= true` ⇒
  `exclusionReason` **must be null** (else 400). **Recoverable on PATCH** — setting `includeInDti = true` clears
  `exclusionReason` (the Spec-4 paired-field fix). `monthlyPayment` ≥ 0; `unpaidBalance` ≥ 0 when present;
  `monthsRemaining` ≥ 0 when present.
- **Asset:** `cashOrMarketValue` ≥ 0.
- Verification order validates `borrowerId` membership when provided (the Spec-4 cross-tenant fix: a `borrowerId`
  must belong to the loan/org).
- Fields optional at capture (required-for-submission enforced later).

## Testing (mirror Spec 4)
- **Crown jewels — totals across borrowers vs independent JDBC sums:**
  - `assets/summary`: 2 borrowers, several assets → `totalAssets` equals `SELECT SUM(cash_or_market_value)
    WHERE loan_id=?`.
  - `liabilities/summary`: mix of included + excluded liabilities → `totalMonthlyPayments` = Σ all,
    `dtiMonthlyPayments` = Σ included only (assert the excluded one is omitted from the DTI total but present in
    the rows), each matched against independent JDBC sums.
- Asset CRUD + ordinal; Liability CRUD; **DTI pairing** (exclude w/o reason → 400; include w/ reason → 400;
  PATCH include→true clears reason → 200). Negative value → 400.
- Tenant + loan scope (cross-org → 404 via `findByIdAndOrgId`, cross-loan borrower → 404, no token → 401).
- VOA stub order → `ORDERED` + reference; list reads back; foreign `borrowerId` → 400.
- **RLS:** new tables (`asset`, `liability`, `asset_verification`) covered by the Spec-2 `RlsIT` pattern.
- Unit: `AssetType.isAccount()` / `LiabilityType.isExpense()` partitions; the three liability-summary totals;
  the assets total. All Spec 1–4 tests stay green.

## Migration `V8__assets_liabilities.sql` (mirror V7's idiom exactly)
- `CREATE TABLE asset (… org_id …)` — index `(org_id, borrower_id)`, `(org_id, loan_id)`; FK
  `borrower_id → borrower_party(id)`. **RLS FORCE + `WITH CHECK` `tenant_isolation`** (NULLIF-empty GUC);
  grant `app_user` SELECT/INSERT/UPDATE/DELETE.
- `CREATE TABLE liability (… org_id …, include_in_dti boolean not null default true, exclusion_reason varchar(40),
  months_remaining int …)` — same indexes/FK/RLS/grants.
- `CREATE TABLE asset_verification (… org_id …)` — index `(org_id, loan_id)`; RLS + grants.

## Module placement
- **`financials` (new):** `Asset`, `Liability`, `AssetVerification` + enums; repos; `AssetService`,
  `LiabilityService`, `AssetSummaryService`, `LiabilitySummaryService`, `AssetVerificationService`;
  `AssetVerificationPort` + `StubAssetVerificationAdapter`; controllers + DTOs.
- **`app`:** `include("financials")` (settings) + `:financials` dep; `V8` migration; component scan already covers `com.msfg.los`.
- **`platform`/`parties`/`loan-core`:** reused unchanged (`TenantScopedEntity`, `LoanAccessGuard`, `TenantContext`, `BorrowerRepository`).

## Out of scope / deferred
Joint/co-borrower ownership; REO + rental income (Spec 6); the DTI **ratio** + housing-expense + cash-to-close
calc (Spec 6 engine — consumes these inputs); real VOA vendor; required-for-submission validation.

**Implementation plan:** `docs/superpowers/plans/2026-06-05-los-spec5-assets-liabilities.md` (next).
