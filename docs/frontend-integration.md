# MSFG LOS — Frontend Integration Guide

> For the **frontend** (a separate repo/app, e.g. `msfg-suite-web`) that consumes this backend's REST API.
> This backend is a stateless Spring Boot JWT resource server. The **API contract is the integration seam** —
> the frontend never edits backend code; backend changes it needs are requested from the backend session.

## TL;DR — fastest way to start
1. Run the backend locally in the **`local` profile** — it auto-authenticates **every** request as a dev ADMIN
   with a tenant already set, so you need **no Cognito and no tokens** to build screens early:
   ```bash
   docker compose up -d         # local Postgres
   ./gradlew :app:bootRun --args='--spring.profiles.active=local'
   # API at http://localhost:8080 · Swagger UI at http://localhost:8080/swagger-ui.html
   ```
2. Point your dev server at `http://localhost:8080`. CORS already allows `http://localhost:5173` (Vite) and
   `http://localhost:3000` (Next) in the `local` profile — see [CORS](#cors).
3. Generate a typed client from the live OpenAPI spec (below). Build against the [stable endpoints](#stable-endpoint-surface).
4. Wire real Cognito auth later (dev/prod) — see [Auth](#auth-cognito--jwt). The contract (envelopes, routes) is identical.

## Base URLs
| Env | API base | Notes |
|---|---|---|
| Local | `http://localhost:8080` | `local` profile = dev ADMIN auto-auth (no IdP). |
| Dev/Prod | TBD (set when deployed) | Real Cognito JWT required on every `/api/**` call. |

## OpenAPI / typed client (the contract)
springdoc is enabled and **public** (no auth):
- Swagger UI: `GET /swagger-ui.html`
- OpenAPI JSON: `GET /v3/api-docs`

Generate a typed client from `/v3/api-docs` (e.g. `openapi-typescript`, `orval`, or `openapi-generator`).
**Treat the generated client as the source of truth for request/response schemas** — this guide stays
high-level on purpose so it can't drift. Existing endpoints are **stable**; new specs (Assets/Liabilities,
REO, etc.) only **add** endpoints, never break shipped ones.

## Response envelopes (every endpoint)
Success:
```json
{ "success": true, "data": <payload> }
```
Paged list:
```json
{ "success": true, "data": { "items": [...], "page": 0, "size": 20, "totalElements": 123, "totalPages": 7 } }
```
Error (any 4xx/5xx — FLAT, not nested):
```json
{ "success": false, "code": "VALIDATION_ERROR", "message": "human-readable", "fields": { "field": "why" }, "timestamp": "2026-06-05T..." }
```
- `401` = no/invalid token · `403` = authenticated but not allowed (wrong role / not your loan) ·
  `404` = not found **or cross-tenant** (existence is never leaked across orgs) · `400` = validation
  (`code: VALIDATION_ERROR`, often with `fields`).

## Auth (Cognito + JWT)
The backend validates **RS256 Cognito JWTs** as a resource server (stateless; send `Authorization: Bearer <token>`).
- **You provision a Cognito app client** (Authorization Code + **PKCE**, SPA) in the LOS user pool (separate from
  the dashboard's pool). Hosted UI or Amplify/oidc-client both fine. The backend does not issue tokens.
- **REQUIRED claim: `org_id`** — the token MUST carry an `org_id` claim (the tenant UUID). Tenancy is enforced
  from it on every call; a token without `org_id` fails closed. Ensure the pool emits `org_id` (custom attribute
  / pre-token-generation Lambda) in the token the SPA sends. *(Backend coordination point — flag if the pool
  isn't emitting it yet.)*
- **Roles** come from the `cognito:groups` claim, mapped to `ROLE_*`. Known roles:
  `LO`, `PROCESSOR`, `UNDERWRITER`, `CLOSER`, `ADMIN`, `PLATFORM_ADMIN`. The UI should gate features by role
  (e.g. only `LO`/`ADMIN` may create loans; `/api/admin/**` is `PLATFORM_ADMIN` only).
- **Local shortcut:** in the `local` profile there is NO real auth — every request is a dev ADMIN
  (`org_id = 00000000-0000-0000-0000-0000000000aa`). Build and demo without Cognito; add the real flow before dev/prod.

## CORS
Configured via `los.cors.allowed-origins` (exact-origin allowlist; `allowCredentials=false` since auth is a
Bearer header, not cookies). Applies to `/api/**`.
- `local` profile already allows `http://localhost:5173` and `http://localhost:3000`.
- For dev/prod, the **backend session** adds your deployed frontend origin (uncomment in
  `application-{dev,prod}.yml` or set the `LOS_CORS_ALLOWED_ORIGINS` env var, comma-separated). Send the request
  to the backend session — don't edit backend config from the frontend repo.

## Stable endpoint surface (today)
All under `/api`, all tenant- + loan-scoped (the JWT's `org_id` filters everything; you only ever see your org's data).
Exact schemas: see `/v3/api-docs`.

- **Loans / pipeline**
  - `POST /api/loans` · `GET /api/loans` (paged; filter by status/assignee) · `GET /api/loans/{id}` ·
    `PATCH /api/loans/{id}` · `POST /api/loans/{id}/status` (guarded lifecycle transition `{targetStatus, reason}`).
- **Borrowers & PII** (1003 §1a)
  - `POST|GET|PATCH|DELETE /api/loans/{loanId}/borrowers[/{borrowerId}]` — SSN is **masked** in responses
    (`ssnLast4`/`ssnMasked`); full SSN only via `POST /api/loans/{loanId}/borrowers/{borrowerId}/reveal-ssn`
    `{reason}` (audited — show a reason prompt in the UI).
  - `POST|GET|PATCH|DELETE /api/loans/{loanId}/borrowers/{borrowerId}/addresses[/{addressId}]` (typed address history).
- **Employment & Income** (1003 §1b–1e)
  - `POST|GET|PATCH|DELETE /api/loans/{loanId}/borrowers/{borrowerId}/employments[/{employmentId}]`.
  - `POST|GET|PATCH|DELETE /api/loans/{loanId}/borrowers/{borrowerId}/income[/{incomeId}]`
    (`incomeType` is a ULAD enum; employment-income items link to an `employmentId`, other-source items don't).
  - `GET /api/loans/{loanId}/income/summary` — the income grid: rows (borrower · type · employer · monthly) +
    `totalMonthlyIncome`.
  - `POST|GET /api/loans/{loanId}/income/verifications` — doc-less VOI/tax-transcript tracker (stub today).
- **Admin** (platform)
  - `/api/admin/**` — org/tenant provisioning etc. (`PLATFORM_ADMIN` only).

*(Coming next, additive: Assets & Liabilities, REO + Loan Info + LTV/DTI calc, Declarations/HMDA, then
conditions/pricing/AUS/disclosures. Watch `/v3/api-docs` and `docs/ROADMAP.md`.)*

## Design inputs (use these)
The UI is modeled on **UWM EASE**. Rich design material already lives in this repo:
- `docs/reference/uwm-ease-frontend-schematic.md` — page/section structure (incl. the 1003 sections).
- `docs/reference/uwm-ease-wireframe-spec.md` — ASCII wireframes (pipeline, 1003 forms, the income grid, etc.).
- `docs/reference/uwm-ease-master-reference.md` — the route/feature registry.

## Working rule (avoids confusion with the backend session)
**One owner per area.** The frontend repo never edits backend Java, Gradle, or config. Anything you need from
the backend — a new endpoint, a field, CORS origin, the `org_id` token claim — is a request to the **backend
session**, which owns and ships it. The OpenAPI spec is the shared contract between the two.
