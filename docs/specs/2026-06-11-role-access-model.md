# MSFG LOS — Role Access Model (back-office org-wide)

> Fixes the workflow blocker found in the CoC review: `LoanAccessGuard` admits only ADMIN or the
> owning LO, so a real underwriter (different subject than the LO) gets **403 before the underwriter
> role gate ever runs** — CoC decisions are impossible for the people meant to make them, and
> processors/closers have an empty pipeline. Security-critical → opus review pass before merge.

## Decision (Zack, 2026-06-11)
**Back-office roles get org-wide loan access.** `PROCESSOR`, `UNDERWRITER`, `CLOSER` may open and
work **any loan in their own org** (read + edit, same surface the owning LO has). `LO` stays scoped
to their own loans. `ADMIN` unchanged (org-wide). `PLATFORM_ADMIN` gets **no** loan-data access.
Assignment-based access (loan × role × user) was considered and deferred — it needs a migration,
endpoints, and FE work; it can layer on top of this later without undoing it.

## Locked decisions
| Area | Decision |
|---|---|
| Guard | `LoanAccessGuard.assertCanAccess`: pass for ADMIN (as today) **or any of PROCESSOR/UNDERWRITER/CLOSER**; else existing LO-owner check. One chokepoint — all **24 call sites across 10 modules** inherit the fix with no per-module changes. |
| Pipeline | `GET /api/loans`: ops roles see **all org loans** (the ADMIN query path). The `admin` boolean threading controller→service becomes `orgWideView = isAdmin \|\| hasAnyOf(PROCESSOR, UNDERWRITER, CLOSER)`. LO pipeline unchanged (own loans only). |
| PLATFORM_ADMIN | No loan-data access. **Verified:** `CurrentUser.isAdmin()` is `ROLE_ADMIN` only (`CurrentUser.java:26`), so platform admins are already excluded today — this change keeps it that way, pinned by a new test (`PLATFORM_ADMIN` → 403 on loan GET). |
| Unchanged | Tenant isolation (`@TenantId` + RLS + `findByIdAndOrgId` → cross-org stays **404**); within-org no-access stays **403**; `POST /api/loans` stays `LO`/`ADMIN`; `/api/admin/**` stays `PLATFORM_ADMIN`; per-action gates stay (CoC decision = UNDERWRITER/ADMIN; lifecycle `ENTRY_ROLE` map; role-aware transitions endpoint needs no change). |
| Conscious consequence | The **audited SSN reveal** becomes reachable org-wide for ops roles (they legitimately need full SSN; every reveal is still reason-required + logged to `PiiAccessLog`). Existing Spec-3 follow-up (rate-limit/alert on reveal) becomes more relevant — unchanged here. |
| Scope | No migration. No new endpoints. No schema change (`/v3/api-docs` byte-identical) — FE needs no `gen:api`; their role-gated UI already anticipates this (their caveat #1). Behavior change is additive-permissive: 403→200 for ops roles. |

## Implementation sketch
`LoanAccessGuard` (loan-core):
```java
private static final Set<String> ORG_WIDE_AUTHORITIES = Set.of(
    Role.PROCESSOR.authority(), Role.UNDERWRITER.authority(), Role.CLOSER.authority());

public void assertCanAccess(Loan loan) {
    if (currentUser.isAdmin()) return;
    if (currentUser.roles().stream().anyMatch(ORG_WIDE_AUTHORITIES::contains)) return;
    // existing LO-owner check unchanged (403 on mismatch)
}
```
`LoanController.pipeline`: pass `currentUser.isAdmin() || hasOrgWideRole()` where the `admin`
flag is passed today (rename the parameter to `orgWideView` for honesty; `LoanService.pipeline`
logic itself unchanged).

## Testing
- **Unit — `LoanAccessGuardTest`** (new, loan-core): full matrix — owner LO ✓, other LO ✗(403),
  PROCESSOR ✓, UNDERWRITER ✓, CLOSER ✓, ADMIN ✓, PLATFORM_ADMIN ✗, no/garbled subject ✗.
- **ITs (app):**
  - Underwriter with a **different subject than the LO** can `GET /api/loans/{id}` and
    **ACCEPT a CoC** end-to-end — replace the same-subject workaround in `CocDecisionIT`
    (it deliberately masked this gap) with the honest distinct-subject test.
  - PROCESSOR pipeline: sees another LO's loan in `GET /api/loans` (non-empty, org-wide).
  - LO pipeline still scoped: does NOT list another LO's loan; LO on another LO's loan → 403 (existing tests stay green).
  - PLATFORM_ADMIN → 403 on loan GET (pins the exclusion).
  - Cross-org ops role → still 404 (tenancy unaffected by role breadth).
- **Inventory flips:** audit existing tests asserting ops-role 403s (e.g. `LoanControllerIT:154`
  region) — update any that asserted the OLD restriction as if it were desired behavior.
- Full `./gradlew build` green; `OpenApiDocsIT` (no schema change expected).

## Review gates
Two-stage (spec compliance + code quality) + **opus security pass** (access-control change touching
every module's guard path) before merge. After merge: restart local bootRun (behavior change),
append FE handoff note (no regen needed — semantic unblock only).

## Out of scope / deferred
Assignment-based access (future module, layers on top) · branch-level scoping · per-action write
matrices (processor-vs-underwriter edit rights) · reveal-SSN rate-limiting (existing follow-up).
