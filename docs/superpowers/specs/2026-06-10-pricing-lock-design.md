# Spec — Products & Pricing / Rate Lock (`pricing` module)

**Date:** 2026-06-10 · **Migration:** V14 · **Branch:** `feat/pricing-lock`
**Drivers:** FE work-order §4 (`msfg-suite-web/docs/HANDOFF-BACKEND-REQUESTS.md`), EASE wireframe §M
(Products & Pricing), ROADMAP Milestone 3 row 1. FE screen already shipped
(`src/features/pricing/PricingPage.tsx`) — structural stub, no adapter swap needed; it reads the typed
client after `gen:api`.

**Design decisions (Zack, 2026-06-10):** current-lock + append-only event audit · Control Your Price =
the price-and-lock action · stub engine derives adjustments from real loan data · **no write-back** to
`Loan.interestRate` (the 1003 §4 rate changes only via explicit user/CoC action).

---

## 1. Scope

Delivers the EASE Products & Pricing screen contract:
- Lock state grid: Lock Status · Commitment Period · Interest Rate · Compensation Payer Type ·
  Interviewer Email · Total Loan Amount · Locked By · Lock Date · Current Expiration (+ Exact Rate Type).
- Pricing Breakdown table: Base Price → adjustments → Final Price → Compensation → Final Price After
  Compensation (name / adjustment % / dollar amount).
- Four lock actions: **control-your-price, extend, rate-change, relock** (the FE's 4 buttons).
- **Generate Lock Confirmation** → stored HTML document (Document Manager integration).
- Lock event history endpoint (compliance audit, EASE parity).

**Out of scope (deferred):** real pricing vendor adapter (Optimal Blue et al.), lock cancel/float,
business-day expiration calendars, PDF rendering, lock-desk roles/approval queue, lock fee billing,
re-price-vs-CoC TRID fee-tolerance integration (Milestone-3 CoC engine row), push notifications.

## 2. Module & dependencies

New Gradle module **`pricing`** — `com.msfg.los.pricing.{domain,port,engine,repo,service,web,web/dto}`,
mirroring `coc`/`fees` layout. Registered in `settings.gradle.kts` + added to `app` dependencies.

Dependencies: `platform`, `loan-core`, **`qualification`** (reuse `LoanCalculationService` for
`totalLoanAmount` + `ltv` — never re-derive the math), **`documents`** (letter storage).

Additive changes in `documents` (both safe for OpenAPI):
1. `DocumentType` gains `LOCK_CONFIRMATION`.
2. `DocumentService` gains `Document storeGenerated(UUID loanId, DocumentType type, String filename,
   String contentType, byte[] bytes)`; `generatePreApproval` refactors to call it internally
   (behavior unchanged). Pricing renders its own letter via `LockConfirmationGenerator` (in `pricing`)
   and stores through this method — `documents` never learns lock internals.

## 3. Domain model (migration V14 — verify V14 is still next before authoring)

All entities extend `TenantScopedEntity` (`org_id` + `@TenantId`); repos load by
`findByIdAndOrgId` / `findByLoanId…`. DDL mirrors V12/V13 conventions: uuid PKs, `loan_id` FK +
index, `org_id uuid not null`, **FORCE RLS + WITH CHECK** policies, `app_user` grants.

### `rate_lock` — current lock, 1:1 per loan (`loan_id UNIQUE`)
| column | type | notes |
|---|---|---|
| `locked_rate` | numeric | the locked note rate (%) |
| `commitment_days` | int | 15/30/45/60/90 |
| `lock_date` | timestamptz | when (re)locked |
| `expiration_date` | date | lockDate-date + commitmentDays; extends add to it |
| `extension_days_total` | int default 0 | cumulative extension days (drives the Extension Fee row) |
| `compensation_payer_type` | text | `LENDER_PAID` \| `BORROWER_PAID` |
| `locked_by` | text | principal name at lock time |
| `interviewer_email` | text null | JWT `email` claim if present, else null |

No row ⇒ NOT_LOCKED. **Effective status is computed at read time** (no scheduler):
`EXPIRED` iff `expiration_date < today(UTC)` — expiration day itself is still LOCKED.
`app_user`: SELECT/INSERT/UPDATE/DELETE.

### `pricing_adjustment` — persisted quote snapshot (full replace per pricing event)
`ordinal int` (1..n per quote, single tx delete+insert) · `name text` · `row_type text`
(`BASE` | `ADJUSTMENT` | `FINAL` | `COMPENSATION` | `FINAL_AFTER_COMP`) · `adjustment_percent numeric`
(signed, 3dp) · `dollar_amount numeric` (signed, 2dp). `app_user`: SELECT/INSERT/UPDATE/DELETE.

### `lock_event` — append-only audit
`action text` (`CONTROL_YOUR_PRICE` | `EXTEND` | `RATE_CHANGE` | `RELOCK`) · `actor text` ·
`occurred_at timestamptz` · post-action snapshot: `rate numeric`, `commitment_days int`,
`expiration_date date`. **`app_user`: SELECT/INSERT only** (PiiAccessLog treatment).

Enums (`pricing.domain`): `RateLockStatus` {NOT_LOCKED, LOCKED, EXPIRED} (response-only; only
LOCKED-state rows persist in v1), `LockAction`, `CompensationPayerType`, `PricingRowType`.
⚠️ springdoc keys schemas by **simple name** — at build, grep-verify these names are repo-unique.

## 4. Lock state machine

Effective state: **NOT_LOCKED** (no row) → **LOCKED** (row, `expiration_date ≥ today`) →
**EXPIRED** (row, `expiration_date < today`).

| action | allowed states | body | effect |
|---|---|---|---|
| `control-your-price` | NOT_LOCKED, LOCKED | `{rate, commitmentDays, compensationPayerType}` | quote → upsert lock (lockDate=now, expiration=lockDate's UTC date + commitmentDays, extensionDaysTotal=0), replace adjustments, append event |
| `extend` | LOCKED | `{additionalDays}` | expiration += days; extensionDaysTotal += days; **full re-quote** (Extension Fee row appears); append event |
| `rate-change` | LOCKED | `{rate}` | lockedRate = rate; full re-quote; lockDate/expiration **unchanged**; append event |
| `relock` | EXPIRED | `{rate, commitmentDays, compensationPayerType}` | fresh lock (lockDate=now, expiration=lockDate's UTC date + commitmentDays, extensionDaysTotal=0), replace adjustments, append event |

Errors:
- Wrong state → **409** code `LOCK_STATE_CONFLICT` (message names current state + action).
- Loan status terminal (`LoanStatus.isTerminal()`: FUNDED/WITHDRAWN/CANCELLED/DENIED) → **409**
  `LOCK_STATE_CONFLICT` for all four actions (reads unaffected).
- `totalLoanAmount` null (no `baseLoanAmount`) → **409** code `LOAN_NOT_PRICEABLE` (dollar amounts
  need a basis). Null FICO/LTV do **not** block (no-data bucket, §5).
- Bean validation → **400** flat envelope with `fields` map; **each rule its own check — no `&&`
  collapse**: `rate` `@NotNull @DecimalMin("0.125") @DecimalMax("25.000")` · `commitmentDays`
  `@NotNull` + ∈ {15,30,45,60,90} (service check → `fields.commitmentDays`) · `compensationPayerType`
  `@NotNull` · `additionalDays` `@NotNull @Min(1) @Max(60)`.
- CYP+relock share request DTO `LockTermsRequest`; rate-change `RateChangeRequest`; extend
  `ExtendLockRequest`.
- Concurrency: racing first-locks hit `loan_id UNIQUE` → `DataIntegrityViolation` → global **409**
  (fees precedent, already in `GlobalExceptionHandler`).
- Invalid enum constant in body (e.g. bad `compensationPayerType`) currently surfaces per the
  platform-wide Jackson behavior (FE handoff §5 micro-nit; a platform fix is in flight in another
  session). Pricing ITs do **not** assert that branch — it's platform-owned.

Tenancy/scoping: every endpoint guards `assertCanAccess(loanService.get(loanId))` (reo/fees/coc
pattern); no role gate beyond loan access (locking is an LO action in EASE). `lockedBy` = principal
name; `interviewerEmail` = JWT `email` claim or null.

## 5. Pricing engine port + stub adapter

```java
public interface PricingEnginePort {
    PriceQuote quote(PricingQuoteRequest request);
}
```

`PricingQuoteRequest` (record): `rate, commitmentDays, compensationPayerType, extensionDaysTotal,
fico (Integer|null), ltv (BigDecimal|null), loanPurpose (LoanPurposeType|null), totalLoanAmount`.
`PriceQuote` (record): ordered `List<QuoteRow>`; `QuoteRow` = `{name, rowType, percent}`.
Service computes `dollarAmount = money2(percent/100 × totalLoanAmount)`; percents are 3dp.
Inputs sourced: `fico` = `Loan.qualifyingCreditScore`; `ltv` + `totalLoanAmount` from
`qualification.LoanCalculationService`.

`StubPricingEngineAdapter` (`@Component`, only impl; real vendor swaps in later, zero service change).
**Deterministic table — ITs hand-compute against exactly this:**

1. **BASE** "Base Price": `−((7.000 − rate) × 0.500) − ((commitmentDays − 15) / 15 × 0.125)` → 3dp.
   *(rate 6.500, 30-day → −0.375)*
2. **ADJUSTMENT** "FICO/LTV Adjustment" (name suffix "(no data)" and **0.000** when fico or ltv null):

   | | ltv ≤ 60 | 60 < ltv ≤ 80 | ltv > 80 |
   |---|---|---|---|
   | fico ≥ 760 | 0.000 | 0.250 | 0.375 |
   | 700–759 | 0.250 | 0.500 | 0.750 |
   | < 700 | 0.500 | 1.000 | 1.500 |
3. **ADJUSTMENT** "Purpose Adjustment": `REFINANCE` +0.250 · `CONSTRUCTION` +0.500 ·
   `PURCHASE`/`OTHER`/null 0.000.
4. **ADJUSTMENT** "Extension Fee (N days)": only when `extensionDaysTotal > 0`;
   `N × 0.020` (N = cumulative days) → 3dp.
5. **FINAL** "Final Price": Σ rows 1–4.
6. **COMPENSATION** "Compensation": `LENDER_PAID` +1.000 · `BORROWER_PAID` 0.000.
7. **FINAL_AFTER_COMP** "Final Price After Compensation": row 5 + row 6.

## 6. Endpoints (all `ApiResponse`-enveloped; additive-only)

| method/path | returns | notes |
|---|---|---|
| `GET /api/loans/{loanId}/pricing` | `PricingResponse` | **always 200**, even NOT_LOCKED |
| `GET /api/loans/{loanId}/pricing/adjustments` | `List<PricingAdjustmentResponse>` | ordinal order; `[]` if never priced |
| `GET /api/loans/{loanId}/pricing/lock/history` | `List<LockEventResponse>` | `occurredAt ASC, id ASC` |
| `POST /api/loans/{loanId}/pricing/lock/control-your-price` | `PricingResponse` (200) | body `LockTermsRequest` |
| `POST /api/loans/{loanId}/pricing/lock/extend` | `PricingResponse` (200) | body `ExtendLockRequest` |
| `POST /api/loans/{loanId}/pricing/lock/rate-change` | `PricingResponse` (200) | body `RateChangeRequest` |
| `POST /api/loans/{loanId}/pricing/lock/relock` | `PricingResponse` (200) | body `LockTermsRequest` |
| `POST /api/loans/{loanId}/pricing/lock-confirmation` | `DocumentResponse` (**201**) | 409 `LOCK_STATE_CONFLICT` unless effective LOCKED |

`PricingResponse`: `lockStatus` (effective) · `interestRate` (**lockedRate when a lock row exists —
LOCKED or EXPIRED — else `Loan.interestRate`**, may be null) · `commitmentDays` · `lockDate` ·
`currentExpiration` · `extensionDaysTotal` · `compensationPayerType` · `lockedBy` ·
`interviewerEmail` · `totalLoanAmount` (qualification calc) · `exactRateType`
(`Loan.amortizationType`: FIXED | ADJUSTABLE_RATE | OTHER, null if unset). Lock fields null when
NOT_LOCKED.

`PricingAdjustmentResponse`: `ordinal, name, rowType, adjustmentPercent, dollarAmount`.
`LockEventResponse`: `id, action, actor, occurredAt, rate, commitmentDays, expirationDate`.

### Lock confirmation letter
`LockConfirmationGenerator` (pricing module) renders self-contained HTML mirroring
`PreApprovalLetterGenerator`'s style: loan number, product header (mortgageType + term/12 yr), the
9 grid fields, full breakdown table, generated-at + lockedBy footer. Stored via
`DocumentService.storeGenerated(...)`: type `LOCK_CONFIRMATION`, category `"PRICING"`, filename
`lock-confirmation-{loanNumber}-{yyyyMMdd}.html`, contentType `text/html`. Listable via existing
`GET /documents?type=LOCK_CONFIRMATION`; downloadable via existing binary endpoint.

## 7. Testing (TDD; crown-jewel ITs assert hand-computed values)

Unit (`pricing`): stub-engine golden table (≥6 cases: bucket corners, null-FICO/LTV no-data row,
purpose rows, extension row, comp payer both ways, FINAL/FINAL_AFTER_COMP sums) · expiration
boundary (expiration == today ⇒ LOCKED; yesterday ⇒ EXPIRED) · action×state matrix.

ITs (`app`, MockMvc per coc/fees pattern):
- CYP happy path: lock grid fields + adjustments persisted, **asserted against hand-computed stub
  values**; lockDate/expiration sane; event appended with actor.
- Extend: expiration += days, extensionDaysTotal set, Extension Fee row present, FINAL re-summed.
- Rate-change: new rate, expiration unchanged, re-quoted table.
- Relock: repo-force `expiration_date` into the past → GET shows EXPIRED → relock succeeds, resets
  extensionDaysTotal; relock on active lock → 409.
- Wrong-state 409s (each action) + terminal-loan 409 + LOAN_NOT_PRICEABLE 409 (no baseLoanAmount).
- Validation 400s: **one test per rule branch**, asserting `fields.<name>` in the flat envelope.
- GET /pricing on virgin loan: 200 NOT_LOCKED, nulls, loan rate passthrough, totalLoanAmount.
- Cross-tenant: foreign-org JWT → 404 on reads and actions (`findByIdAndOrgId` discipline).
- Lock-confirmation: 201, appears in `GET /documents?type=LOCK_CONFIRMATION`, content downloads,
  contains locked rate; unlocked → 409.
- RLS coverage for all 3 tables (mirror documents RLS IT); `lock_event` insert-only grant verified.
- `OpenApiDocsIT` stays green (duplicate simple-name guard).

Estimate ~30 new tests on top of 252.

## 8. Build-process notes

- **Working tree is dirty** (Zack's REO-fix + racing-session edits in `reo`/`coc`/`platform`/
  `qualification`): pricing work overlaps none of those files; **stage selectively** — never commit
  them on this branch. Don't restart any running bootRun until merge time.
- Verify **V14** is still the next migration immediately before authoring (parallel sessions).
- Subagent-driven TDD build, fresh agent per task; front-load known traps (no `&&`-collapsed
  validation, `fields.<name>` assertions, HashMap over `Collectors.toMap`, borrower/loan membership
  guards, `findByIdAndOrgId`).
- 2-stage review (spec compliance + code quality) + **opus final pass** (money-adjacent math).
- Per-merge loop: update `docs/frontend-integration.md`; dated reply in
  `msfg-suite-web/docs/HANDOFF-FROM-BACKEND.md`; restart local backend; FE runs `gen:api`.
