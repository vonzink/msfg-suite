# Spec — Borrower-self application read/write (joint LO + client 1003 editing)

**Date:** 2026-06-26 · **Status:** DRAFT (pre-build) · **Owner call:** save to the suite (SoR); full self-submit.

## 1. Goal & context
The client (borrower) `/apply` form in **mortgage-app** must persist the full 1003 to the **suite**
(system of record), and the application is a **joint process**: both the borrower AND the loan officer
get into the *same* loan and edit concurrently during the initial application.

Today the borrower's loan is created in the suite at `/continue` via `POST /api/loans/intake`
(borrower-allowed; creates loan + primary borrower + links `borrower_party.user_id = Cognito sub`),
but the `/apply` submit posts to the **legacy mortgage-app backend** (`POST /loan-applications`,
staff-only) → `AccessDeniedException` → masked as a 500. And `/apply` only prefills from the funnel
token — it never loads suite state, so it can't support joint editing.

## 2. Requirements
- **R1** Borrower can READ their own full application from the suite (to hydrate `/apply`, incl. the LO's edits).
- **R2** Borrower can WRITE their own application data to the suite.
- **R3** The LO/staff edit the SAME loan via the existing staff console endpoints (unchanged).
- **R4** Authorization stays asymmetric: a borrower writes ONLY their own borrower row + own 1003 data
  + the borrower-writable subset of loan §4; never a co-borrower's data, never pricing/UW fields.
- **R5** Concurrency-safe: simultaneous borrower/LO edits must not silently clobber → optimistic lock (`@Version` → 409),
  and the borrower upsert touches ONLY the caller's own rows (so LO edits to other borrowers / loan-level
  pricing are never overwritten by a borrower save).
- **R6** Preserve all existing per-module business rules (SSN normalization, DTI include/exclude pairing,
  ordinal assignment, validation) — do NOT bypass them by writing repositories directly.
- **R7** The public staff-only per-module endpoints stay EXACTLY as security-reviewed (no widening).

## 3. Authorization design
Add one predicate to `LoanAccessGuard` (mirror of `assertBorrowerSelfReadable`, write-side):

```java
/** WRITE predicate: staff-or-owning-LO, OR a ROLE_BORROWER whose sub IS this borrowerId. */
public void assertBorrowerSelfWritable(Loan loan, UUID borrowerId) {
    if (isStaffOrOwningLo(loan)) return;
    UUID me = currentSubject();
    if (me != null
            && currentUser.roles().contains(Role.BORROWER.authority())
            && loanLinkageResolver.isBorrowerSelf(borrowerId, me)) return;
    throw new ForbiddenException("No write access to loan " + loan.getLoanNumber());
}
```

**Orchestrator + no-guard internals (the existing `addAtIntake` pattern).** A new
`BorrowerApplicationService` (new `application` module, or in `origination`) authorizes ONCE at the top
via `assertBorrowerSelfWritable(loan, callersBorrowerId)`, resolves the caller's OWN borrower row
(`loanLinkageResolver` / primary-by-user_id), then writes via **new no-guard internal methods** on the
existing services (e.g. `EmploymentService.replaceForBorrowerInternal(loanId, borrowerId, …)`),
exactly as `IntakeService` calls `borrowerService.addAtIntake` today. The public guarded methods are
untouched. Staff hitting the same orchestrator pass `isStaffOrOwningLo` and may target any borrower.

**Co-borrower:** a borrower may only address their own `borrowerId`; any other → 403 (the guard denies).

## 4. API surface
- `GET  /api/loans/{loanId}/application` → consolidated application (loan §4 + the caller's borrower(s) + collections).
  Borrower sees own data; staff see all. Hydrates the form.
- `PUT  /api/loans/{loanId}/application` → idempotent upsert of the caller's own application data.
  Returns the refreshed application (+ ETag/version for conflict detection).
- **SecurityConfig:** add regex matchers (UUID loanId) for GET+PUT `…/application` → `hasAnyRole(STAFF_AND_BORROWER)`.
  Method-level guard does the borrower-self narrowing (defense in depth, matching the T11 pattern).

## 5. Request/response DTO
Mirror the mortgage-app `LoanApplicationDTO` shape so the FE maps with minimal transform:
loan-level (`loanPurpose` read-only post-intake, `loanType`/`loanAmount`/`downPayment`/`propertyValue`),
`property`, `borrowers[]` (personal + `employmentHistory[]` + `incomeSources[]` + `assets[]` +
`reoProperties[]` + `declaration`), `liabilities[]`. (See §6 mapping.)

## 6. Mapping (FE LoanApplicationDTO → suite modules) + enums
| FE section | Suite target | Notes / enum mapping |
|---|---|---|
| loan-level + property | `LoanService` §4 **subset** (see §7) + `SubjectProperty` | propertyUse Primary/Secondary/Investment → `OccupancyType`; propertyType → `PropertyType` |
| borrower personal | `BorrowerParty` (add/update internal) | SSN via `SsnSupport.normalize` + EncryptedStringConverter; maritalStatus/citizenship → suite enums |
| employmentHistory[] | `Employment` (income module) | status Present/Prior → `EmploymentStatusType`; selfEmployed flag |
| incomeSources[] + employment income | `IncomeItem` | FE incomeType (SocialSecurity/Pension/…) → suite `IncomeType`; employment monthlyIncome → an employment-linked IncomeItem |
| assets[] | `Asset` (financials) | FE assetType (Checking/Savings/401k/…) → suite `AssetType`; usedForDownpayment |
| liabilities[] (loan-level, FE) | `Liability` (per-borrower in suite) | assign to the caller's borrower; type (Revolving/Installment/…) → `LiabilityType`; SecuredLoan→Installment shim |
| reoProperties[] | `RealEstateOwned` (loan-scoped) | set `ownerBorrowerId` = caller |
| declaration + HMDA | `BorrowerDeclarations` + `BorrowerDemographics` (upsert) | map booleans to suite §5 fields; HMDA CSV → `Set<Race>`/`Set<Ethnicity>`/`Sex` |

## 7. Borrower-writable §4 subset
ALLOW: address (line1/2/city/state/postal), `estimatedValue`, `salesPrice`, `downPaymentAmount`,
`baseLoanAmount`, `propertyType`, `occupancyType`, `numberOfUnits`, `mortgageType`.
DENY (LO/pricing/UW only): `interestRate`, `loanTermMonths`, `qualifyingCreditScore`, `proposed*Monthly`,
`financedFeesAmount`, `secondLoanAmount`, `appraisedValue`, `documentationType`, `consummationDate`,
`lienPriority`, `amortizationType`. (`loanPurpose` is fixed at intake.)

## 8. Concurrency
- Each entity extends `BaseEntity` (`@Version`) → optimistic lock; platform `GlobalExceptionHandler`
  already maps `OptimisticLockException` → 409. The PUT carries the loan version (ETag); stale → 409 → FE reloads.
- The borrower upsert is **scoped to the caller's own rows only** (own borrower row, own employment/income/assets/
  liabilities/reo/declarations, borrower-writable §4 subset). It REPLACES the caller's own collections
  (delete-absent + upsert-present by a stable key/ordinal), and never reads/writes other borrowers or
  staff-only loan fields — so an LO editing pricing or a co-borrower row is never clobbered.
- Section-level granularity: v1 ships the consolidated PUT; FE saves per-step (save-as-you-go) so each
  side sees the other's progress and conflict windows stay small. (Per-section PUT endpoints = fast-follow if needed.)

## 9. Deferrals / known gaps (call out, don't silently drop)
- **Residences / address history**: the suite has NO residence entity (form `residences[]` has no target).
  v1 DROPS residences with a logged warning; adding a suite `residences` module is a fast-follow spec.
- **Co-borrowers**: borrower self-submit handles the caller's own row only; co-borrower data entry deferred.
- A few FE-only/computed fields (risk score, masked account, annualized) are not persisted (already computed).
- No Flyway migration expected (reuses existing tables); confirm during build.

## 10. Frontend (mortgage-app) changes
- `ContinuePage.finishAndContinue`: capture the intake result's suite loan id → pass to `/apply` (e.g.
  `/apply?loanId=<uuid>` or sessionStorage) so the form knows it's editing an existing suite loan.
- `ApplicationForm`: for an existing suite loan, **hydrate** via `GET …/application` (merge over token prefill),
  and **submit/save** via `PUT …/application` on `suiteClient` (not the legacy `apiClient` create).
  Keep client draft autosave as an offline buffer, but the suite is the shared SoR.
- `GlobalExceptionHandler` (mortgage-app backend): add `@ExceptionHandler(AccessDeniedException)` → 403
  (stop masking 403s as 500s) — hygiene; the borrower no longer hits that backend for create.

## 11. Test plan (TDD)
- **Guard unit tests**: `assertBorrowerSelfWritable` — staff ✓, owning-LO ✓, borrower-self ✓,
  borrower-other-row ✗, agent ✗, platform-admin ✗, no-sub ✗.
- **Mapping unit tests**: each FE section → suite entity (enum mapping, SSN normalize, liability DTI pairing,
  SecuredLoan→Installment, HMDA CSV→Set).
- **Crown-jewel ITs**: borrower self PUT persists across modules (verify via independent reads);
  borrower cannot PUT another borrower's data (403); staff PUT any borrower; concurrent stale-version PUT → 409;
  GET hydrate returns the round-tripped data; cross-tenant denied (RLS).
- **FE**: ContinuePage passes loanId; ApplicationForm hydrates + saves to suiteClient (mocked); 403/409 handling.

## 12. Build order
1. `assertBorrowerSelfWritable` (guard) — TDD. 2. No-guard internal methods on the per-module services — TDD.
3. `BorrowerApplicationService` orchestrator (read + upsert) + DTO + mapping — TDD. 4. Controller + SecurityConfig
allowlist + ITs. 5. opus security review (borrower-self write boundary). 6. FE rewire (mortgage-app). 7. Deploy (owner-gated).
