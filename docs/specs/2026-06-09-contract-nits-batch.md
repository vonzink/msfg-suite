# MSFG LOS — Contract-Nits Batch (frontend §3)

> Four small, **additive** loan-contract refinements requested by the frontend session
> (`msfg-suite-web/docs/HANDOFF-BACKEND-REQUESTS.md` §3). No migration; no breaking changes; `/v3/api-docs`
> stays healthy (guarded by `OpenApiDocsIT`). One module-boundary wrinkle solved with a port (the project's
> ports-and-adapters convention).

## Context
The frontend's typed client consumes these endpoints; every change here is **additive only** (new response fields,
a relaxed-and-defaulted request field, a new read endpoint). Nothing persisted changes → **no Flyway migration**
(max remains `V10`).

## Locked decisions
| # | Item | Decision |
|---|---|---|
| 3.1 | `LoanSummaryResponse` missing fields | **Add** `lienPriority`, `amortizationType`, `addressLine1`, `addressLine2`, `postalCode`, `estimatedValue` (all already on `Loan`/`SubjectProperty`) |
| 3.2 | `LoanListItemResponse` thin | **Add** `primaryBorrowerName`, `propertyCity`, `propertyState`, `updatedAt`; borrower name via a **port resolver** (interface in `loan-core`, `@Component` adapter in `parties`, batch query) |
| 3.3 | `loanOfficerId` required | **Relax `@NotNull`**; default to the authenticated principal (`CurrentUser.id()`) in `LoanService.create`. **Users-list endpoint deferred** (no user directory yet) |
| 3.4 | No allowed-transitions endpoint | **New** `GET /api/loans/{loanId}/status/transitions` → `{currentStatus, allowedTransitions}`, **role-aware** (only what the caller may perform) |

## §3.1 — `LoanSummaryResponse` (loan-core)
Append to the record + `from(Loan)`: `LienPriorityType lienPriority` (`l.getLienPriority()`), `AmortizationType
amortizationType` (`l.getAmortizationType()`), `String addressLine1`/`addressLine2`/`postalCode`
(`sp.getAddressLine1()`/`...Line2()`/`...PostalCode()`), `BigDecimal estimatedValue` (`sp.getEstimatedValue()`).
Null-safe on `sp` (as the existing `from` already is). JSON is name-keyed → additive for the frontend.

## §3.2 — `LoanListItemResponse` enrichment (loan-core + parties)
- **`LoanListItemResponse`** (loan-core) → `(id, loanNumber, status, loanOfficerId, primaryBorrowerName,
  propertyCity, propertyState, Instant updatedAt)`; factory `from(Loan l, String primaryBorrowerName)`
  (`propertyCity/State` from `sp`, `updatedAt` from `l.getUpdatedAt()`).
- **Port (loan-core):** `interface PrimaryBorrowerNameResolver {
  Map<UUID,String> primaryBorrowerNamesByLoanIds(Collection<UUID> loanIds); }` in `loan-core/.../service/`.
- **Adapter (parties):** `@Component PrimaryBorrowerNameAdapter implements PrimaryBorrowerNameResolver` — uses a
  new `BorrowerRepository.findByLoanIdInAndPrimaryTrue(Collection<UUID>)` (one batched, `@TenantId`-filtered
  query), maps `loanId → (firstName + " " + lastName).trim()` (null-safe; blank → null). One primary per loan
  (enforced by `BorrowerService`); if duplicates, first wins.
- **`LoanService.pipeline(...)`** injects the resolver; returns `Page<LoanListItemResponse>`: fetch the loan page,
  collect ids, `resolver.primaryBorrowerNamesByLoanIds(ids)` once, then map each loan + its name. **No N+1.**
  `LoanController.pipeline` wraps the page in `PagedResponse.from(...)` (unchanged contract).
- Loans with no borrowers → `primaryBorrowerName = null` (frontend renders blank). The resolver is a required
  bean — the `app` context always has the `parties` adapter (loan-core's own tests are non-Spring units).

## §3.3 — `loanOfficerId` defaults to principal (loan-core)
- `CreateLoanRequest.loanOfficerId`: drop `@NotNull` → optional `UUID`.
- `LoanService.create` injects `CurrentUser`. Effective officer = `req.loanOfficerId()` if present, else
  `CurrentUser.id()` parsed to `UUID`. If neither yields a value → `ValidationException("loanOfficerId is
  required")` (400). Backwards-compatible: an explicit id is still honored.
- **Deferred:** users-list endpoint (the LOS has no user directory — Cognito is the identity store; a real
  directory is its own feature).

## §3.4 — allowed-transitions endpoint (loan-core)
- **`LoanLifecycle.allowedTransitions(LoanStatus from, Set<String> authorities)` → `List<LoanStatus>`**
  (new method, no change to `assertTransition`): structural set = `FORWARD.get(from)` ∪ (`!from.isTerminal()` ?
  `CANCELLABLE` : ∅); **role-filter** each target by `ENTRY_ROLE` (keep if no required role, or caller has it,
  or caller is `ADMIN`). Deterministic order: forward targets by enum ordinal, then `WITHDRAWN`, `CANCELLED`.
- **DTO** `TransitionsResponse(LoanStatus currentStatus, List<LoanStatus> allowedTransitions)`.
- **Endpoint** `GET /api/loans/{id}/status/transitions` on `LoanController` (inject `LoanLifecycle`): `get` +
  `accessGuard.assertCanAccess`, then `ApiResponse.ok(new TransitionsResponse(loan.getStatus(),
  lifecycle.allowedTransitions(loan.getStatus(), currentUser.roles())))`. Loan-scoped (cross-org 404, no-token 401).

## Testing
- **§3.1/3.2 — pipeline crown jewel:** create a loan (with §4 + subject-property city/state) + a primary borrower
  (e.g. "Abbas Hussein"); `GET /api/loans` → the row has `primaryBorrowerName == "Abbas Hussein"`,
  `propertyCity`/`propertyState` populated, `updatedAt` non-null; a loan with no borrower → `primaryBorrowerName`
  null. `GET /api/loans/{id}` → the 6 new summary fields echo back what was PATCHed.
- **§3.3:** `POST /api/loans` with NO `loanOfficerId` → 201, and the created loan's `loanOfficerId` equals the
  caller's principal id (the dev ADMIN id under the test JWT); with an explicit id → that id is used.
- **§3.4 — transitions:** `STARTED` loan as an LO → `[APPLICATION_IN_PROGRESS, WITHDRAWN, CANCELLED]`;
  `IN_UNDERWRITING` loan as an LO → role-gated targets (`APPROVED_WITH_CONDITIONS`/`DENIED`/`SUSPENDED`) are
  **absent**, but as `UNDERWRITER`/`ADMIN` they appear (`CANCELLABLE` always present for non-terminal); a terminal
  loan → `[]`. cross-org → 404; no token → 401. Unit-test `LoanLifecycle.allowedTransitions` directly.
- **OpenApiDocsIT** stays green (new `TransitionsResponse` has a unique simple name; no enum collisions).
- All existing loan tests stay green.

## Module placement
- **loan-core:** `LoanSummaryResponse` (+6), `LoanListItemResponse` (enriched), `PrimaryBorrowerNameResolver`
  (interface), `CreateLoanRequest` (relax), `LoanService` (resolver + CurrentUser + pipeline mapping + create
  default), `LoanLifecycle` (+`allowedTransitions`), `TransitionsResponse`, `LoanController` (+transitions endpoint).
- **parties:** `PrimaryBorrowerNameAdapter` (@Component), `BorrowerRepository.findByLoanIdInAndPrimaryTrue`.
- **No migration.**

## Out of scope / deferred
Users-list / user-directory endpoint; any change to existing field semantics or paths; SUSPENDED→resume edge
(unrelated lifecycle TODO).

**Implementation plan:** `docs/superpowers/plans/2026-06-09-contract-nits-batch.md` (next).
