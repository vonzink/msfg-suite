# MSFG LOS — Spec 1: Foundation + Core Loan Spine

> Greenfield backend Loan Origination System (LOS) for MSFG, modeled on UWM EASE.
> This spec covers **Spec 1 only** (the foundation + loan spine). It also records the
> project-wide decisions and the piece-by-piece roadmap so later specs slot in cleanly.

---

## Context

MSFG wants a backend that handles the **actual loan-origination process** — the 1003
(URLA) application, conditions, pricing/lock, AUS, documents, disclosures — modeled on
**UWM EASE** (`ease.uwm.com`). The three source docs in `docs/reference/` describe EASE:
two front-end specs (Schematic, Wireframes) and one reverse-engineered back-end intel
note (EASE itself runs legacy ASP.NET MVC + SignalR — **reference only**, not our target).

This is a **full LOS** — ~15–20 subsystems — so we build it as **sequenced sub-projects**,
each with its own spec → plan → build cycle, going deep on one at a time. Spec 1 stands up
the foundation and the **core loan spine**: a createable, lifecycle-managed, access-controlled
loan that lists in a pipeline. It is a shippable, testable unit and the spine every later
subsystem hangs off. **This is a fully independent program** (own repo, own Cognito, own DB,
own deploy) — unrelated to `dashboard.msfgco.com`.

## Locked decisions

| Area | Decision |
|---|---|
| Relationship | **Independent program** — unrelated to the dashboard; new logins/Cognito |
| Language/Framework | **Java 21 (LTS) · Spring Boot 3 · Gradle (Kotlin DSL)** |
| Database | **Postgres 16 · Flyway** migrations (versioned SQL, run on boot) |
| Architecture | **Modular monolith**, **multi-module by bounded context** |
| Domain style | **Pragmatic layered + focused domain** (JPA entities + service + domain layer; not full DDD) |
| Integrations | **Stub/mock first** behind ports; real vendors later |
| Data model | **MISMO/ULAD-aligned (pragmatic)** — relational Postgres, ULAD-named enums/fields; no raw MISMO XML |
| Auth | **AWS Cognito (new pool)** → Spring Security resource server (RS256 JWT) → group-based RBAC |
| Cloud | AWS; **Docker-first**, deploy target (ECS Fargate vs EC2) **deferred to infra phase** |
| First milestone | Core loan + **full 1003** (this spec = the foundation slice of it) |

## Architecture (Spec 1)

- **Modular monolith**, one deployable Spring Boot app, boundaries enforced at compile time.
- **Ports-and-adapters** reserved for integration edges (not built in Spec 1 — no consumer yet; YAGNI).
- **REST + JSON**, auto-documented via **OpenAPI/Swagger** (springdoc).
- **Spring Security resource server**: validate Cognito RS256 JWTs; map Cognito groups → app roles
  (`LO`, `PROCESSOR`, `UNDERWRITER`, `CLOSER`, `ADMIN`).
- **Testcontainers** (real Postgres in integration tests), **TDD**, JSON structured logging, Actuator health.

### Modules (★ = built in Spec 1)
- `app/` ★ — Spring Boot bootstrap, security config, OpenAPI, profiles, Dockerfile, compose, Flyway migrations
- `platform/` ★ — base/auditable entity, response envelope, error model, pagination, id + loan-number gen, NPI encryption
- `loan-core/` ★ — Loan aggregate, SubjectProperty, lifecycle state machine, pipeline, loan-scoped authz
- `parties/` ★ — BorrowerParty (minimal identity; full PII in Spec 2)
- `integration-ports/` — deferred (created when first integration needs it)
- `application-1003/`, `conditions/`, `pricing/`, `aus/`, `documents/`, `disclosures/` — later specs

## Domain model (MISMO/ULAD-aligned)

- **Loan** (aggregate root): `id` UUID PK · `loanNumber` (generated, unique) · `loanOfficerId` (owner) ·
  `loanPurpose` · `mortgageType` · `lienPriority` · `amortizationType` · `status` · `noteAmount` (nullable
  early) · audit cols · `@Version`. Enums named to ULAD data points.
- **SubjectProperty** (`@Embeddable`, 1:1): address + estimated value.
- **BorrowerParty** (ULAD party, role=Borrower): `loanId` · `isPrimary` · `ordinal` · `firstName` · `lastName`.
  SSN/DOB/etc. deferred to Spec 2 (encrypted).
- **LoanStatusHistory**: `loanId` · `fromStatus` · `toStatus` · `changedBy` · `changedAt` · `reason`.
- **AuditableEntity** base populated from the Cognito principal via JPA auditing.
- **Loan-scoped access:** `ADMIN` sees all; others see loans they own (`loanOfficerId`)/are assigned to.

### Lifecycle (guarded state machine — hand-rolled)
```
STARTED → APPLICATION_IN_PROGRESS → SUBMITTED → IN_UNDERWRITING
        → APPROVED_WITH_CONDITIONS → CLEAR_TO_CLOSE → CLOSING → FUNDED
terminal: WITHDRAWN · CANCELLED · DENIED · SUSPENDED
```
Role-gated transitions (e.g. only `UNDERWRITER` → `APPROVED_WITH_CONDITIONS`); every transition writes history.

## API surface (Spec 1, all authed + loan-scoped)

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/loans` | Create loan → `id` + `loanNumber` |
| GET | `/api/loans` | Pipeline list (paginated, filter by status) |
| GET | `/api/loans/{id}` | Loan summary |
| PATCH | `/api/loans/{id}` | Update core fields |
| POST | `/api/loans/{id}/status` | Transition status (guarded) |
| POST/GET/PATCH/DELETE | `/api/loans/{id}/borrowers[/{bid}]` | Manage borrowers |

## Compliance baseline
Optimistic locking · global error handler + consistent envelope · JPA auditing · pagination/sort ·
UUID PKs + human loan number · **NPI columns encrypted at rest** (AES-GCM; pattern established now,
used in Spec 2) · audit history for status changes · least-privilege role checks on every endpoint.

## Roadmap (after Spec 1)
**S2** Parties & Personal Info (+NPI) → **S3** Employment & Income → **S4** Assets & Liabilities →
**S5** REO + Loan Info + calc engine (LTV/CLTV/TLTV, Details-of-Transaction, Housing Expenses, DTI) →
**S6** Declarations + HMDA → ✅ full 1003. Then Conditions/Docs → Pricing/Lock → AUS → Doc-gen/Disclosures
→ Ancillary. Cross-cutting (real-time, notifications, vendor adapters) layered in as needed.

## Out of scope (Spec 1)
Full 1003 fields; pricing/AUS/conditions/documents/disclosures; real vendor integrations; front-end;
deploy target + IaC (Docker-first now).

**Implementation plan:** `docs/superpowers/plans/2026-06-03-los-spec1-foundation-loan-spine.md`
