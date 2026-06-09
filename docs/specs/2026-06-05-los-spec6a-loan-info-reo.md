# MSFG LOS — Spec 6A: Loan Information + REO

> The 1003's **Loan Information (§4)** and **Real Estate Owned (§3)** — the last *data-capture* sections of
> the 1003. Pure inputs; the derived ratios live in **Spec 6B (calc engine)**, which consumes everything here
> (loan amounts + value → LTV; proposed housing inputs → PITI; REO net rental → DTI income).

## Context
**Loan Information** extends the `Loan` aggregate Spec 1 started (`Loan` + embedded `SubjectProperty`) with the
§4 fields: rate/term, loan amounts (base/financed/second), value (sales price/appraised), down payment, credit
score, property type/occupancy/units, and the **proposed monthly housing-expense inputs** (taxes/insurance/HOA/MI)
the calc engine needs for PITI. **REO** is a new loan-scoped entity grid (owned properties + market value +
rental income + property expenses + the property's mortgage). No new crypto (financial data, not SSN-class).

## Locked decisions
| Area | Decision |
|---|---|
| Loan Info | **Extend `loan-core` in place** — add §4 fields to `Loan` + `SubjectProperty`; **extend the existing `PATCH /api/loans/{id}` + loan response** (mirrors Spec-3 extending borrower with PII). No new module/endpoint for §4 |
| REO | **New `reo` module** (deps `:platform`,`:loan-core`,`:parties`); loan-scoped `RealEstateOwned` + CRUD + summary |
| REO granularity | **Single associated mortgage (balance+payment) + single `ownerBorrowerId` per REO row.** Multi-lien-per-property + joint ownership deferred |
| Tenancy | New entities/columns inherit `org_id`/`@TenantId`/RLS; REO loads via `findByIdAndOrgId` |
| Crypto | None |
| Deferred | Down-payment-source checkbox list; multi-lien/multi-owner REO; the **ratios** (Spec 6B); housing-expense present-vs-proposed *comparison* (6B derives it) |

## Data model

### `Loan` (extend — `loan-core`)
Add: `documentationType` (enum `DocumentationType`), `interestRate` (BigDecimal, %), `loanTermMonths` (Integer),
`baseLoanAmount` · `financedFeesAmount` · `secondLoanAmount` · `downPaymentAmount` (BigDecimal),
`qualifyingCreditScore` (Integer), and proposed housing inputs `proposedTaxesMonthly`,
`proposedHazardInsuranceMonthly`, `proposedHoaDuesMonthly`, `proposedMortgageInsuranceMonthly` (BigDecimal).
(`noteAmount` stays; 6B derives `totalLoanAmount = baseLoanAmount + financedFeesAmount`.)

### `SubjectProperty` (extend — `loan-core`, embeddable)
Add: `salesPrice` · `appraisedValue` (BigDecimal), `propertyType` (enum `PropertyType`), `occupancyType`
(enum `OccupancyType`), `numberOfUnits` (Integer).

### `RealEstateOwned` (new — `reo`, **extends `TenantScopedEntity`**)
`loanId` · `ownerBorrowerId` (nullable) · `ordinal` · `isSubjectProperty` (boolean) · address (`addressLine1`,
`addressLine2`, `city`, `state` `UsStateCode`, `postalCode`) · `propertyType` (`PropertyType`) ·
`intendedOccupancy` (`OccupancyType`) · `propertyStatus` (enum `ReoPropertyStatus`
RETAINED/SOLD/PENDING_SALE/RENTAL) · `marketValue` · `grossMonthlyRentalIncome` · `monthlyTaxes` ·
`monthlyInsurance` · `monthlyHoaDues` · `monthlyMaintenance` · `mortgageUnpaidBalance` · `mortgageMonthlyPayment`
(all amounts BigDecimal).

### Enums
- **`loan-core`:** `DocumentationType` (FULL, ALTERNATIVE, STREAMLINE_REFINANCE, NO_DOCUMENTATION),
  `PropertyType` (SINGLE_FAMILY, CONDOMINIUM, TOWNHOUSE, PUD, TWO_TO_FOUR_UNIT, MANUFACTURED, COOPERATIVE),
  `OccupancyType` (PRIMARY_RESIDENCE, SECOND_HOME, INVESTMENT) — reused by subject property + REO.
- **`reo`:** `ReoPropertyStatus` (RETAINED, SOLD, PENDING_SALE, RENTAL).

## API
- **Loan Information:** extend `UpdateLoanRequest` + the loan summary/response with the §4 + subject-property
  fields above (all optional — patch). `PATCH /api/loans/{id}` applies them; `GET /api/loans/{id}` returns them.
- **REO:** `POST/GET/PATCH/DELETE /api/loans/{loanId}/reo[/{reoId}]` (loan-scoped CRUD, `LoanAccessGuard`).
  `GET /api/loans/{loanId}/reo/summary` → rows + totals (`totalMarketValue`, `totalGrossMonthlyRentalIncome`,
  `totalMonthlyExpenses` [Σ taxes+insurance+hoa+maintenance], `totalMortgageUnpaidBalance`,
  `totalMonthlyMortgagePayment`).

## Validation
`interestRate` 0–25; `loanTermMonths` 1–480; `qualifyingCreditScore` 300–850; `numberOfUnits` 1–4; all amounts
(loan amounts, REO value/rental/expenses/mortgage) ≥ 0 (each its own `if`/throw). Fields optional at capture.

## Testing
- Loan Info: extend the loan ITs — PATCH sets §4 + subject-property fields, GET returns them; validation 400s
  (rate/term/score/units out of range, negative amount → 400, `$.message` names the field); existing loan
  lifecycle/CRUD tests stay green.
- REO: CRUD + ordinal; `reo/summary` totals proven vs independent JDBC sums (crown jewel — `totalMonthlyExpenses`
  = Σ of the four expense columns); tenant/loan scope (cross-org 404, no-token 401); **RLS** for `reo` (mirror
  the Spec-2 `RlsIT` pattern). Unit: REO summary math.

## Migration `V9__loan_info_reo.sql`
- `ALTER TABLE loan ADD COLUMN …` (the §4 Loan columns + the SubjectProperty embedded columns
  `sales_price`, `appraised_value`, `property_type`, `occupancy_type`, `number_of_units`).
- `CREATE TABLE reo (… org_id …)` + indexes `(org_id, loan_id)` + FK `org_id → organization`; **RLS FORCE +
  `WITH CHECK` `tenant_isolation`** (NULLIF-empty GUC) + `app_user` SELECT/INSERT/UPDATE/DELETE. (`loan_id` is a
  plain uuid; `owner_borrower_id` nullable, no FK to keep REO independent of borrower lifecycle.)

## Module placement
- **`loan-core`:** extend `Loan` + `SubjectProperty` + enums; extend `UpdateLoanRequest`, `LoanService` apply,
  `LoanSummaryResponse`.
- **`reo` (new):** `RealEstateOwned` + `ReoPropertyStatus`; repo; `ReoService` + `ReoSummaryService`; controllers + DTOs.
- **`app`:** `include("reo")` + dep; `V9` migration.

## Out of scope / deferred
Down-payment-source checkboxes; multi-lien-per-REO; joint REO ownership; the LTV/DTI/housing/cash-to-close
**ratios + ledger** (Spec 6B); MISMO export.

**Implementation plan:** `docs/superpowers/plans/2026-06-05-los-spec6a-loan-info-reo.md` (next).
