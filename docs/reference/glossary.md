# MSFG Integration — Glossary

Shared vocabulary for the 4-system MSFG product (one integrated funnel: capture → apply → process).
Cross-references the repos: **msfg.us**, **mortgage-app**, **msfg-suite** (this repo), **msfg-rag**.

## Systems

- **msfg.us** — Next.js marketing site + **lead-capture** funnel (live at `staging.msfg.us`, prod `msfg.us`).
  Captures the lead, then **hands off** to the app. Does NOT own the application. Hosted on the owner's EC2.
- **mortgage-app** — the **customer-facing portal** (borrower · agent · loan-officer). Spring Boot backend +
  Create-React-App frontend. Borrower fills the **full 1003** here. Serves `app.msfgco.com`. Its backend is a
  thin **BFF** (see *strangler*) over the suite.
- **msfg-suite** — *this repo*. The **system of record (SoR)**: the loan, every 1003 field, documents, and the
  underwriting engine. Java 21 / Spring Boot. Multi-tenant.
- **msfg-suite-web** — the **staff console** (React/Vite) for processors/underwriters/closers; reads the suite.
- **msfg-rag** — a **standalone public AI Q&A brain** (`:8090`), contract-only, never touches loan PII.

## Architecture & platform

- **System of record (SoR)** — the suite. The single owner of loan data; the other front-ends write into and
  read from it.
- **BFF / strangler** — mortgage-app's backend, thinning into a pass-through *backend-for-frontend* over the
  suite (the "strangler" pattern: new system grows around the old).
- **Ports & adapters (hexagonal)** — every external edge (storage, IdP, AI, vendors) sits behind a **port**
  (interface) with **stub-first, swappable adapters**. E.g. `BlobStoragePort`, `PrincipalPort`, `UserAdminPort`,
  `PasswordlessAuthPort`.
- **Modular monolith** — the suite is one deployable split into Gradle modules: `platform`, `loan-core`,
  `parties`, `identity`, `income`, `financials`, `reo`, `qualification`, `declarations`, … + `app`.
- **`ApiResponse` envelope** — the suite's response shape `{ success, data }` (errors:
  `{ success, code, message, fields, timestamp }`). Domain exceptions carry status+code
  (`ValidationException`→400, `ForbiddenException`→403, `NotFoundException`→404, `ConflictException`→409).

## Multi-tenancy

- **`org_id`** — the tenant id on every domain row. MSFG's is `00000000-0000-0000-0000-0000000000aa` (seeded in
  `V3__multitenancy.sql`; also the local-dev org).
- **`@TenantId`** — Hibernate annotation that auto-filters reads + stamps writes by the current tenant. ⚠️
  `findById` does NOT honor it → load tenant rows by `findByIdAndOrgId`.
- **RLS** — Postgres Row-Level Security, the fail-closed backstop (`FORCE` + `WITH CHECK`); engages only when the
  app connects as a **non-owner** DB role (`app_user`).
- **`TenantContext` / `TenantContextFilter`** — read `org_id` from the JWT, bind it for the request (Hibernate +
  the `app.current_org` Postgres session var). `requireOrgId()` throws if absent.

## Identity & auth

- **Cognito** — the AWS identity provider. Shared pool **`us-west-1_S6iE2uego`** (also backs the legacy
  `dashboard.msfgco.com`).
- **`cognito:groups`** — the JWT claim carrying role group strings (`Admin`, `LO`, `Borrower`, …).
- **Pre-token-generation Lambda** — injects the **bare `org_id`** claim into the ID token (the suite fail-closes
  without it). MSFG-only-now → a constant. See `docs/deploy/cognito-pretoken-org-claim.md`.
- **`PrincipalPort` / `JwtPrincipalAdapter` / `CurrentUser`** — the provider-neutral **read-side** identity seam
  (id/email/name/orgId/roles). The Cognito claim shape lives only in `JwtPrincipalAdapter`.
- **`UserAdminPort` / `StubUserAdminAdapter` / `CognitoUserAdminAdapter`** — the **write-side** seam (create user
  + reset password). Stub is the local/test default; the Cognito adapter is `@ConditionalOnProperty
  (los.identity.user-admin=cognito)`, dormant until cutover.
- **`OrgScopedJwtAuthenticationConverter`** — fail-closed: rejects (401) any token whose `org_id` is missing or
  not a UUID.
- **`CognitoRolesConverter`** — maps `cognito:groups` → suite `Role`s via a case-sensitive **allowlist**; unknown
  groups are dropped.
- **`user_account`** — the tenant-scoped staff/user row, **materialized on the first `/me` call** (its id = the
  Cognito `sub`).
- **Passwordless / EMAIL_OTP / USER_AUTH** — the borrower sign-in for the funnel: an emailed one-time code (no
  password). `PasswordlessAuthPort` selects `DevPasswordlessAdapter` (local auto-verify) vs `CognitoOtpAdapter`
  (native Cognito EMAIL_OTP via the USER_AUTH flow).
- **Dev bridge / `local` profile** — the no-AWS local seam: `LocalDevSecurityConfig` (suite) +
  `LocalSecurityConfig` (mortgage-app) honor **`X-Dev-Sub` / `X-Dev-Roles` / `X-Dev-Org`** headers. `@Profile
  ("local")` only — never in dev/prod.

## Roles

`BORROWER`, `REAL_ESTATE_AGENT` (self-service **party** roles), `LO` (loan officer), `PROCESSOR`, `UNDERWRITER`,
`CLOSER`, `MANAGER`, `ADMIN` (tenant staff), `PLATFORM_ADMIN` (platform operator, *excluded* from loan data).
Staff get org-wide views; LO is owner-scoped; parties are self-scoped to their own loan (`LoanAccessGuard`).

## The borrower funnel (capture → apply → loan)

- **Lead capture** — msfg.us apply wizard collects a lead (`POST /api/v1/leads`).
- **Hand-off token** — msfg.us mints a short-TTL **HS256** token (`HANDOFF_TOKEN_SECRET`, payload under claim
  `h`, non-sensitive) and redirects to the app's `/continue?t=…`.
- **`/continue`** — the mortgage-app transition page: decodes the token → passwordless verify → prefilled app.
- **"Loan born in the suite"** — at first authenticated apply, the loan is created in the **suite** (not msfg.us),
  carrying **`source_lead_id`**.
- **Intake** — `POST /api/loans/intake` (suite, idempotent on `source_lead_id`); the mortgage-app's
  `SuiteClient` / `continuePrefill` map onto it.

## Mortgage domain

- **1003 / URLA** — the Uniform Residential Loan Application (the borrower form). Sections: Personal Info,
  Employment & Income, Assets & Liabilities, Loan & Property, Declarations, Demographics/HMDA.
- **ULAD / MISMO** — the standardized mortgage data dictionaries the model aligns to (import/export feasible).
- **AUS** — Automated Underwriting System (DU = Fannie Desktop Underwriter, LPA = Freddie Loan Product Advisor).
- **TRID / LE / CD** — TILA-RESPA Integrated Disclosure; the Loan Estimate + Closing Disclosure + timing rules.
- **CoC** — Change of Circumstance (a re-disclosure trigger). **REO** — Real Estate Owned. **DTI / LTV** — debt-to-
  income / loan-to-value ratios (the `qualification` calc engine). **NPI / PII** — non-public info (SSN etc.),
  encrypted at rest, masked, revealed only via an audited endpoint.

## Program & process

- **Cutover program** — making msfg-suite the single backend under mortgage-app; phased (Phase 0–6; Phase A =
  local-e2e harness, Phase F = identity/access foundation).
- **Prompts 1–5** — this session's workstream: (1) prove+merge the funnel, (2) shared msfg.us brand, (3) LO/Admin
  user administration, (4) Cognito cutover prep, (5) prod hardening.
- **Cutover runbook** — `docs/cutover/cognito-cutover-runbook.md`: owner AWS actions + dev-bridge flip-off
  inventory + prod env matrix.
- **GHAS / CodeQL** — GitHub Advanced Security / the code-scanning workflow. Free now that the repos are public.
- **TDD / red-green** — the build discipline: one failing test → minimal code → repeat.
