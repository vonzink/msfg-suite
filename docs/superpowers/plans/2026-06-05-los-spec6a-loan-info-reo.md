# LOS Spec 6A — Loan Information + REO Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Capture URLA §4 (Loan Information — extend the `Loan` aggregate) + §3 (REO — new `reo` module). Pure
data; ratios are Spec 6B.

**Architecture:** §4 extends `loan-core`'s `Loan` + embedded `SubjectProperty` + the existing loan endpoints
(no new module). REO is a new `reo` module that is a near-clone of `financials` (entity + CRUD + summary + RLS).
Migration `V9`.

**Spec:** `docs/specs/2026-06-05-los-spec6a-loan-info-reo.md`

## Templates to mirror (read before writing)
- REO module ≈ the **`financials`** module exactly: `Asset`→`RealEstateOwned`, `AssetService`→`ReoService`
  (loan-scoped — guard is `accessGuard.assertCanAccess(loanService.get(loanId))`, single load
  `findByIdAndOrgId(id, org())`; NOTE REO is **loan-scoped, not borrower-scoped**, so there is no
  `assertBorrowerInLoan` — model the guard on `IncomeSummaryService`/`AssetVerificationService` which are loan-scoped).
  `AssetSummaryService`→`ReoSummaryService`. Controllers/DTOs/IT mirror the financials equivalents.
- §4 Loan extension ≈ **Spec 3's borrower-PII extension** (`parties`): added fields to an entity + extended the
  Add/Update request + the `*Response.from` + the service apply helper. Here: `loan-core`'s `UpdateLoanRequest`,
  `LoanService`, `LoanSummaryResponse`.
- V9 RLS idiom ≈ `V8__assets_liabilities.sql` (FORCE RLS + tenant_isolation NULLIF-empty GUC + grants).
- RLS IT ≈ `app/src/test/java/com/msfg/los/financials/AssetsLiabilitiesRlsIT.java`.

## File Structure
```
settings.gradle.kts                          (modify: add "reo")
reo/build.gradle.kts                         (create — copy financials/build.gradle.kts)
app/build.gradle.kts                         (modify: implementation(project(":reo")))
loan-core/.../domain/{DocumentationType,PropertyType,OccupancyType}.java   (create)
loan-core/.../domain/Loan.java SubjectProperty.java                        (modify: add §4 fields)
loan-core/.../web/dto/{UpdateLoanRequest,LoanSummaryResponse}.java         (modify: add §4 fields)
loan-core/.../service/LoanService.java                                     (modify: apply §4 fields + validation)
reo/.../domain/{RealEstateOwned,ReoPropertyStatus}.java
reo/.../repo/RealEstateOwnedRepository.java
reo/.../service/{ReoService,ReoSummaryService}.java
reo/.../web/{ReoController,ReoSummaryController}.java
reo/.../web/dto/{AddReoRequest,UpdateReoRequest,ReoResponse,ReoSummaryRow,ReoSummaryResponse}.java
app/src/main/resources/db/migration/V9__loan_info_reo.sql
app/src/test/java/com/msfg/los/loan/web/LoanInformationIT.java
app/src/test/java/com/msfg/los/reo/web/{ReoControllerIT,ReoSummaryIT}.java
app/src/test/java/com/msfg/los/reo/ReoRlsIT.java
```

---

## Task 0: Scaffold `reo` module + the 4 enums
- [ ] settings.gradle.kts → add `"reo"`. `reo/build.gradle.kts` = copy `financials/build.gradle.kts`. `app/build.gradle.kts` → `implementation(project(":reo"))`.
- [ ] `loan-core/.../domain/`:
```java
// DocumentationType.java
package com.msfg.los.loan.domain;
public enum DocumentationType { FULL, ALTERNATIVE, STREAMLINE_REFINANCE, NO_DOCUMENTATION }
```
```java
// PropertyType.java
package com.msfg.los.loan.domain;
public enum PropertyType { SINGLE_FAMILY, CONDOMINIUM, TOWNHOUSE, PUD, TWO_TO_FOUR_UNIT, MANUFACTURED, COOPERATIVE }
```
```java
// OccupancyType.java
package com.msfg.los.loan.domain;
public enum OccupancyType { PRIMARY_RESIDENCE, SECOND_HOME, INVESTMENT }
```
```java
// reo/.../domain/ReoPropertyStatus.java
package com.msfg.los.reo.domain;
public enum ReoPropertyStatus { RETAINED, SOLD, PENDING_SALE, RENTAL }
```
- [ ] `./gradlew :reo:classes :loan-core:compileJava :app:compileJava` → SUCCESS. Commit `chore(reo): scaffold reo module + loan-info/reo enums`.

## Task 1: `V9` migration
- [ ] `app/src/main/resources/db/migration/V9__loan_info_reo.sql`:
```sql
alter table loan
    add column documentation_type varchar(40),
    add column interest_rate numeric(7,4),
    add column loan_term_months int,
    add column base_loan_amount numeric(15,2),
    add column financed_fees_amount numeric(15,2),
    add column second_loan_amount numeric(15,2),
    add column down_payment_amount numeric(15,2),
    add column qualifying_credit_score int,
    add column proposed_taxes_monthly numeric(15,2),
    add column proposed_hazard_insurance_monthly numeric(15,2),
    add column proposed_hoa_dues_monthly numeric(15,2),
    add column proposed_mortgage_insurance_monthly numeric(15,2),
    add column sales_price numeric(15,2),
    add column appraised_value numeric(15,2),
    add column property_type varchar(40),
    add column occupancy_type varchar(40),
    add column number_of_units int;

create table reo (
    id uuid primary key,
    version bigint not null default 0,
    org_id uuid not null references organization(id),
    loan_id uuid not null,
    owner_borrower_id uuid,
    ordinal int not null default 0,
    is_subject_property boolean not null default false,
    address_line1 varchar(255), address_line2 varchar(255),
    city varchar(120), state varchar(2), postal_code varchar(10),
    property_type varchar(40),
    intended_occupancy varchar(40),
    property_status varchar(20),
    market_value numeric(15,2),
    gross_monthly_rental_income numeric(15,2),
    monthly_taxes numeric(15,2), monthly_insurance numeric(15,2),
    monthly_hoa_dues numeric(15,2), monthly_maintenance numeric(15,2),
    mortgage_unpaid_balance numeric(15,2), mortgage_monthly_payment numeric(15,2),
    created_at timestamp(6) with time zone, created_by varchar(120),
    updated_at timestamp(6) with time zone, updated_by varchar(120)
);
create index idx_reo_org_loan on reo(org_id, loan_id);

alter table reo enable row level security;
alter table reo force row level security;
create policy tenant_isolation on reo
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);
grant select, insert, update, delete on reo to app_user;
```
- [ ] `./gradlew :app:test --tests "*LosApplicationTests"` → PASS. Commit `feat(app): V9 migration — loan §4 columns + reo table + RLS`.

## Task 2: Loan Information (§4) extension
- [ ] **`Loan.java`** — add the 12 §4 fields (after `noteAmount`): `@Enumerated(STRING) DocumentationType documentationType`, `BigDecimal interestRate`, `Integer loanTermMonths`, `BigDecimal baseLoanAmount/financedFeesAmount/secondLoanAmount/downPaymentAmount`, `Integer qualifyingCreditScore`, `BigDecimal proposedTaxesMonthly/proposedHazardInsuranceMonthly/proposedHoaDuesMonthly/proposedMortgageInsuranceMonthly` (lombok getters/setters already via `@Getter/@Setter`).
- [ ] **`SubjectProperty.java`** — add `BigDecimal salesPrice/appraisedValue`, `@Enumerated(STRING) PropertyType propertyType`, `@Enumerated(STRING) OccupancyType occupancyType`, `Integer numberOfUnits`.
- [ ] **`UpdateLoanRequest`** — add all 17 fields (nullable). **`LoanSummaryResponse`** — add them to the record + `from(Loan)` mapping (read the existing `LoanSummaryResponse` and extend its `from`).
- [ ] **`LoanService`** (the method handling `PATCH`/update) — apply each new field null-skip, then validate (each its own `if`/throw, `ValidationException` → 400): `interestRate` 0–25; `loanTermMonths` 1–480; `qualifyingCreditScore` 300–850; `numberOfUnits` 1–4; every amount ≥ 0. (Find the existing update/patch method in `LoanService`; mirror its style.)
- [ ] **IT** `app/src/test/java/com/msfg/los/loan/web/LoanInformationIT.java` (extends `AbstractIntegrationTest`): create a loan, `PATCH /api/loans/{id}` with §4 + subject-property fields → 200 + `GET` echoes them; out-of-range `interestRate`/`loanTermMonths`/`qualifyingCreditScore`/`numberOfUnits` → 400 (`$.message` names field); negative `baseLoanAmount` → 400. Use the loan JWT helper from existing loan ITs.
- [ ] `./gradlew :app:test --tests "*LoanInformationIT" --tests "*LoanController*"` → PASS (existing loan tests stay green). Commit `feat(loan-core): Loan Information §4 fields on Loan + SubjectProperty + validation`.

## Task 3: REO entity + CRUD
- [ ] **`RealEstateOwned.java`** (extends `TenantScopedEntity`, mirror `Asset`): all fields from the spec; `@Enumerated(STRING)` on `propertyType`/`intendedOccupancy`/`propertyStatus`; `state` is `UsStateCode`. **`RealEstateOwnedRepository`** — `findByLoanIdOrderByOrdinalAsc`, `findByIdAndOrgId`, `countByLoanId`.
- [ ] **`ReoService`** — loan-scoped (mirror `AssetService` but NO borrower check; guard = `accessGuard.assertCanAccess(loanService.get(loanId))`, `org()` from `TenantContext`, single load `reo.findByIdAndOrgId(reoId, org()).filter(x -> x.getLoanId().equals(loanId)).orElseThrow(new NotFoundException("REO", reoId))`, `ordinal = countByLoanId`). Validation: `marketValue`/rental/expenses/mortgage amounts ≥ 0 (each own `if`).
- [ ] **`ReoController`** `@RequestMapping("/api/loans/{loanId}/reo")` (mirror a financials controller, loan-scoped — path params are just `loanId` + `reoId`, no borrowerId). DTOs `AddReoRequest`/`UpdateReoRequest`/`ReoResponse` (records + `from`).
- [ ] **IT** `ReoControllerIT`: add REO → 201 + list 1; negative `marketValue` → 400; 2nd REO ordinal=1; PATCH; DELETE → 204; cross-org → 404; no token → 401.
- [ ] `./gradlew :app:test --tests "*ReoControllerIT"` → PASS, `./gradlew :reo:test` → PASS. Commit `feat(reo): RealEstateOwned CRUD (loan-scoped)`.

## Task 4: REO summary (crown jewel)
- [ ] DTOs `ReoSummaryRow(reoId, isSubjectProperty, propertyType, propertyStatus, marketValue, grossMonthlyRentalIncome)` + `ReoSummaryResponse(rows, totalMarketValue, totalGrossMonthlyRentalIncome, totalMonthlyExpenses, totalMortgageUnpaidBalance, totalMonthlyMortgagePayment)`.
- [ ] **`ReoSummaryService`** (mirror `LiabilitySummaryService` — loan-scoped, guard first, `findByLoanIdOrderByOrdinalAsc`, null-safe BigDecimal sums). `totalMonthlyExpenses` = Σ (`monthlyTaxes`+`monthlyInsurance`+`monthlyHoaDues`+`monthlyMaintenance`) per row (null-safe per component). Controller `GET /api/loans/{loanId}/reo/summary`.
- [ ] **IT** `ReoSummaryIT` (crown jewel): 2 REO rows → totals `compareTo` independent JDBC sums (`select coalesce(sum(market_value),0) ...`, and for expenses `select coalesce(sum(coalesce(monthly_taxes,0)+coalesce(monthly_insurance,0)+coalesce(monthly_hoa_dues,0)+coalesce(monthly_maintenance,0)),0) from reo where loan_id=?::uuid`); cross-org 404; no token 401.
- [ ] `./gradlew :app:test --tests "*ReoSummaryIT"` → PASS. Commit `feat(reo): REO summary (value/rental/expense/mortgage totals)`.

## Task 5: RLS IT + full build + finish
- [ ] **`ReoRlsIT`** — copy `AssetsLiabilitiesRlsIT` mechanism; fresh orgs `…00f1`/`…00f2`; seed a loan per org; insert one `reo` row (ORG_X) under `app_user`+matching GUC (read V9 columns); assert ORG_Y count 0 / ORG_X ≥1 / fail-closed on `reo`.
- [ ] `./gradlew :app:test --tests "*ReoRlsIT"` → PASS, then FULL `./gradlew build` → SUCCESSFUL (report total test count).
- [ ] Commit `test(reo): RLS coverage for reo table`.
- [ ] Update `ROADMAP.md`/`CLAUDE.md`, then **superpowers:finishing-a-development-branch**.

## Self-Review
Spec coverage: §4 Loan/SubjectProperty extension + validation (T2); REO CRUD (T3) + summary (T4); V9 + RLS (T1/T5). Ratios deferred to 6B (spec). Loan-info via existing endpoints; REO loan-scoped. ✓
Lessons: each validation rule its own `if`; `$.message` field asserts; summary crown jewels vs JDBC; loan-scoped guard (no borrower check) for REO. ✓
