# Cutover Program — Phase 0: Foundation & Seam (Design)

**Date:** 2026-06-15
**Branch:** `cutover/phase-0-foundation`
**Status:** Approved design → ready for implementation plan
**Repos in scope:** `msfg-suite` (backend, this repo) + `msfg-suite-web` (frontend, `/Users/zacharyzink/MSFG/msfg-suite-web`)

---

## 1. Program context — the cutover

We are making **MSFG-suite the single backend** for MSFG's mortgage origination, replacing the
existing `mortgage-app` monorepo backend (`/Users/zacharyzink/MSFG/WebProjects/mortgage-app`).

**Why:** `mortgage-app/backend` (Spring Boot 3.2 / Java 17 / Maven, single-tenant, ~16K LOC) is a
1003-intake + document-collection + pipeline + conditions tracker with **zero native underwriting** —
it outsources credit, AUS, pricing, disclosures, and DTI/LTV to **LendingPad** via MISMO round-trips.
MSFG-suite (Spring Boot 3.3 / Java 21 / Gradle multi-module, **multi-tenant**, 484 tests) already has
exactly that missing engine: full 1003, qualification calc, pricing/lock, AUS+credit, disclosures
(TRID), fees, CoC — all behind swappable vendor ports. MSFG-suite is also the strategic SaaS platform.

### Locked program decisions (from brainstorm, 2026-06-15)

- **Integration shape:** **Full cutover.** MSFG-suite becomes the one backend; `mortgage-app/backend`
  and `mortgage-app/frontend` are retired at the end.
- **Data:** **Greenfield.** No production data migration. New loans originate in MSFG-suite from day one.
  `mortgage-app` stays live in prod serving real loans until the final flip (build-alongside, flip-at-end).
- **Frontend:** **Complete `msfg-suite-web`** (React 19 / Vite / TS, already ~55–65% built and natively
  typed against MSFG-suite's OpenAPI) to full `mortgage-app` feature parity. Not a re-point of the old
  CRA app, not a from-scratch build.
- **Repo ownership:** **This program drives BOTH repos directly.** The prior "backend session never
  edits the frontend" rule is retired for cutover work.
- **Deployed auth:** **New dedicated LOS Cognito pool** (not a reuse of `mortgage-app`'s
  `us-west-1_S6iE2uego`). Provisioned later, in the deploy phase.
- **LendingPad:** **Retire it.** Underwriting moves fully in-house on MSFG-suite. **MSFG-suite must be
  able to generate (export) a MISMO file** — so MISMO **export is required** (Phase 5); MISMO **import**
  becomes optional/portability only.

### Cutover roadmap (each phase = its own spec → plan → build cycle)

| Phase | Scope |
|-------|-------|
| **0 — Foundation & seam** *(this spec)* | Prove the local end-to-end seam; harden tenancy; design the deploy-Cognito seam; write the parity checklist. |
| 1 — Document system parity | S3 storage adapter + presigned direct-upload; folders + templates + table-backed doc types + classification; review workflow + bulk; frontend document workspace. |
| 2 — Origination & pipeline parity | Full 1003 in the frontend; pipeline + loan-dashboard chrome; conditions + notes (backend gap) + UI; status-model reconciliation + backdating. |
| 3 — Admin & operations | Org/user management, doc-types admin, folder-templates admin, app settings, role/access admin. |
| 4 — Folder AI evaluation | Provider-agnostic `AiPort` (Anthropic/OpenAI/DeepSeek) per tenant, per-folder eval, cost tracking, PDFBox parsing. Off by default. |
| 5 — Integrations | GoHighLevel sync; **MISMO 3.4 export (required)**, import (optional). |
| 6 — Production cutover & decommission | Provision the LOS Cognito pool; deploy MSFG-suite (Docker) + msfg-suite-web (S3/CloudFront); flip `app.msfgco.com`; run the app on a **non-owner DB role** (engages RLS); retire `mortgage-app`. |

The full parity inventory that seeds Phases 1–6 is a **Phase 0 deliverable** (the Parity Checklist, D4).

---

## 2. Phase 0 goal & non-goals

**Goal:** prove MSFG-suite + msfg-suite-web run together **end-to-end locally** with a real loan
flowing through, **close the tenancy null-org hole**, **design (not provision)** the deployed Cognito
seam, and produce the **authoritative parity checklist** that gates the whole cutover.

**Non-goals (YAGNI):**
- No real Cognito / AWS provisioning (deferred to Phase 6).
- No parity features (documents, conditions, AI, integrations — later phases).
- No deployment, no DNS, no decommission.
- No data migration (greenfield).

---

## 3. Current-state seam facts (verified 2026-06-15)

The two sides already substantially agree — Phase 0 confirms and hardens, it does not rebuild.

### Backend (`msfg-suite`, pkg `com.msfg.los`, Gradle, Flyway head **V17**)

- **Roles** — `platform` `Role` enum: `LO, PROCESSOR, UNDERWRITER, CLOSER, ADMIN, PLATFORM_ADMIN`
  (authority prefix `ROLE_`). `CognitoRolesConverter` maps `cognito:groups` → authorities by **exact
  enum-name match**; unknown groups are dropped.
- **`org_id`** — `TenantContextFilter` reads JWT claim key **`org_id`** → `TenantContextHolder`.
  **GAP:** when the claim is absent/empty the filter leaves the tenant **unset and the request
  proceeds** (NIL-org write risk). This is the hardening target (D2).
- **JWT (non-local `SecurityConfig`, `@Profile("!local")`)** — resource server, `issuer-uri:
  ${COGNITO_ISSUER}` (env-driven, JWKs auto-discovered), validates the bearer token (access-token
  shape by default), principal = `sub`.
- **Local profile** — `LocalDevSecurityConfig` + `DevPrincipalFilter` auto-inject a dev principal:
  user `00000000-0000-0000-0000-000000000001`, **org `00000000-0000-0000-0000-0000000000aa`**,
  `cognito:groups=["ADMIN"]`, authorities `ROLE_ADMIN` + `ROLE_PLATFORM_ADMIN`. No token required.
- **CORS** — `los.cors.allowed-origins`; local allows `http://localhost:5173,http://localhost:3000`;
  non-local from `${LOS_CORS_ALLOWED_ORIGINS}`.
- **Create loan** — `POST /api/loans`, `CreateLoanRequest { loanPurpose (required: PURCHASE|…),
  mortgageType?, lienPriority?, amortizationType?, noteAmount?, loanOfficerId? }`; requires `ROLE_LO`
  or `ROLE_ADMIN`; → `201` + `LoanSummaryResponse`.
- **Pipeline** — `GET /api/loans?status?&page=0&size=20` → `PagedResponse<LoanListItemResponse>`
  `{ id, loanNumber, status, loanOfficerId, primaryBorrowerName, propertyCity, propertyState, updatedAt }`.

### Frontend (`msfg-suite-web`, React 19 / Vite / TS)

- Typed client via `openapi-typescript` + `openapi-fetch` + React Query; `gen:api` →
  `openapi-typescript http://localhost:8080/v3/api-docs -o src/lib/api/schema.d.ts`.
- **Auth modes** gated by `VITE_AUTH_MODE`: **`local`** (`LocalDevAuth`, no token, grants `ADMIN` on
  org **`…0000000000aa`**) vs **`cognito`** (OIDC code+PKCE, fail-closed).
- Runtime config from `/config.json`; API base via `resolveBaseUrl()` = `config.apiBaseUrl` else
  `VITE_API_BASE_URL` (dev fallback `http://localhost:8080`).
- **`org_id`** read from `profile["org_id"] ?? profile["custom:org_id"]`; empty → clears session +
  shows `OrgNotConfigured` (fail-closed). Roles from `cognito:groups`; `Role = LO | PROCESSOR |
  UNDERWRITER | CLOSER | ADMIN | PLATFORM_ADMIN`.
- Deploy infra `MsfgLosWebSpaStack` (S3 + CloudFront, OAC, strict CSP) **references** a Cognito pool
  (for CSP) but does **not** create one.

### Seam alignment verdict

Roles match exactly; `org_id` claim name matches; **local-dev auth on both sides already targets the
same dev org `…0000000000aa`**; frontend dev base URL already points at `localhost:8080`; backend
local CORS already allows Vite's `:5173`. **The local stack should talk end-to-end with no Cognito
work** — Phase 0 verifies this, captures any contract drift, and hardens the one real hole.

---

## 4. Deliverables

### D1 — Local end-to-end verification (glue; minimal/no new code)

Stand up both apps locally and prove the core loop:

1. Backend: `docker compose up -d` (Postgres) + `./gradlew :app:bootRun --args='--spring.profiles.active=local'`.
2. Frontend: `VITE_AUTH_MODE=local` Vite dev server on `:5173`, base URL `http://localhost:8080`;
   run `npm run gen:api` against the running backend so the typed client is current.
3. Drive the loop through the UI: dev sign-in → **create a loan → see it in the pipeline → open it.**
4. Confirm: CORS preflight passes, the `ApiResponse` / `PagedResponse` envelope deserializes,
   and the `CreateLoanRequest` / `LoanListItemResponse` contracts match what the UI sends/expects.

**Acceptance:** a loan created in msfg-suite-web (local) appears in the pipeline list and opens, with a
**preview screenshot** captured as proof. Any contract drift discovered is logged into the Parity
Checklist (D4), not silently fixed here.

### D2 — Tenancy seam hardening (backend, TDD)

Close the NIL-org hole: a **non-local** authenticated request whose JWT lacks a usable `org_id` claim
must **fail closed** rather than proceed tenant-less.

- Reject (401/403) when `org_id` is absent, empty, or not a valid UUID, in the non-local security path.
- Must **not** affect the `local` profile (dev principal already carries org `…aa`).
- TDD: write the failing test first (authenticated request, no `org_id` → rejected; valid `org_id` →
  passes and binds the tenant). Follow existing `platform`/tenancy test patterns.

**Acceptance:** new test(s) red→green; full `./gradlew build` stays green (484+ tests).

### D3 — Deployed-Cognito seam design doc (`docs/cutover/cognito-deploy-seam.md`, doc only)

Document — do **not** provision — the new dedicated LOS Cognito pool:

- The 6 groups named exactly `LO / PROCESSOR / UNDERWRITER / CLOSER / ADMIN / PLATFORM_ADMIN`.
- A **pre-token-generation Lambda** that injects the **bare `org_id`** claim (user→org lookup; all
  users → MSFG org `…aa` initially) so the backend's `org_id` reader and the frontend's fail-closed
  gate both see it without a `custom:` prefix.
- Which token the SPA sends as the bearer and how `org_id` + `cognito:groups` land in that token
  (id-vs-access decision + the Lambda trigger version implied).
- Env wiring: `COGNITO_ISSUER` (backend), `LOS_CORS_ALLOWED_ORIGINS` (backend), and the SPA
  `config.json` (`apiBaseUrl`, `cognito.*`).
- Explicit **token-shape risk** to validate when the pool is first provisioned (Phase 6).

**Acceptance:** doc exists and is internally consistent with §3 facts.

### D4 — The Parity Checklist (`docs/cutover/PARITY-CHECKLIST.md`) — the keystone artifact

The authoritative, **phase-organized** inventory of every `mortgage-app` behavior/endpoint that must
exist in MSFG-suite + msfg-suite-web before the flip, each tagged **have / partial / missing** with the
owning cutover phase. Seeded from the gap analysis already performed:

- **Documents:** S3 presigned direct-upload + Object Lock/WORM (missing) · folders + folder templates
  (missing) · table-backed document types + classification (missing) · review workflow
  accept/reject/request-revision/status-history/bulk (missing) · → Phase 1.
- **Origination/pipeline:** full 1003 in UI (partial) · dashboard chrome / Key Dates / advance-status
  modal (partial) · conditions (`LoanCondition`) + notes (`LoanNote`) backend modules (missing) ·
  11-stage milestone model + status backdating (partial/missing) · → Phase 2.
- **Admin:** org/user management · doc-types admin · folder-templates admin · app settings · → Phase 3.
- **AI:** folder AI evaluation + provider-agnostic `AiPort` + cost tracking + PDFBox parsing (missing) → Phase 4.
- **Integrations:** GoHighLevel sync (missing) · MISMO export (missing, required) · MISMO import
  (optional) · → Phase 5.
- **Auth/roles:** borrower + agent + manager roles exist in `mortgage-app` but **not** in MSFG-suite's
  staff-only role set — flag as an open parity question (borrower/agent self-service portal scope) → Phase 2/3.
- **Audit:** `audit_log` coverage on document/loan operations (partial) → Phase 1/3.

**Acceptance:** checklist exists, covers every `mortgage-app` controller/feature area, tags each item,
and assigns a phase. This is the definition-of-done for the cutover and the source for later specs.

### D5 — Working-model note (`docs/cutover/README.md`, short)

Record: this program owns both repos; the local run recipe (D1); the branch convention
(`cutover/phase-N-*` per phase in each repo); how the two repos' commits relate during the cutover.

**Acceptance:** doc exists; another engineer can start both apps locally from it.

---

## 5. Testing strategy

- **D2** is TDD (backend JUnit/Testcontainers, mirror existing tenancy tests; red→green; full build green).
- **D1** is verified by running the real stack and exercising the UI (preview screenshot as evidence) —
  not asserted.
- **D3/D4/D5** are documents; "tested" by internal-consistency review against the §3 facts.

## 6. Success criteria (Phase 0 done when all true)

1. Loan created in msfg-suite-web (local) shows in the pipeline and opens — screenshot captured.
2. Backend rejects a non-local authenticated request with no/empty/invalid `org_id` — test green;
   `./gradlew build` green.
3. `docs/cutover/PARITY-CHECKLIST.md` written, phase-tagged, reviewed.
4. `docs/cutover/cognito-deploy-seam.md` + `docs/cutover/README.md` written.

## 7. Risks & open items

- **Token-shape risk** (id vs access token; bare `org_id` via pre-token-gen Lambda) — validated when
  the real pool is provisioned (Phase 6); recorded in D3.
- **Borrower/agent roles** — `mortgage-app` has self-service borrower + agent (+ manager) roles absent
  from MSFG-suite's role enum. Whether the cutover must preserve a borrower/agent portal is an open
  scope question logged in D4 for Phase 2/3, not resolved in Phase 0.
- **Contract drift** found during D1 is logged to D4 and resolved in the owning phase, not patched ad hoc.

## 8. Out of scope (explicit)

Real Cognito/AWS provisioning · any parity feature build · deployment/DNS/decommission · data
migration · MISMO · AI · GoHighLevel · borrower/agent portal.
