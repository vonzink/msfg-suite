# MSFG LOS — Fees Module (frontend §1)

> Loan-scoped fee ledger matching the EASE/Loan-Estimate sections. The frontend UI is already shipped behind
> `LocalFeesAdapter`; when this lands it regenerates the typed client and swaps adapters. Mirrors
> `msfg-suite-web/src/features/fees/model.ts`. Money-critical (totals) → opus review + crown-jewel ITs.

## Context
A loan has **fee line items** (keyed by section+label) and **invoice entries** (keyed by fee label). The section
catalogs (`FEE_SECTIONS`, `INVOICE_FEES`) are **static frontend templates** — the backend stores only the entered
values. New **`fees` module** (deps `:platform`, `:loan-core`), **loan-scoped** (guard via
`accessGuard.assertCanAccess(loanService.get(loanId))` — mirror `ReoService`; no borrower scope). Migration **V11**.

## Locked decisions
| Area | Decision |
|---|---|
| Module | New **`fees`** (deps platform, loan-core); loan-scoped |
| Fee line items | **Id-based CRUD** (POST→201, 409 on duplicate `section+label`; PATCH/DELETE by id; GET list); unique `(org_id, loan_id, section, label)` |
| Totals | **Server-computed** `GET …/fees/totals` — per-section + 5 LE categories, mirroring the frontend formulas EXACTLY (`escrowPrepaids = F+G`; sum of `amount`). `sectionTotals` keyed by **section name `String`** (not an enum-keyed Map — avoids the springdoc non-String-key risk) |
| Invoices | List + **PUT-upsert by `feeLabel`**; unique `(org_id, loan_id, fee_label)`. Wire field `final` (Java keyword) → entity/DTO field `finalized` + `@JsonProperty("final")` so the JSON contract stays `final` |
| Money | `BigDecimal` scale 2; amounts ≥ 0 |
| Tenancy | entities extend `TenantScopedEntity`; loads via `findByIdAndOrgId`; RLS on both tables |
| Deferred | Invoice file-upload binding (with Document Manager); the static section/fee catalogs (frontend owns them) |

## Data model (module `fees`, package `com.msfg.los.fees`)

### `FeeSection` enum
`SELLER_CONCESSIONS, A, B, C, E, F, G, PRORATIONS, H, K, L, REC` (mirrors `SectionId`).

### `FeeLineItem` (new — **extends `TenantScopedEntity`**, loan-scoped)
`loanId` · `ordinal` · `section` (`@Enumerated(STRING) FeeSection`) · `label` (String) · `amount` (BigDecimal) ·
`sellerConcession` (BigDecimal) · `percent` (BigDecimal, nullable — origination items). **Unique `(org_id, loan_id,
section, label)`.**

### `InvoiceEntry` (new — **extends `TenantScopedEntity`**, loan-scoped)
`loanId` · `feeLabel` (String) · `amountDisclosed` · `invoiceAmount` · `borrowerPoc` (BigDecimal) ·
`finalized` (boolean, default false; getter `isFinalized()`) · `comment` (String). **Unique `(org_id, loan_id,
fee_label)`.**

## API (loan-scoped; cross-org → 404, no token → 401)
- **Fee line items:** `POST /api/loans/{loanId}/fees` `{section, label, amount, sellerConcession, percent?}` → 201
  (409 `CONFLICT` if `section+label` already exists for the loan) · `GET /api/loans/{loanId}/fees` (all items,
  ordinal order) · `PATCH /api/loans/{loanId}/fees/{feeId}` (amount/sellerConcession/percent — `section`/`label`
  are identity, not patched) · `DELETE /api/loans/{loanId}/fees/{feeId}` → 204.
- **Totals:** `GET /api/loans/{loanId}/fees/totals` → `{ sectionTotals: { "A": …, "B": …, … }, categoryTotals:
  { origination, didNotShop, didShop, taxesGov, escrowPrepaids } }`. `sectionTotals[name] = Σ amount` for that
  section (every `FeeSection` present, default 0). `categoryTotals`: `origination=A`, `didNotShop=B`,
  `didShop=C`, `taxesGov=E`, **`escrowPrepaids=F+G`**. (Spring matches the literal `/totals` before `/{feeId}`.)
- **Invoices:** `GET /api/loans/{loanId}/fees/invoices` (list) · `PUT /api/loans/{loanId}/fees/invoices`
  `{feeLabel, amountDisclosed, invoiceAmount, borrowerPoc, "final": bool, comment}` → upsert by `feeLabel`
  (find-or-create, full replace), 200.

## Validation
`amount`, `sellerConcession`, `amountDisclosed`, `invoiceAmount`, `borrowerPoc` ≥ 0; `percent` ≥ 0 when present
(each its own `if`/throw → 400). `section`+`label` required on add. Duplicate `section+label` on POST →
`ConflictException` (409). Fields otherwise optional.

## Testing
- **Fee CRUD:** add → 201 + echo + `ordinal=0`; **duplicate `section+label` → 409**; 2nd distinct item ordinal=1;
  PATCH amount leaves section/label unchanged; negative amount → 400 (`$.message` ~ "amount"); DELETE → 204 + gone;
  cross-org → 404; no token → 401.
- **Totals — crown jewel:** seed fees `A` = 100+200, `B` = 50, `C` = 75, `E` = 30, `F` = 400, `G` = 600; `GET
  …/fees/totals` → `sectionTotals.A == 300`, `.F == 400`, `.G == 600`; `categoryTotals.origination == 300`,
  `.taxesGov == 30`, **`.escrowPrepaids == 1000`** (F+G). Assert each against an independent JDBC `SUM(amount)`
  (incl. a JDBC check that `escrowPrepaids` = `SUM where section in ('F','G')`). Empty loan → all totals 0 (no 500).
- **Invoice upsert:** PUT `{feeLabel:"Appraisal Fee", "final":true, …}` → 200; `GET …/invoices` → the row, with
  **`final == true`** (the `@JsonProperty` round-trips the keyword field); 2nd PUT same `feeLabel` (different
  values) → list length still 1 (upsert, JDBC `count(*) where fee_label=? == 1`); cross-org → 404.
- **RLS:** `fee_line_item` + `invoice_entry` covered by the Spec-2 `RlsIT` pattern.
- **`OpenApiDocsIT` stays green** — new DTOs have unique simple names; `sectionTotals` is `Map<String,BigDecimal>`
  (string keys, springdoc-safe); `@JsonProperty("final")` is a valid schema property name.
- Unit: the totals computation (section sums + `escrowPrepaids=F+G`).

## Migration `V11__fees.sql`
- `CREATE TABLE fee_line_item (… org_id …, loan_id uuid not null, ordinal int not null default 0, section
  varchar(40) not null, label varchar(255) not null, amount numeric(15,2), seller_concession numeric(15,2),
  percent numeric(9,4), … audit)`; `unique (org_id, loan_id, section, label)`; index `(org_id, loan_id)`; RLS
  FORCE + `tenant_isolation` (NULLIF-empty GUC) + grant app_user CRUD.
- `CREATE TABLE invoice_entry (… org_id …, loan_id uuid not null, fee_label varchar(255) not null, amount_disclosed
  numeric(15,2), invoice_amount numeric(15,2), borrower_poc numeric(15,2), finalized boolean not null default
  false, comment varchar(1000), … audit)`; `unique (org_id, loan_id, fee_label)`; index `(org_id, loan_id)`; RLS + grants.

## Module placement
- **`fees` (new):** `FeeSection`, `FeeLineItem`, `InvoiceEntry`; repos; `FeeService`, `FeeTotalsService`,
  `InvoiceService`; controllers + DTOs.
- **`app`:** `include("fees")` + dep; `V11` migration.

## Out of scope / deferred
Invoice file upload (Document Manager milestone); the static section/fee catalogs; per-fee tolerance/CD logic
(that's the Change-of-Circumstance / disclosure work).

**Implementation plan:** `docs/superpowers/plans/2026-06-09-fees-module.md` (next).
