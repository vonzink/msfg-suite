# MSFG LOS — Spec 6B: Qualification Calc Engine

> The 1003's **derived underwriting math** — LTV/CLTV/TLTV, monthly P&I, proposed housing (PITI), net rental,
> and the **DTI ratios** — computed **read-only** from the inputs Specs 4/5/6A already capture. This is the
> precision-critical spec: every formula, value-basis rule, convention, and rounding below is **normative**.
> (Details-of-Transaction / cash-to-close is intentionally **deferred to Spec 6C** — it needs ~15 input fields
> captured first.)

## Context
No new persisted data. A new **`qualification` module** with a `LoanCalculationService` that reads the current
loan + §4 fields + subject property (loan-core), the income total (S4 `IncomeSummaryService`), the DTI-included
liability payments (S5 `LiabilitySummaryService.dtiMonthlyPayments`), and the REO rows (S6A
`RealEstateOwnedRepository`), and returns all derived figures via `GET /api/loans/{loanId}/calculations`.
Computed-on-read = always fresh, no staleness, no migration.

## Locked decisions
| Area | Decision |
|---|---|
| Shape | **Read-only computed-on-read** — `LoanCalculationService` + `GET /api/loans/{loanId}/calculations`. No new table/migration |
| Module | New **`qualification`** module (deps `:platform`,`:loan-core`,`:parties`,`:income`,`:financials`,`:reo`) |
| Scope | Qualification ratios only: loan amount, LTV/CLTV/TLTV, P&I, proposed housing, net rental, DTI front/back, housing comparison. **DoT/cash-to-close → Spec 6C** |
| Net rental | **Fannie 75% convention:** per REO property `net = 0.75 × grossMonthlyRentalIncome − mortgageMonthlyPayment`; positive nets → income, `abs(negative)` nets → monthly debt |
| Precision | **currency** `BigDecimal` scale 2, `HALF_UP`; **ratios** as a percent `BigDecimal` scale 3 `HALF_UP` (e.g. `28.500`) |
| Edge cases | **Never divide by zero / NPE.** Any missing input → the dependent figure is `null` (see table). Partial results are fine |
| Tenancy | Read path is loan-scoped: `accessGuard.assertCanAccess(loanService.get(loanId))` first; all reads `@TenantId`-filtered |

## Normative formulas (implement EXACTLY)

Let all amounts be `BigDecimal`. `nz(x) = x == null ? ZERO : x`.

1. **Total loan amount:** `totalLoanAmount = nz(baseLoanAmount) + nz(financedFeesAmount)`.
   (If `baseLoanAmount` is null → `totalLoanAmount = null`, treat as "not computable" for LTV.)
2. **LTV value basis** (`ltvBasis`):
   - `loanPurpose == PURCHASE` → `min(salesPrice, appraisedValue)` (the **lesser of**). If either is null, use the
     non-null one; if both null → `null`.
   - else (refinance / not purchase) → `appraisedValue` (fallback `estimatedValue` if `appraisedValue` null).
3. **LTV / CLTV / TLTV** (percent, scale 3; `null` if `ltvBasis` null or zero, or numerator null):
   - `ltv  = baseLoanAmount / ltvBasis × 100`
   - `cltv = (nz(baseLoanAmount) + nz(secondLoanAmount)) / ltvBasis × 100`
   - `tltv = cltv` (no separate HELOC-line field yet — document this; revisit when HELOC line amount is captured).
4. **Monthly P&I** (`monthlyPrincipalInterest`, currency; `null` if `baseLoanAmount`/`interestRate`/`loanTermMonths`
   null or `loanTermMonths ≤ 0`):
   - `c = interestRate / 100 / 12` (monthly rate, as decimal).
   - if `c == 0` → `M = baseLoanAmount / loanTermMonths`.
   - else → `M = baseLoanAmount × c × (1+c)^n / ((1+c)^n − 1)`, `n = loanTermMonths`.
     Use `BigDecimal` with a working scale ≥ 10 for `(1+c)^n` (via `BigDecimal.pow(n)` since n is an int), then
     round `M` to scale 2 HALF_UP.
5. **Proposed monthly housing (PITI+)** (`proposedHousingExpense`, currency):
   `nz(monthlyPrincipalInterest) + nz(proposedTaxesMonthly) + nz(proposedHazardInsuranceMonthly) +
   nz(proposedHoaDuesMonthly) + nz(proposedMortgageInsuranceMonthly)`. (If `monthlyPrincipalInterest` is null,
   still sum the escrow inputs but flag `proposedHousingExpense` as partial — OR return null if P&I null; **decision:
   return null if P&I is null**, since DTI without P&I is misleading.)
6. **Net rental** (from `RealEstateOwnedRepository.findByLoanIdOrderByOrdinalAsc`): for each REO row,
   `net = 0.75 × nz(grossMonthlyRentalIncome) − nz(mortgageMonthlyPayment)`.
   `netRentalIncome = Σ max(net, 0)`; `netRentalDebt = Σ max(−net, 0)` (both currency, scale 2).
7. **Total monthly income:** `totalMonthlyIncome = incomeSummary.totalMonthlyIncome + netRentalIncome`.
   (`incomeSummary` from `IncomeSummaryService.summarize(loanId)`.)
8. **Total monthly debts:** `totalMonthlyDebts = liabilitySummary.dtiMonthlyPayments + netRentalDebt`.
   (from `LiabilitySummaryService.summarize(loanId)`.)
9. **DTI** (percent, scale 3; `null` if `totalMonthlyIncome` null or `== 0`):
   - `frontEndDti = proposedHousingExpense / totalMonthlyIncome × 100`
   - `backEndDti  = (nz(proposedHousingExpense) + totalMonthlyDebts) / totalMonthlyIncome × 100`
10. **Housing comparison:** `presentHousingExpense` = the primary borrower's present-address `rentAmount`
    (S3 `BorrowerAddress` where `addressType=PRESENT`, primary borrower) — **read-only best-effort, may be null**;
    `proposedHousingExpense` from (5). Return both + `delta = proposed − present` (null-safe).

### Edge-case table (return `null`, do not throw)
| Figure | null when |
|---|---|
| `totalLoanAmount` | `baseLoanAmount` null |
| `ltvBasis` | purchase + both sales & appraised null; non-purchase + appraised & estimated null |
| `ltv/cltv/tltv` | `ltvBasis` null or 0, or `baseLoanAmount` null |
| `monthlyPrincipalInterest` | `baseLoanAmount`/`interestRate`/`loanTermMonths` null or term ≤ 0 |
| `proposedHousingExpense` | `monthlyPrincipalInterest` null |
| `frontEndDti`/`backEndDti` | `totalMonthlyIncome` null or 0 |

## API
`GET /api/loans/{loanId}/calculations` → `ApiResponse<LoanCalculationResponse>` where the response carries **every
derived figure above** plus the **inputs used** (for transparency/debuggability): `baseLoanAmount`,
`secondLoanAmount`, `financedFeesAmount`, `ltvBasis`, `interestRate`, `loanTermMonths`, `totalMonthlyIncome`
(broken into base income + net rental), `totalMonthlyDebts` (broken into liabilities + net rental debt),
`proposedHousingExpense` components. Loan-authorized (cross-org → 404, no token → 401).

## Testing (the math is the product — be exhaustive)
- **Crown jewel — fully-worked example:** one loan with known inputs (e.g. base 300000, rate 6.5, term 360,
  sales 375000, appraised 380000, purchase; proposed taxes 400 / ins 120 / HOA 0 / MI 90; income total 9000;
  DTI liabilities 650; one REO gross rent 2000 / mortgage 1200). **Hand-compute** and assert EXACTLY:
  `ltvBasis = 375000` (lesser of), `ltv = 80.000`, P&I ≈ `1896.20` (assert to the cent), proposed housing ≈
  `2506.20`, net rental = `0.75×2000 − 1200 = 300` (income), income = `9300`, debts = `650`,
  frontEndDti ≈ `26.948`, backEndDti ≈ `33.938`. (Recompute precisely when writing the test; the point is
  every figure is asserted against an independent hand calculation, not a snapshot.)
- **Refi value basis:** non-purchase uses appraised (not lesser-of). **CLTV:** with a second loan, `cltv > ltv`.
- **Net rental negative:** gross 1000 / mortgage 1500 → `net = 750 − 1500 = −750` → `netRentalDebt = 750`,
  added to debts, NOT subtracted from income.
- **Zero-rate P&I:** rate 0 → `M = base / term`.
- **Edge cases:** no income → `frontEndDti`/`backEndDti` null (no 500); no rate → P&I null → housing null → DTI null;
  no value → LTV null. Each returns `200` with the dependent fields null.
- **Precision:** ratios scale 3, currency scale 2, HALF_UP (assert exact scale/value).
- Tenant/loan scope (cross-org 404, no token 401). Unit-test the formula service directly with hand-computed cases.

## Module placement
- **`qualification` (new):** `LoanCalculationService` (the formula engine — pure, unit-testable; inject
  `LoanService`, `IncomeSummaryService`, `LiabilitySummaryService`, `RealEstateOwnedRepository`, `BorrowerRepository`
  for present housing, `LoanAccessGuard`), `LoanCalculationController`, `LoanCalculationResponse` (+ nested
  breakdown records). A pure `MortgageMath` helper (amortization, safe-divide, rounding) is recommended for
  unit-testing the formulas in isolation.
- **`app`:** `include("qualification")` + dep. **No migration** (read-only).

## Out of scope / deferred
Details-of-Transaction ledger + cash-to-close (Spec 6C — needs prepaids/closing-costs/points/credits input
capture); separate HELOC-line TLTV; MI auto-calculation (uses the captured `proposedMortgageInsuranceMonthly`);
residual-income (VA) / other agency-specific ratios; storing a calc snapshot.

**Implementation plan:** to be written by the building session (`docs/superpowers/plans/…-spec6b-calc-engine.md`).
Mirror the module structure of `income`; the novelty is `MortgageMath` (amortization + ratios) — TDD it hard.
