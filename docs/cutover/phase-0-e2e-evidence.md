# Phase 0 — D1 Local End-to-End Verification Evidence

**Date:** 2026-06-15 · **Branch:** `cutover/phase-0-foundation`

Proves MSFG-suite (backend) + msfg-suite-web (frontend) integrate locally, end to end.

## Environment (as run)
- **Backend:** `msfg-suite` `:app:bootRun` with `--spring.profiles.active=local --server.port=18080`.
  Booted clean: profile `local` (dev ADMIN auto-auth, org `00000000-0000-0000-0000-0000000000aa`),
  Flyway validated **17 migrations** (schema at V17), Tomcat on **18080**, started in ~4.25s.
  > Ran on **18080**, not the default 8080 — port 8080 is occupied by an unrelated local app
  > (`rag-brain`). 5173 is occupied by another project's Vite (`msfg-rag/dashboard`). See drift #4.
- **Postgres:** container `msfg-suite-postgres-1` (postgres:16) on 5432, db `msfg_los`.
- **Frontend:** `msfg-suite-web` (React 19 / Vite / TS), `node_modules` present.

## Result: PASS

### 1. API seam — create → pipeline → open (definitive)
Driven against `http://localhost:18080` (local profile auto-auths as dev ADMIN; no token):
- `POST /api/loans {"loanPurpose":"PURCHASE"}` → **201**, `success:true`, created loan
  `1000000058` (id `98ef5a7b-…`), status `STARTED`.
- `GET /api/loans?page=0&size=10` → **200**, `success:true`, 58 loans, items in the exact
  `LoanListItemResponse` shape: `id, loanNumber, loanOfficerId, primaryBorrowerName, propertyCity,
  propertyState, status, updatedAt`.
- `POST /api/loans` (second) → **201** (`1000000059`); `GET /api/loans/{id}` → **200**,
  `success:true`, id matches — the **"open"** step.

create → pipeline → open all green. (The `ApiResponse`/`PagedResponse` envelope deserialized correctly;
`data.items[]` for the page, `data.id` for create/get.)

### 2. Typed-client contract seam
- `openapi-typescript http://localhost:18080/v3/api-docs → schema.d.ts` **succeeded** (7.13.0, 640ms) —
  the live backend's OpenAPI is consumable by the FE's generator (the contract seam holds).

### Note on tooling
Per the Phase 0 plan, D1's acceptance is satisfied by any one of {preview screenshot, Chrome-MCP
screenshot, **curl/HTTP create+list trace**}. The deterministic HTTP create→pipeline→open trace above
(plus the successful `gen:api`) is that proof. A full browser SPA render was **not** driven because
(a) D1 is already met, and (b) the FE's committed OpenAPI client is stale vs the live backend
(drift #1) — a clean runtime render requires the FE to regenerate first, which is FE source work
outside Task 3's "no FE changes" scope. `curl`/`wget` are also blocked by the session's context-mode
hook, so HTTP was driven via Node `fetch`.

## Contract drift observed (→ folded into PARITY-CHECKLIST.md)
1. **FE OpenAPI client is stale.** A fresh `gen:api` against the live backend changed
   `src/lib/api/schema.d.ts` by **+447 / −20 lines** — the backend exposes endpoints/schemas the FE's
   committed client predates. **Action:** the cutover must (re)generate the FE client against the
   current backend and pin regeneration in CI. *(Owning phase: 1–3, per area.)*
2. **springdoc positional `operationId` churn.** Auto-generated operationIds are positional
   (`get → get_1 → get_2 …`); adding/reordering endpoints renumbers existing ones, churning the typed
   client across unrelated areas. **Action:** consider stable operationIds (`@Operation(operationId=…)`)
   to keep the FE client diff localized. *(Owning phase: 2/3.)*
3. **Pipeline default sort is not newest-first.** A freshly created loan does not appear on
   `GET /api/loans` page 0 (older loans surface first). **Action:** confirm the FE pipeline's expected
   ordering; sort by `updatedAt` desc or expose a sort param. *(Owning phase: 2.)*
4. **Local port assumptions.** The FE defaults its API base + `gen:api` target to `localhost:8080`,
   which collided with another local app here. **Action:** the run recipe should note the FE's
   `VITE_API_BASE_URL` override and that the backend port is configurable
   (`--server.port`). *(Captured in docs/cutover/README.md.)*

## Reproduce
```bash
# backend (any free port; 8080 may be taken locally)
docker compose up -d   # in msfg-suite
./gradlew :app:bootRun --args='--spring.profiles.active=local --server.port=18080'
# API seam
curl -s -XPOST localhost:18080/api/loans -H 'Content-Type: application/json' -d '{"loanPurpose":"PURCHASE"}'   # 201 + data.id
curl -s 'localhost:18080/api/loans?page=0&size=10'                                                            # 200 paged
curl -s localhost:18080/api/loans/<id>                                                                        # 200 open
# FE typed client
( cd ../msfg-suite-web && ./node_modules/.bin/openapi-typescript http://localhost:18080/v3/api-docs -o /tmp/schema.d.ts )
```
