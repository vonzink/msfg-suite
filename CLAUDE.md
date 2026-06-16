# MSFG LOS — Project Guide (CLAUDE.md)

## What this is
A backend **Loan Origination System** modeled on UWM EASE — the system that processes a mortgage
from application to closing. **It is evolving into a multi-tenant SaaS platform:** MSFG is the first
tenant, but it must serve **many lender companies, small to large**, each with their own users, data
isolation, integrations, API keys, AI keys, and config. Fully independent program (own repo / DB /
auth / deploy) — NOT the Node/MySQL dashboard at dashboard.msfgco.com.

## Status
- ✅ **Spec 1 — Foundation + Core Loan Spine** — done + merged (`f2437ad`), 37 tests.
- ✅ **Spec 2 — Platform Foundation (multi-tenancy + portability)** — done + merged (`31d190a`), 44 tests.
- ✅ **Spec 3 — Personal Information & PII** — done + merged (`30361eb`), 56 tests. NPI encryption is LIVE (encrypted/masked SSN + audited reveal).
- ✅ **Spec 4 — Employment & Income** — done + merged (`0f61957`), 95 tests. New `income` module: Employment + unified `IncomeItem` (ULAD), loan-level income grid + TOTAL, doc-less VOI/tax-transcript verification tracker behind `IncomeVerificationPort` (stub adapter).
- ✅ **Spec 5 — Assets & Liabilities** — done + merged (`f3756ef`), 129 tests. New `financials` module: unified `Asset` + `Liability` (ULAD), liability **DTI include/exclude inputs** (flag + reason + monthsRemaining), loan-level summaries (TOTAL ASSETS; all-vs-DTI payment totals), doc-less VOA tracker behind `AssetVerificationPort` (stub).
- ✅ **Spec 6A — Loan Information + REO** — done + merged (`4f11b1d`), 146 tests. Loan §4 fields on `Loan`/`SubjectProperty` + new `reo` module (loan-scoped RealEstateOwned CRUD + summary).
- ✅ **Spec 6B — Calc engine** — done + merged (`65677f1`), 162 tests. New `qualification` module (read-only): `MortgageMath` + `GET /api/loans/{id}/calculations` (LTV/CLTV/TLTV, P&I, proposed housing PITI, net rental, **DTI front/back**). opus re-derived the math. DoT/cash-to-close → 6C.
- ✅ **Spec 7 — Declarations + HMDA** — done + merged (`aa55203`), 175 tests. New `declarations` module: `BorrowerDeclarations` + `BorrowerDemographics` (1:1 per borrower; multi-selects via `EnumSetConverter`), PUT-upsert.
- ✅ **Contract-nits batch (frontend §3)** — done + merged (`e5d9efc`), 188 tests. `LoanSummaryResponse` +6 fields (lien/amortization/address/estimatedValue); pipeline list enriched (primary borrower name via `PrimaryBorrowerNameResolver` port, property city/state, updatedAt); `loanOfficerId` optional → defaults to principal; role-aware `GET /api/loans/{id}/status/transitions` (`TransitionsResponse`).
- ✅ **Processing modules (FE work-order)** — **Fees** (`fb79ba0` + PUT-upsert/credits reconcile `2223d35`, V11) · **CoC** (`e5cd3b6`, V12) · **Document Manager** (`6873ebc`, V13, storage behind `DocumentStoragePort`) · **Role access model** (`9e33679`: PROCESSOR/UNDERWRITER/CLOSER org-wide via `LoanAccessGuard.hasOrgWideView`, LO owner-scoped, PLATFORM_ADMIN excluded — opus-security-reviewed). **272 tests.** Follow-up: enum-allowlist `CognitoRolesConverter` (unvalidated `cognito:groups` → authorities).
- ✅ **Pricing/Lock (FE §4)** — merged (`802ab20`), **311 tests**, **V15 next**. New `pricing` module (V14: `rate_lock`/`pricing_adjustment`/`lock_event` append-only audit): `PricingEnginePort` + deterministic stub, lock state machine (control-your-price/extend/rate-change/relock + EXPIRED matrix), lock-confirmation letter via documents `storeGenerated` seam. Built by the parallel session (stalled 11h at finish), adopted + opus-reviewed (no HIGH/CRITICAL) + merged here.
- ✅ **AUS + Credit (FE §4)** — merged (`1e12711`), **370 tests**, **V16 next**. New `aus` module (V15: `vendor_credential` org+loan encrypted/masked/write-only · `aus_profile` · `credit_order` · `aus_run`): `AusVendorPort`/`CreditVendorPort` shaped 1:1 to real DU (MISMO 3.4/casefile) / LPA (S2S v6.1/Key) / credit (MISMO 2.x/reissue) wire surfaces, deterministic stubs, ONE_CLICK runs, findings/credit-report artifacts as documents, ERROR rows survive rollback (REQUIRES_NEW). Opus pass: GO, no HIGH/CRITICAL. Follow-up chip: HTML-escape + nosniff at the documents seam (inherited pattern).
- ✅ **Contacts (FE §6)** — merged (`07ecb7d`), **384 tests**, **V17 next**. New `contacts` module (V16): loan-scoped CRUD `{role enum ×8, name, company, phone, email}`, ordinal max+1, reo mirror. **THE FRONTEND WORK-ORDER IS COMPLETE.**
- ✅ **Disclosures — TRID LE/CD (milestone, not work-order)** — merged (`0feded3`), **484 tests**, **V17**. New `disclosures` module: in-house `BusinessDayCalculator` (both 1026.2(a)(6) defs + federal-holiday set, CFPB-verified counting) + timing/deadline engine (advisory, gate-ready) + `TolerancePolicy` bucketing + 3-trigger CD reset detector (symmetric APR band + 1026.22(a)(5)(ii) relief); APR + regulated H-24/H-25 rendering delegated to `DisclosureVendorPort` (deterministic stub; DocMagic/IDS/Docutech + e-sign + UCD MISMO 3.3.0 deferred behind it). Grounded in a verified-TRID research pass; **opus review caught 3 composition blockers** (ERROR-row poison/NPE, tolerance LE-vs-LE, dead LE-deadline) — all fixed RED-first before merge. Additive: fee tolerance-fact columns + loan `consummationDate` + `DocumentType.{LOAN_ESTIMATE,CLOSING_DISCLOSURE}`.
- 🎉 **THE FULL 1003 (URLA) IS COMPLETE** — Personal Info · Employment & Income · Assets & Liabilities · Loan Information · REO · qualification calc engine · Declarations · HMDA, all on the multi-tenant spine. **Next (`docs/ROADMAP.md`): real vendor adapters (DU/LPA/credit/disclosure onboarding) → AI milestone.**

## North-star requirements (design for these from the beginning)
1. **Multi-tenant** — many companies, small→large; per-tenant data isolation, users, config,
   integrations, branding. *(Built in Spec 2: shared DB + `org_id` + `@TenantId` + RLS.)*
2. **Cloud-portable** — backend ships as a **Docker image**, not locked to AWS. AWS-specific services
   (S3 storage, Cognito auth) sit behind **ports** so they're swappable (GCP, Fly.io, Render, a VPS…).
   ⚠️ The Spring Boot backend runs as a long-lived container — it does **not** run on Vercel. Vercel/
   Netlify would host a *future frontend*; the backend goes anywhere Docker runs.
3. **Provider-agnostic AI** — OpenAI, Claude (Anthropic), DeepSeek behind one interface;
   provider / model / API key selectable **per tenant**.
4. **Integration surface** — partner **API endpoints + inbound/outbound webhooks** for other
   companies' systems (API-key/OAuth auth, signed payloads, retries), scoped **per tenant**.

## Tech stack
Java 21 · Spring Boot 3.3 · Gradle (Kotlin DSL, multi-module **modular monolith**) · Postgres 16 +
Flyway · Cognito JWT *(to be abstracted)* · Testcontainers · MISMO/ULAD-aligned data model.

## Modules
`platform` (crypto, error model, auditing, security, + tenancy + ports) · `loan-core` · `parties` ·
`app`. Future: `application-1003`, `conditions`, `pricing`, `aus`, `documents`, `disclosures`,
`ai`, `integrations`.

## Architecture principles
- Modular monolith with **ports-and-adapters at every external edge** (storage, auth, AI, payments,
  credit/AUS/pricing vendors, webhooks) — stub-first, swappable adapters.
- **Tenant-scoped everything:** every domain row carries an organization id; access is tenant + loan scoped.
- MISMO/ULAD-aligned (3.4 import/export feasible). Cloud-agnostic: Docker + env/secret config.

## Build / run / test
- `./gradlew build` (needs Docker for Testcontainers). Always use `./gradlew` (wrapper pinned to 8.10;
  system Gradle is 9.x). JDK: `/Library/Java/JavaVirtualMachines/temurin-21.jdk`.
- Local run: `docker compose up -d` + `./gradlew :app:bootRun --args='--spring.profiles.active=local'`
  → Swagger at :8080 (local profile = dev ADMIN, no real IdP).

## Conventions
- **TDD** (test-first). Every phase: brainstorm → spec → granular plan → **subagent-driven build**
  (fresh agent per task) → **2-stage review** (spec compliance + code quality) → finish-branch merge.
- **Flyway migrations are a strict ordered sequence** (V1, V2, …) — a serialization point; never
  author migrations in parallel branches.
- Consistent response envelope (`ApiResponse`/`PagedResponse`/`ApiError`); domain exceptions carry status+code.
- **NPI/PII encrypted at rest** (AES-256-GCM via `EncryptedStringConverter`); masked by default;
  full value only via an **audited reveal** endpoint.
- Tenant- and loan-scoped access on every endpoint; least privilege.
- Reviewable single-purpose commits; commit/merge only when asked; branch off `main` for changes.

## Working style (Zack)
Move fast, ship often. Blunt, audit-quality feedback welcome (cite file:line). Don't over-ask — make
reasonable calls, he'll redirect. Verify by running the thing, not just asserting it works.

## Docs
`docs/ROADMAP.md` (forward plan) · `docs/specs/` · `docs/superpowers/plans/` · `docs/reference/` (UWM EASE intel).

## Decided (architecture)
- **Tenant isolation (built in Spec 2):** shared database, **`org_id` on every domain row**. Two layers:
  (1) **app layer — Hibernate `@TenantId`** (auto-filters JPQL reads, auto-stamps writes) from the JWT
  `org_id` claim — this is the **active runtime enforcement**; **load tenant-scoped entities by
  `findByIdAndOrgId`, NOT `findById`** (`@TenantId` does NOT filter `find()`-by-PK). (2) **Postgres RLS**
  (`FORCE` + `WITH CHECK`, fail-closed) — a **proven backstop that only engages when the app connects as a
  non-owner DB role**. ⚠️ **DEPLOYMENT REQUIREMENT:** in dev/prod, run the app's datasource as a
  **non-owner role** (the seeded `app_user`, given a login/password out-of-band) with **Flyway as the
  owner**, or RLS is bypassed at runtime (today the app connects as owner → app-layer only). A
  large/regulated tenant can later be promoted to a dedicated schema/DB without app changes.
  - *Spec-2 follow-ups:* engage RLS at runtime (non-owner datasource); `BorrowerService` self-scoping
    `findByIdAndOrgId`; reject JWTs with no `org_id` claim (avoid NIL-org writes); ADMIN-role cross-tenant test.
- **Portability:** external services behind ports with swappable adapters — storage
  (`platform.storage.BlobStoragePort`), AI, email, payments, webhooks. Backend = a Docker image that runs on
  any cloud. ⚠️ **Auth is NOT yet a port:** it's Spring Security OAuth2 resource-server (OIDC-JWT),
  config-swappable for any OIDC IdP (Auth0/Keycloak) via `issuer-uri`, but the Cognito-specific claim mapping
  (`org_id`, `cognito:groups`) is currently inline in `app/config` (`SecurityConfig` +
  `OrgScopedJwtAuthenticationConverter` + `CognitoRolesConverter`). A provider-neutral principal/tenant-claim
  **port** is a planned deliverable of the cutover auth/role-reconciliation phase. Config-driven per env/tenant.
- **Sequencing:** **Platform Foundation (multi-tenancy + port seams) lands before the 1003 sections.**
- **AI:** provider-agnostic (`AiPort`) with OpenAI / Anthropic-Claude / DeepSeek adapters; provider+model+key per tenant. (Own spec.)
- **Integrations:** partner REST API + signed inbound/outbound webhooks, per tenant. (Own milestone.)

Decided in Spec 2: **one company per user** (single `org_id` JWT claim). Still open: multi-org users (later); per-tenant subdomain routing.
