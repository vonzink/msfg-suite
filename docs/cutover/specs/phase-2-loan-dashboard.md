# Cutover Phase 2 — Loan/Application + Dashboard (spec)

**Goal:** parity with mortgage-app's loan-application CRUD + dashboard + conditions + notes, adapted
**multi-tenant + staff-only**. Branch `feat/cutover-phase2-loan-dashboard`. Migrations V21→V23.
Source map = the mortgage-app loan/dashboard/conditions/notes mapping (this session).

## Determined implementation calls (deviations from source, justified)
- **Delete = SOFT** (mortgage-app hard-deletes). A regulated mortgage LOS must not hard-delete loans →
  add `deleted_at` to `loan`, filter from pipeline/get/search. Gate: LO-owner / MANAGER / ADMIN (Processor excluded, matching source).
- **Conditions = loan-access-gated** (any staff passing `LoanAccessGuard.assertCanAccess`), matching
  mortgage-app (no UW-only gate). Note: UW-gating is a possible future tightening.
- **Closing-date filter** reuses the existing `loan.consummationDate` (no new field) for `closingFrom/To`.
- **`status_changed_at`** mirror column added to `loan` (= latest transition time) so `stageAgeGt` sorts
  without joining history; **status backdating** = optional `transitionedAt` on the status transition.
- **Update**: extend the existing `PATCH /api/loans/{id}` (no separate PUT).

## Build tasks (each TDD'd, own commit on `feat/cutover-phase2-loan-dashboard`)

### T1 — conditions module (V21)
New `conditions` module (mirror `contacts`/`reo`; deps platform + loan-core). Migration **V21** table
`loan_condition` (tenant-scoped + FORCE RLS + grants, V13 pattern): id, org_id, loan_id, condition_text
varchar(2000) not null, condition_type varchar(50) (PriorToDocs|PriorToFunding|AtClosing|PostClose|Other),
status varchar(20) not null default 'Outstanding' (Outstanding|Cleared|Waived), assigned_to varchar(120),
due_date date, cleared_at timestamptz, cleared_by varchar(120), notes varchar(2000), created_by, timestamps,
deleted_at. Index (org_id, loan_id, status). Entity `LoanCondition`, repo, `ConditionService` + `ConditionController`
under `/api/loans/{loanId}/conditions`: POST (create; conditionText blank→400; status default Outstanding;
stamp createdBy from principal), GET (list ordered), PATCH /{id} (patch-semantics; cross-loan guard;
**clear logic**: Outstanding→Cleared|Waived stamps cleared_at+cleared_by, reopen→Outstanding wipes them),
DELETE /{id} (soft). Loan-access-gated. Expose `outstandingCount(loanId)` for the pipeline `conditionsGt`
filter + the dashboard. Unique DTO names (ConditionResponse, UpsertConditionRequest). ITs incl. RLS + clear/reopen + cross-loan 400.

### T2 — notes module (V22)
New `notes` module. Migration **V22** table `loan_note` (tenant-scoped + RLS + grants): id, org_id, loan_id,
author_id varchar(120), author_name varchar(200), content varchar(2000) not null, timestamps. Index (org_id, loan_id, created_at).
Entity `LoanNote`, repo, `NoteService` + `NoteController` `/api/loans/{loanId}/notes`: GET (newest-first),
POST (content blank→400; stamp author_id=sub + author_name from the user_account/CurrentUser), DELETE /{id}
(hard; cross-loan guard). Loan-access-gated. Unique DTO names. ITs incl. RLS + newest-first + cross-loan.

### T3 — loan-core enhancements: status backdating + soft-delete + lookup + search (V23)
- Migration **V23**: `loan` ADD `status_changed_at timestamptz` (backfill = max(loan_status_history.transitioned_at)
  COALESCE created_at) + `deleted_at timestamptz`. Index (org_id, status, status_changed_at).
- **Status backdating**: `TransitionRequest` gets optional `transitionedAt`; the transition writes the
  `LoanStatusHistory` row with it (null→now), mirrors `loan.status_changed_at = transitionedAt ?? now`.
- **Soft-delete**: `DELETE /api/loans/{id}` → set deleted_at; gate LO-owner/MANAGER/ADMIN; filter deleted from
  get/pipeline/search.
- **Lookup by number**: `GET /api/loans/number/{loanNumber}` (org-scoped, staff w/ access).
- **Typeahead search**: `GET /api/loans/search?q=&limit=` (min len 2, limit≤50): rank exact→prefix on
  loanNumber, then borrower-name substring (via PrimaryBorrowerNameResolver / a search query). Return
  slim hits {id, loanNumber, borrowerName, propertyCity, propertyState, status}. Org-wide-view scoped.

### T4 — pipeline filter parity + default ordering
Extend `GET /api/loans` (pipeline) with the full filter set: `status` (List), `lo` (assigned LO UUID),
`conditionsGt` (Integer — count outstanding via conditions module's service/count query),
`closingFrom`/`closingTo` (vs consummationDate), `stageAgeGt` (days vs status_changed_at), `loanType` (List),
`amountMin`/`amountMax` (vs baseLoanAmount/noteAmount), `sort` (whitelist: createdAt|statusChangedAt|amount,
dir asc|desc; injection-safe fallback), `page`/`size`. **Default ordering newest-first** (updatedAt or
createdAt DESC + id tiebreaker). Build with JPA `Specification` (query-side, like documents search). Keep the
existing simple `status` param working. `conditionsGt` reads the conditions module via its SERVICE (ArchUnit).
ITs assert each facet filters at the query layer + default ordering.

### T5 — clone ("Copy to new")
`POST /api/loans/{id}/clone` (gate LO/PROCESSOR/MANAGER/ADMIN + access): deep-copy the loan's data tree to a
NEW loan via each owning module's SERVICE (loan §4 + SubjectProperty; borrowers + their employment/income/
assets/liabilities/REO/declarations). **RESET**: new loanNumber, status→STARTED (+ fresh status_changed_at),
**DROP borrower SSNs** (re-collect — stale NPI), DROP documents. Assign caller as LO if source had none.
Return {id, loanNumber}. Cross-module copy via services only (ArchUnit). HEAVIEST task — if the full
financial-tree copy is disproportionate, scope to loan+property+borrowers(+declarations) and note the rest as
follow-up. ITs: clone produces a new loan with copied property+borrowers, reset number/status, no SSN, no docs.

### T6 — dashboard aggregated payload + edit terms
New `dashboard` aggregator module (deps: loan-core, parties, fees, contacts, conditions, notes, qualification —
mirror how `qualification`/`aus` aggregate; if deps get unwieldy, the controller may live in `app`).
- `GET /api/loans/{loanId}/dashboard` → assembled `DashboardResponse`: loanId/applicationNumber(loanNumber)/
  status/created/updated; identifiers; primaryBorrower (parties + PrimaryBorrowerNameResolver, SSN masked);
  property (SubjectProperty); loanTerms (loan §4); housingExpenses (proposed PITI inputs); purchaseCredits
  (fees section-L credits); conditions (conditions module); statusHistory (existing, newest-first, + note/by);
  loanAgents (contacts module); closingInformation (contacts/fees/disclosures consummationDate); notes (notes
  module, newest-first). Read-only assembly; all via SERVICES (ArchUnit). Loan-access-gated.
- `PATCH /api/loans/{loanId}/dashboard/terms` → patch the loan §4 terms in place (baseLoanAmount, noteAmount,
  interestRate, amortizationType, loanTermMonths, lienPriority, downPaymentAmount); validate interestRate
  sane (≥0, <100) → 400. Reuse the loan update path.

### T7 — verify + merge
Full `./gradlew clean build` green (RLS/OpenAPI/ArchUnit), merge → main, push, update PARITY-CHECKLIST (mark
Documents + Auth + this phase ✅), checkpoint with Zack.

## Staff-only drops: borrower/agent dashboard sections stay only insofar as `contacts` already models agents;
no borrower-self-service, no intake lead funnel, no /me/loans changes (done in Phase 2/3).
