# MSFG LOS

Backend **Loan Origination System** for MSFG, modeled on UWM EASE. A modular-monolith
Spring Boot service that owns the loan file and the loan-origination workflow. Fully
independent program (own DB, own auth, own deploy).

> **Status:** Spec 1 — *Foundation + Core Loan Spine* — complete. A createable,
> lifecycle-managed, access-controlled loan with borrowers, served over a REST API.
> See `docs/specs/` for the design and the roadmap toward the full 1003 (URLA).

## Tech stack

Java 21 · Spring Boot 3.3 · Gradle (Kotlin DSL, multi-module) · PostgreSQL 16 · Flyway ·
Spring Security (OAuth2 resource server, AWS Cognito) · springdoc/OpenAPI · Testcontainers · JUnit 5.

## Prerequisites

- **JDK 21** (Temurin recommended)
- **Docker** running (used for local Postgres and for Testcontainers in the test suite)

## Quick start (local)

```bash
docker compose up -d                                          # Postgres on :5432
./gradlew :app:bootRun --args='--spring.profiles.active=local'
```

Then open **Swagger UI**: http://localhost:8080/swagger-ui.html (API docs at `/v3/api-docs`).

**Local auth:** the `local` profile uses `LocalDevSecurityConfig` — every request is
auto-authenticated as a fixed **dev ADMIN** (no token needed), so you can exercise the API
directly. Use `00000000-0000-0000-0000-000000000001` as `loanOfficerId` when creating loans.
⚠️ **Never run the `local` profile in a deployed environment** — dev/prod use real Cognito JWT validation.

Smoke test once it's up:
```bash
DEV=00000000-0000-0000-0000-000000000001
curl -s -X POST localhost:8080/api/loans -H 'Content-Type: application/json' \
  -d "{\"loanPurpose\":\"PURCHASE\",\"mortgageType\":\"CONVENTIONAL\",\"loanOfficerId\":\"$DEV\"}"
curl -s localhost:8080/api/loans            # pipeline
```

## Build & test

```bash
./gradlew build         # compiles all modules + runs the full test suite (needs Docker for Testcontainers)
```

The suite (36 tests) runs against a **real Postgres** via Testcontainers and the real Spring MVC
stack via MockMvc — unit tests for crypto/lifecycle/number-gen, integration tests for persistence,
security (401/403), loan-scoped access control, and the borrower invariants.

## Modules

```
platform/    cross-cutting: base/auditable JPA entities, response envelope + error model,
             AES-256-GCM NPI encryption, Role/CurrentUser, loan-number contract
loan-core/   Loan aggregate + SubjectProperty + status history, lifecycle state machine,
             pipeline, loan-scoped access guard, service + REST
parties/     BorrowerParty (borrowers) with single-primary invariant, service + REST
app/         Spring Boot bootstrap, security (Cognito + local-dev), config, Flyway migrations, Dockerfile
```
Only `app` is a Spring Boot application (bootJar); the others are libraries. Flyway migrations live in
`app/src/main/resources/db/migration/`.

## API (Spec 1)

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/loans` | Create a loan (LO/ADMIN) → `id` + `loanNumber` |
| GET | `/api/loans` | Pipeline (paginated; `?status=&page=&size=`) |
| GET | `/api/loans/{id}` | Loan summary |
| PATCH | `/api/loans/{id}` | Update core fields / subject property |
| POST | `/api/loans/{id}/status` | Transition status `{targetStatus, reason}` (guarded) |
| POST/GET/PATCH/DELETE | `/api/loans/{loanId}/borrowers[/{id}]` | Manage borrowers |

All routes require authentication; loan data is loan-scoped (`ADMIN` sees all; others see loans they own).

**Loan lifecycle:** `STARTED → APPLICATION_IN_PROGRESS → SUBMITTED → IN_UNDERWRITING →
APPROVED_WITH_CONDITIONS → CLEAR_TO_CLOSE → CLOSING → FUNDED` (+ terminal `WITHDRAWN/CANCELLED/DENIED`,
non-terminal `SUSPENDED`). Transitions are role-gated and recorded in `loan_status_history`.

## Configuration

| Profile | Auth | Datasource |
|---|---|---|
| `local` | dev ADMIN principal (no IdP) | `localhost:5432/msfg_los` (docker compose) |
| `dev` / `prod` | Cognito JWT (`COGNITO_ISSUER`) | `DB_URL` / `DB_USER` / `DB_PASSWORD` env |
| `test` | stubbed (Testcontainers) | ephemeral Postgres container |

Env vars: **`LOS_NPI_KEY`** (base64 of a 32-byte AES-256 key — *required* in dev/prod; the app
fails fast if unset, never encrypting NPI with a default key), **`COGNITO_ISSUER`** (Cognito pool
issuer URI), **`DB_URL`/`DB_USER`/`DB_PASSWORD`**.

## Docs

- Design / spec: `docs/specs/2026-06-03-los-spec1-foundation-loan-spine.md`
- Implementation plan: `docs/superpowers/plans/2026-06-03-los-spec1-foundation-loan-spine.md`
- UWM EASE reference (the model): `docs/reference/`

## Known Spec-1 follow-ups (tracked for later)

- **Error-handling hardening:** extend `ResponseEntityExceptionHandler` so unmatched-route 404s
  return the `ApiError` envelope consistently in prod (today a test-only MVC setting makes test
  assert 404; prod returns Boot's default `/error` JSON — no stacktrace leak, just shape parity).
- **`loanOfficerId` from principal:** create currently takes the owner in the request body; a later
  spec should default it to the authenticated user (admins may override).
- **Lifecycle:** add a `SUSPENDED → IN_UNDERWRITING` resume edge and consider gating `CLOSING` to `CLOSER`.
