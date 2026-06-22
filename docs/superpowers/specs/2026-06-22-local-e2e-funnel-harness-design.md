# Local End-to-End Funnel Harness — design

**Date:** 2026-06-22
**Status:** ✅ Approved (owner) — first pass of "putting together the full site"
**Parent:** `docs/superpowers/specs/2026-06-18-unified-integration-architecture-design.md` (Phase A)
**Related:** `docs/superpowers/specs/2026-06-19-mortgage-app-borrower-slice-design.md`

## Goal
Bring all of the funnel apps up **together, locally, and actually connected**, and walk a test borrower
end to end: **msfg.us (lead) → mortgage-app (apply) → msfg-suite (system of record) → staff console**.
Breadth-first — prove the whole flow with real data crossing the seam, exposing/closing the blockers,
rather than polishing any one part. The walkthrough runbook is the acceptance test.

## Why this, why now
The macro architecture is decided (parent doc) and Phase F (suite identity/access foundation) is merged.
Much of the funnel is already coded but **never run together**. Investigation (2026-06-22) found three
blockers that this pass resolves:

- **Gap 1 — keystone data flow missing.** mortgage-app's `createFromIntake` writes the loan to **its own
  DB**; there is **no HTTP client to suite anywhere** in its backend. A freshly-applied loan never reaches
  suite, so the re-pointed borrower screens (which read `/me/loans` *from suite*) show nothing for it.
- **Gap 2 — no local borrower identity.** suite's `local` profile is **ADMIN-only**; mortgage-app's `dev`
  profile demands a **real Cognito JWT**. Phase F scopes a borrower to their loans by token `sub` → linkage
  rows. A faithful borrower-token walk needs the owner-gated Cognito `org_id` Lambda — not achievable purely
  locally today without a stand-in.
- **Gap 3 — port clash.** msfg.us and mortgage-app FE both default to :3000.

## Local topology (verified 2026-06-22)
| App | Port | DB | Local auth today |
|---|---|---|---|
| msfg-suite (SoR) | :8080 | PG :5432 | `local` profile = hardcoded dev ADMIN (`DevPrincipalFilter`), does not validate real JWTs |
| mortgage-app backend | :8081 (ctx `/api`) | H2 in-mem (`dev`) | enforces real Cognito JWT, no local bypass |
| mortgage-app FE (CRA) | :3000 → **:3001** | — | sends Cognito `id_token`; borrower list/detail re-pointed to suite |
| msfg.us (Next) | :3000 | PG :5434 | handoff POSTs `${LOS_API_BASE}/api/loan-applications/intake`, then redirects to app |
| msfg-rag | :8090 | PG :5433 | public `/api/ai/mortgage/ask`; needs Anthropic+OpenAI keys + corpus or it refuses |

DB ports are conflict-free (5432 / 5433 / 5434).

## Locked decisions
- **D-LE1 — Local identity = dev-header bridge** (over a mock-OIDC issuer or waiting for real Cognito).
  Fenced to the `local` profile, zero AWS, exercises Phase F scoping. Mock-OIDC is the later fidelity
  upgrade; real Cognito is the eventual prod path (owner-gated).
- **D-LE2 — Strangler dual-write at intake.** suite becomes authoritative for the created loan; mortgage-app
  also keeps its existing local row (storing the returned suite `loanId` on it) so legacy mortgage-app
  screens don't break during transition.
- **D-LE3 — Ports:** msfg.us :3000, mortgage-app FE :3001, backend :8081, suite :8080, rag :8090. Add
  `http://localhost:3001` to suite's `local` CORS allowlist.
- **D-LE4 — msfg-rag is optional** in this pass (off the critical funnel path; degrades gracefully when the
  brain is disabled).

## Components

### 1. Dev-identity bridge (both Spring backends, `local` profile ONLY)
- Extend suite's `local`-profile `DevPrincipalFilter` to read optional request headers:
  - `X-Dev-Sub` — UUID subject (the acting user). Default: current dev ADMIN id.
  - `X-Dev-Roles` — CSV of Cognito group names (e.g. `Borrower`, `RealEstateAgent`, `LO`). Default: `Admin`.
  - `X-Dev-Org` — UUID org id. Default: the existing dev org (`…00aa`).
- **Absent headers → today's ADMIN behavior unchanged** (backward compatible; existing local flows untouched).
- It builds the same synthetic `Jwt` the filter already uses, populated from the headers, then maps groups
  through the real `CognitoRolesConverter` alias map (`Borrower`→`ROLE_BORROWER`, etc.) so the principal is
  identical in shape to a Cognito-issued one and genuinely exercises Phase F borrower/agent scoping.
- **mortgage-app backend** gets a mirror `local` profile security config with the same dev-header behavior,
  so its own auth doesn't block the intake POST during the local walk.
- **Hard fence + security note:** these beans are wired only under the `local` profile, never referenced by
  the prod `SecurityConfig`. A loud comment + doc line: trust-the-header is a local-only test seam and must
  never be enabled in any deployed environment.
- **Tests (suite IT):** (a) no headers → ADMIN unchanged; (b) `X-Dev-Roles=Borrower` + `X-Dev-Sub=X` →
  borrower principal that can read only X's linked loans via `/me/loans`; (c) borrower cannot read a loan
  they are not linked to (404/403).

### 2. Keystone wiring — mortgage-app intake → suite (closes Gap 1)
- **suite — `POST /api/loans/intake`** (conductor-owned, migrations serialized):
  - Auth: any authenticated principal. When the caller's role is `Borrower`, the caller's `sub` is the
    borrower to link.
  - Request (minimal 1003): `{ sourceLeadId (required), loanPurpose (required), mortgageType?, property?,
    borrower? }`.
  - Behavior: **idempotent by `sourceLeadId`** (within org) — if a loan already carries this `sourceLeadId`,
    return it (200) instead of creating a duplicate. Otherwise create the Loan, stamp `source_lead_id`,
    resolve the loan officer (from `intake.loanOfficer` if present, else suite's default/unassigned rule),
    and create the **borrower linkage** for the caller's `sub` using the Phase F linkage mechanism. Returns
    `{ loanId, loanNumber }`.
  - Migration: add `source_lead_id` to the loan row (nullable, unique per org). Migration version is whatever
    is next in the serialized Flyway sequence at build time (Phase F was V24).
- **mortgage-app — `SuiteClient`** (WebClient): base URL `SUITE_API_BASE` (default
  `http://localhost:8080/api`). `createFromIntake` calls `SuiteClient.createLoanIntake(...)`, stores the
  returned suite `loanId` on the local row (D-LE2 strangler), and returns it so the msfg.us deep-link and the
  FE `/me/loans` read resolve to the same loan.
  - Locally the call carries the dev headers (borrower `sub`); in prod it will carry the borrower Bearer.
- **Tests (mortgage-app unit):** `SuiteClient` request/response mapping; `createFromIntake` calls suite with
  a mocked suite and persists the returned `loanId`.

### 3. Run orchestration & config
- Env wiring:
  - **msfg.us `.env.local`:** `DATABASE_URL=postgresql://dev:dev@localhost:5434/msfg_web?schema=public`,
    `LOS_API_BASE=http://localhost:8081`, `NEXT_PUBLIC_APP_URL=http://localhost:3001`. Brain/AI optional.
  - **mortgage-app FE `.env`:** `REACT_APP_API_URL=http://localhost:8080/api`, `PORT=3001`.
  - **mortgage-app backend:** `SUITE_API_BASE=http://localhost:8080/api`, run under the `local` profile.
  - **suite:** `local` profile; CORS allowlist adds `http://localhost:3001`.
- **`docs/local-e2e.md` runbook:** boot order (suite + PG → mortgage-app backend → mortgage-app FE →
  msfg.us + PG; rag optional), exact commands, env, and the dev-header recipe (how to act as a borrower).

### 4. Walkthrough (the verification)
A step-by-step runbook (curl + browser) proving each hop, with expected results:
1. Submit a lead on msfg.us → `Lead` row persists (PG :5434).
2. Finish step / handoff → mortgage-app `POST /api/loan-applications/intake`.
3. Intake → `SuiteClient` → suite `POST /api/loans/intake` → loan created in suite (PG :5432) + borrower link.
4. Borrower lands on mortgage-app FE (:3001) → `GET /api/me/loans` from suite → the new loan appears.
5. Open the suite-web staff console → the loan is visible to staff.
6. Re-running the same lead handoff returns the same loan (idempotency proof).

Documents what stays gated: real Cognito token E2E (depends on the owner's `org_id` pre-token Lambda) and
deploy/DNS (Phase C).

## Out of scope (later slices)
BFF shrink; other FE area re-points; agent/LO portal actions; document upload; any writes beyond
loan-create; full 1003 detail sync; real Cognito; deploy/DNS; msfg-rag corpus population.

## Open questions / follow-ups
- Whether suite's existing `POST /api/loans` can be extended vs. a dedicated `/loans/intake` — settle during
  planning by reading the current loan-create + Phase F linkage code (favor the smallest change).
- Exact next Flyway version for `source_lead_id` — read at build time (serialized).
- mortgage-app local profile: trust dev headers vs. fully permit-all — favor mirroring suite's header bridge
  for symmetry.
- Dual-write divergence risk (D-LE2) is accepted for this transitional pass; revisit when the BFF shrinks.
