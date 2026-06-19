# Unified Integration Architecture — MSFG (4 systems as one product)

**Date:** 2026-06-18
**Status:** ✅ Approved (owner-confirmed in brainstorming session)
**Owner/conductor:** this msfg-suite session (single conductor; see §13)
**Supersedes the framing of:** `docs/cutover/` (the "retire mortgage-app, suite becomes the single
backend" premise is **inverted** — see §11)

---

## 1. Context & reframe

MSFG runs four independently-deployed systems that must feel like **one product** to a client who
starts on the marketing site, applies, and has their loan processed:

1. **msfg.us** — Next.js 16 / React 19 / TS / Tailwind v4 / Prisma 7 on Vercel. Public marketing
   site + lead capture + apply wizard + GoHighLevel CRM + its own Postgres. (`github.com/vonzink/msfg.us`)
2. **mortgage-app** — Spring Boot 3.2 (Java 17) + CRA React + AWS CDK monorepo. Borrower/agent/LO
   application + portal. Single-tenant. Cognito pool `us-west-1_S6iE2uego` (shared with the legacy
   `dashboard.msfgco.com`). Serves `app.msfgco.com`. (`/Users/zacharyzink/MSFG/WebProjects/mortgage-app`)
3. **msfg-rag** — Java 21 / Spring Boot 3.5 / Spring AI 1.1 + Postgres+pgvector. Public AI mortgage
   Q&A "brain" with citations, compliance guardrails, and a control plane. Runs on `:8090`.
   (`github.com/vonzink/msfg-rag`)
4. **msfg-suite** — Java 21 / Spring Boot 3.3 / Postgres 16 + Flyway. Multi-tenant LOS: full 1003,
   documents, qualification, pricing/lock, AUS+credit, disclosures, conditions, fees, CoC. (this repo)

**The reframe:** a prior "cutover program" assumed msfg-suite would *replace* mortgage-app's backend.
That is no longer the goal. mortgage-app **stays** as the application/portal layer. The decision below
keeps all four and defines where the seams fall.

## 2. The decision in one sentence

**msfg-suite is the system of record for the loan** (1003, documents, processing); **mortgage-app is
the customer-facing application/portal** whose backend thins into a client of suite; **msfg.us is
top-of-funnel lead capture**; **msfg-rag is a standalone public AI knowledge service** integrated by
contract only. One Cognito pool gives all of them SSO. **Single-tenant MSFG now, with the
multi-tenant seams preserved.**

## 3. Locked decisions (the forks that drive everything else)

| # | Decision | Choice | Rationale |
|---|---|---|---|
| D1 | Loan/doc/1003 ownership | **msfg-suite = system of record** | Suite already has the deepest model + the unique processing engine; one source of truth for the loan. |
| D2 | Multi-tenancy | **MSFG-only now, keep seams** | Ship single-tenant; suite's `org_id`+RLS stays active with a constant MSFG org. SaaS reachable without a rip-out. |
| D3 | Where the 1003 is filled | **msfg.us = lead only; mortgage-app = full 1003** | Matches the existing `source_lead_id` seam; msfg.us stays thin top-of-funnel. |
| D4 | Identity/SSO | **One Cognito pool, extended** | Reuse `us-west-1_S6iE2uego`; add `org_id` claim + reconcile role allowlist. True SSO, least rework. |
| D5 | Frontends | **Three focused apps, shared identity** | msfg.us (marketing/lead), mortgage-app FE (borrower/agent/LO portal), msfg-suite-web (staff console). |
| D6 | mortgage-app backend | **Strangler BFF** | Stops owning loan data; becomes a thin adapter/aggregator over suite that shrinks over time. |
| D7 | Lead handoff | **Link/reference; loan born in suite at first authenticated apply** | No msfg.us→suite coupling; `source_lead_id` links back. |
| D8 | Documents | **Single store = suite S3** | mortgage-app's own buckets retire (greenfield, no migration). |
| D9 | msfg-rag boundary | **Standalone public AI knowledge service; contract-only** | Owns knowledge Q&A, never loan PII; not under suite-as-SoR. |
| D10 | AI consolidation | **Not now** | Suite's internal `AiPort` (folder-eval/UW) stays separate from msfg-rag. Revisit later (§10). |

## 4. Target architecture (layers)

```
            Visitor        Borrower / Agent / LO        Processor / UW / Closer
               |                    |                            |
        ┌──────────────┐   ┌──────────────────┐        ┌────────────────────┐
        │   msfg.us    │   │  mortgage-app FE │        │   msfg-suite-web   │   FRONTENDS
        │ marketing+   │   │ borrower·agent·  │        │  staff processing  │
        │ lead capture │   │     LO portal    │        │      console       │
        └──────┬───────┘   └────────┬─────────┘        └─────────┬──────────┘
               │                    │                            │
        ══════════════ One Cognito pool — SSO · org_id claim · unified roles ══════════
               │                    │                            │
               │ lead ref      ┌────┴─────────┐                  │ REST
               │ (link)        │ mortgage-app │  REST            │
               │               │  BFF (thin)  ├───────────┐      │
               │               └──────────────┘           ▼      ▼
               │                                  ┌───────────────────────────┐
               │  msfg.us Postgres (leads)        │        msfg-suite         │  SYSTEM OF RECORD
               │  + GoHighLevel (lead CRM)        │  1003 · documents · cond. │  + PROCESSING
               │                                  │  qualification · pricing  │
        ┌──────┴────────┐                         │  AUS · disclosures · dash │
        │   msfg-rag    │  :8090 ask API          └───────────┬───────────────┘
        │ AI knowledge  │  (control plane)                    │
        │  Q&A brain    │◄───── embedded by msfg.us      Suite Postgres (org_id+RLS) · Suite S3 (WORM)
        └───────────────┘       (later: portals/console via surfaces)
```

## 5. Data ownership matrix

| Data | Owner (SoR) | Notes |
|---|---|---|
| **Lead** (pre-application interest) | **msfg.us** (Postgres) + GoHighLevel | Demote msfg.us `Application` table to lead-staging. |
| **Loan / 1003 / borrower tree** | **msfg-suite** | Born at first authenticated apply; `source_lead_id` links to the lead. |
| **Documents** | **msfg-suite** (S3, WORM) | One store. Borrower uploads go straight to suite's presigned flow. |
| **Mortgage knowledge / Q&A corpus** | **msfg-rag** (pgvector + curated corpus + link registry) | No loan PII. |
| **Identity / users / roles** | **Cognito** (`us-west-1_S6iE2uego`) | Suite materializes a `user_account` (id = sub) on `/me`. |
| **CRM / lead nurture** | **GoHighLevel** (via msfg.us) | Stays at the funnel; not a loan-stage concern. |

## 6. The flow & handoffs (lead → apply → process → back)

1. **Lead** — visitor applies on msfg.us → `Lead` row (Postgres + GHL). No auth.
2. **Handoff** — msfg.us routes the borrower to `app.msfgco.com` carrying a **lead reference**. No
   msfg.us→suite call required.
3. **Apply** — borrower authenticates (Cognito) in mortgage-app; the **loan is created in suite** at
   first authenticated apply, carrying `source_lead_id`. Borrower completes the full 1003 → suite.
   - ✅ *Already partly built:* mortgage-app `main` has a merged `funnel-intake-endpoint` +
     `harden(intake)` (validates `sourceLeadId`/`loanPurpose`, restricts LO assignment). Build on it.
4. **Process** — staff work the loan in the suite console (conditions, AUS, pricing/lock, disclosures, UW).
5. **Back to client** — status + documents flow back to borrower/agent through the same suite APIs,
   rendered in the mortgage-app portal.

## 7. Identity & SSO

- **One pool, extended** (`us-west-1_S6iE2uego`):
  - Add a **pre-token-generation Lambda** that injects `org_id` (constant MSFG value today).
  - **Reconcile one role allowlist** across the two naming schemes:
    - mortgage-app: `Admin, Manager, LO, Processor, Borrower, RealEstateAgent` (+ dormant `External`)
    - suite: `LO, PROCESSOR, UNDERWRITER, CLOSER, ADMIN, PLATFORM_ADMIN`
  - Adding a claim is **additive** → the legacy `dashboard.msfgco.com` on the same pool is unaffected.
  - Principal resolution: mortgage-app resolves by `email`→`sub`; suite by `sub`. Standardize on `sub`.
- **Key new suite backend work — the borrower/agent access model** (the gap the parity checklist
  flagged): add `Borrower` + `RealEstateAgent` roles to suite's allowlist, and **per-loan
  self-scoping** in `LoanAccessGuard` (borrower by `user_id`; agent via a `loan_agents`-style link).
  This is what lets the mortgage-app FE call suite directly with a borrower/agent token.

## 8. Frontends

- **msfg.us** — marketing + lead (Next.js / Vercel). Public.
- **mortgage-app FE** — borrower + agent + **LO** portal at `app.msfgco.com`. **LO lives here**
  (origination/sales role, next to borrowers/agents); LO-relevant suite actions (pricing/lock,
  edit-terms, conditions) surface in the portal *via suite APIs* rather than sending LOs into the
  processing console.
- **msfg-suite-web** — internal staff processing/underwriting console (processor/underwriter/closer).
- All three SSO via the one pool. mortgage-app FE + suite-web call suite APIs.
- *Polish path (deferred):* extract a shared design-system + auth + generated-API-client library so
  the portal and console look/feel identical. Not required for v1.

## 9. mortgage-app backend → strangler BFF

- Stops being its own loan/document/1003 store; becomes a **thin BFF** that:
  1. receives the msfg.us lead handoff and creates the loan in suite (owns `source_lead_id` idempotency),
  2. aggregates/adapts suite responses for the existing React FE (so the FE isn't rewritten wholesale),
  3. progressively delegates to suite and **shrinks** — end state may approach zero.
- The borrower/agent access model **moves into suite** (§7), so over time the FE can call suite directly.

## 10. msfg-rag integration (contract-only)

- **Boundary:** owns mortgage **knowledge** Q&A only (curated corpus + pgvector + citations +
  control plane). Never touches loan PII. Its own deployable + DB.
- **The one call:** `POST http://<brain-host>:8090/api/ai/mortgage/ask` — public, no auth, 10 req/min
  per session/IP. Send `sessionId` + `question` (+ `pageRoute` + `surface:"PUBLIC"` to light up the
  control plane). Returns `answer, citations[], confidence, humanEscalationRequired, disclaimer`, and
  when relevant `recommendedPage{route,label}, links[], nextAction`.
- **Consumers:** embedded by msfg.us today; embeddable in the portal/console later via the `surface`
  enum. The **control plane is the seamless connective tissue** — the AI can route a visitor toward
  "apply" (`app.msfgco.com`), a loan officer, or the right page.
- **Behavior the embedder must respect:** always show answer + citations + disclaimer;
  `humanEscalationRequired:true` → show the loan-officer hand-off and suppress recommendations;
  control-plane links are server-curated registry rows (safe to render directly).
- **Reference:** `msfg-rag/docs/AI-BRAIN-INTEGRATION.md` (full contract + integration checklist).
- **D10 (deferred):** suite's internal `AiPort` (folder-eval/underwriting AI) stays **separate** from
  msfg-rag for now. Possible future convergence: msfg-rag becomes the org's single AI platform serving
  both public Q&A and internal loan AI via `surface`. Not planned in v1.

## 11. Reframe of the cutover program

- **Vindicated, not wasted:** the cutover already built suite copies of mortgage-app features
  (documents/S3, conditions, notes, dashboard, `/me`, `/me/loans`). With D1 (suite = SoR) these become
  the **foundation** consumed by *both* frontends — nothing is thrown away.
- **Premise inverted:** suite sits **under** mortgage-app, not replacing it. mortgage-app's FE stays;
  its backend thins (§9).
- **PARITY-CHECKLIST disposition:** the "❌ replace mortgage-app backend" items are reinterpreted as
  "suite is the shared backend." The still-open items most relevant now:
  - **Borrower/agent self-service** (was an open question) → **YES**, build it (§7).
  - MISMO 3.4 export (suite Phase 5), AI folder-eval (suite Phase 4), deploy + DNS (Phase 6) — stand.

## 12. Deployment & domains

| Surface | Host | Domain | Notes |
|---|---|---|---|
| msfg.us | Vercel | `msfg.us`, `staging.msfg.us` | Unchanged. Push to `main` = prod deploy. |
| mortgage-app FE | S3 + CloudFront | `app.msfgco.com` | Current canonical. |
| msfg-suite-web (console) | S3 + CloudFront | **`los.msfgco.com`** (proposed) | New subdomain. |
| msfg-suite backend | **Docker on ECS Fargate + ALB** | **`api.msfgco.com`** (proposed) | Region us-west-1. |
| mortgage-app BFF | EC2/Fargate | (behind app) | Keep, shrinking. |
| msfg-rag | (existing) | `:8090` internal + admin `:5173` | Embedded by msfg.us. |
| legacy dashboard | (existing Node/MySQL) | `dashboard.msfgco.com` | Untouched; shares the pool. |

- **CORS:** suite must allow `app.msfgco.com` + `los.msfgco.com` (and local dev origins).
- **DB version target (open):** mortgage-app is on **Flyway 11 / PostgreSQL 18**; suite on
  **Postgres 16 / Flyway**. Separate DBs so it's fine today; pin a combined-product PG target later.

## 13. Execution model

- **Single conductor** (this msfg-suite session) drives the program and fans out **subagents** for
  parallel work. Rationale — three serialization points make uncoordinated parallel sessions unsafe:
  1. **Flyway is a strict ordered sequence** — suite migrations must be single-threaded through the conductor.
  2. **The seam is shared state** — `org_id` claim shape, the reconciled role allowlist, suite's
     OpenAPI contract, and CORS are consumed by all repos; they can't drift.
  3. **History** — prior parallel sessions produced same-checkout collisions, direct-to-`main` races,
     and merge trees not matching branch tips.
- **Serial foundation first (no fan-out):** suite borrower/agent access model → reconcile role
  allowlist → define the `org_id` claim → pin the OpenAPI contract + CORS. This unblocks everything.
- **Then fan out** (subagents, or repo-split humans): msfg.us lead/handoff · mortgage-app FE re-point
  + BFF shrink · suite-web console gaps. Independent **once the contract is frozen**.
- **Human-gated (conductor prepares, owner executes):** all AWS actions — pre-token-gen Lambda,
  CDK/Cognito/DNS/secrets, Fargate+ALB, GHAS/CodeQL — plus commit/push/merge approval and prod deploys.

## 14. Roadmap (serial → parallel)

- **Phase F (Foundation, serial):** suite identity seam — `org_id` claim design, borrower/agent roles
  + per-loan self-scoping in `LoanAccessGuard`, reconciled role allowlist, OpenAPI contract pin +
  stable operationIds, CORS for the new origins.
- **Phase A (parallel after F):**
  - *msfg.us:* finalize lead capture + handoff link to `app.msfgco.com`; demote `Application` to
    lead-staging; keep GHL. (Plus the in-flight branded-auth/password track.)
  - *mortgage-app:* thin the backend to a BFF over suite; lead-intake → suite loan creation
    (`source_lead_id`); re-point the FE area-by-area; retire its loan/doc store.
  - *msfg-suite-web:* fill staff-console gaps against the frozen contract.
- **Phase B:** msfg-rag embed polish (control-plane routing into the funnel; later authenticated surfaces).
- **Phase C (human-gated deploy):** Cognito Lambda + flags, CDK/Fargate/ALB, DNS (`api`/`los`),
  RLS non-owner datasource, MISMO export, AI folder-eval.

## 15. Open questions / deferred

- **AI consolidation (D10):** merge suite `AiPort` into msfg-rag later, or keep two AI systems? Deferred.
- **Combined-product PostgreSQL version target** (16 vs 18). Deferred.
- **Shared FE component/auth library** (§8 polish). Deferred.
- **LO actions surfaced in the portal** — exact set of suite endpoints the portal exposes to LOs. To
  detail in Phase F/A.
- **mortgage-app stray branch** `claude/vigilant-mcnulty-695dce` (1 unique commit) — keep or drop?

## 16. Clean-base snapshot (2026-06-18)

All four repos on `main`, working trees clean. Unpushed local commits: msfg-suite +2 (repo hygiene),
msfg-rag +2 (this integration doc seed + ignore), msfg.us +1 (pre-existing spec doc). msfg.us lead
bugfix isolated on `fix/lead-email-validation-order` (tested). PR #8 open/mergeable, gated on the
Cognito flag (= a prod deploy). See the session log for details.
