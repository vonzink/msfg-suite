# MSFG LOS — Roadmap

The living plan. Each phase is its own **spec → plan → build** cycle (see `docs/specs/` and
`docs/superpowers/plans/`). UWM EASE intel that informs each subsystem lives in `docs/reference/`.

**Platform shape:** **multi-tenant SaaS** (many lender companies, small→large) — shared DB, `org_id` on
every row + Postgres RLS; **everything external behind ports** (storage, auth, AI, email, payments,
webhooks) so the backend is a cloud-agnostic Docker image. MSFG is tenant #1.

**Conventions carried from Spec 1:** modular monolith, MISMO/ULAD-aligned model, ports-and-adapters
(stub-first), tenant- + loan-scoped access, Flyway, TDD. UWM is loan-scoped
`/Loan/{loanId}/{borrowerId}/{Controller}/{Action}` — our REST mirrors it (`/api/loans/{id}/...`).

---

## ✅ Milestone 0 — Foundation (DONE, merged `f2437ad`)
Spec 1: Core loan spine — Loan aggregate, lifecycle state machine, pipeline, loan-scoped access,
borrowers, NPI crypto, Cognito security. `platform · app · loan-core · parties`. 37 tests.

---

## ✅ Milestone 0.5 — Platform Foundation (multi-tenancy + portability) — DONE (Spec 2, merged `31d190a`, 44 tests)
Landed **before** the 1003, while the schema was tiny. `tenancy` module + `org_id`/`@TenantId` on every
domain row + Postgres RLS (FORCE/WITH CHECK) + tenant context/filter + PLATFORM_ADMIN. Boot-verified.
Follow-ups (CLAUDE.md): engage RLS at runtime via a non-owner DB role; `BorrowerService` self-scoping.
- **Organization (tenant)** entity + admin provisioning; **platform-admin** role above tenant roles.
- **`org_id` on every domain table** (loan, borrower_party, loan_status_history, + all future) with
  backfill; **TenantContext** (current org from the JWT claim) + tenant-scoped access; **Postgres RLS**
  as defense-in-depth.
- **Per-tenant config** store (settings, feature flags, integration + AI credentials — secrets encrypted
  via the NPI cipher).
- **Ports-and-adapters convention** established: **auth port** (Cognito adapter today, swappable) + the
  seams reserved for storage / AI / email / payments / webhooks (built with their features).
- Local-dev gets a default org so `bootRun` still works.

## Milestone 1 — The 1003 (URLA application)
The data heart. Maps 1:1 to UWM's `Origination`-area controllers. Sits on the multi-tenant spine.

| Spec | Delivers | UWM analog |
|---|---|---|
| ✅ **S3** | Personal Information — full borrower PII, addresses, contact, citizenship (NPI encryption LIVE: encrypted/masked SSN + audited reveal). **DONE, merged `30361eb`, 56 tests.** | `Borrower` |
| ✅ **S4** | Employment & Income — `Employment` + unified `IncomeItem` (ULAD `IncomeType`), loan-level grid + TOTAL, doc-less VOI/tax-transcript tracker behind a stub port. **DONE, merged `0f61957`, 95 tests.** New `income` module. | `Income` |
| ✅ **S5** | Assets & Liabilities — unified `Asset` + `Liability` (ULAD), loan-level summaries (TOTAL ASSETS; all-vs-DTI payment totals), liability **DTI include/exclude inputs** (flag + reason + monthsRemaining), VOA verification stub. **DONE, merged `f3756ef`, 129 tests.** New `financials` module. | `Asset`, `LoanLiabilities` |
| 🔵 **S6** | REO + Loan Information + **calc engine** (LTV/CLTV/TLTV, DTI ratio, Housing Expense comparison, Details-of-Transaction ledger, cash-to-close — consumes the S4/S5 income + DTI inputs) — **NEXT** | `RealEstate`, `LoanInformation`, `Expense`, `DetailsOfTransaction` |
| **S7** | Declarations + Government Monitoring (HMDA demographics, per-borrower Q&A) | `Declarations`, `GovernmentMonitoring` |

→ **Full 1003 complete.** MISMO 3.4 (iLAD) import/export becomes feasible (`FnmaImport`, 3.4 export).

---

## Milestone 2 — Processing workflow
| Subsystem | Notes | UWM analog |
|---|---|---|
| **Conditions + Document Manager** | Condition tracking PTD/PTF, S3 upload, categorization, e-sign tracking, doc archive, 3.4 export | `LoanAttachment`, `Document`, `Docless`, `ProcessingAssistant` |
| **Date Tracking + VVOE** | Date milestones; VVOE **read-only tracker** (per-employment: method/source/3rd-party-number/attempts), **status-gated to Approved-With-Conditions**, fed by VOE vendor/ops | `DateTracking`, `VVOETracking` |
| **Contacts** | Broker + UWM contacts (AE, UW, closer), UTRACK sharing | `Contacts` |

---

## Milestone 3 — Pricing · Decisioning · Disclosures (heavy compliance)
| Subsystem | Notes | UWM analog |
|---|---|---|
| **Products & Pricing / Rate Lock** | Pricing engine, adjustments, lock lifecycle, lock confirmation | `Pricing`, `Fee` |
| **AUS** | Credit pull + DU/LPA one-click | `AUS/OrderAUS`, `Credit` |
| **Generate Documents + Disclosures** | **TRID disclosure-ownership branch** (own LE vs UWM-generated); `PackageTypeId` enum is **status-gated** (e.g. `8=Closing Disclosure`, `22=Condition`); e-sign + UCD validation pipeline; interviewer info NMLS-bound read-only | `Document/Order`, `Document/ClosingDisclosure*`, `UCDResults`, `DisclosuresCalendar`, `History/DisclosureHistory` |
| **Change of Circumstance** | The **TRID fee-tolerance / re-disclosure engine**. Reason enum = CFPB valid changed-circumstance set; Date-of-Discovery + reason justify the tolerance reset (3-business-day redisclosure clock). Tabs: Loan Structure / Fees / Preview / History. Collection model (batched fee changes) → pending → ops review (tolerance cure) → redisclosed CD | `ChangeOfCircumstancePreview/InsertPendingChanges` |

This milestone is a tight cluster — Generate Documents, CD, Change of Circumstance, UCD results, and
disclosure history all feed one **disclosure/UCD/eSign pipeline**. Compliance (TRID, fee tolerance,
disclosure clocks) is first-class and needs audit trails + date tracking (extend our
`LoanStatusHistory` / `AuditableEntity` foundation).

---

## Milestone 4 — Appraisal & Closing
| Subsystem | Notes | UWM analog |
|---|---|---|
| **Appraisal Manager + Tracker** | Order, product type (FNMA 1004/1073), AMC fulfillment, milestone timeline, two-way appraiser messaging, **Stripe payments** (PayNow in-app + SendPaymentLink to borrower + UpdatePaymentTracker reconciliation), cancel | `AppraisalManager`, `AppraisalTracker/*` |
| **Closing (UClose)** | UClose 3.0 scheduler (Hybrid eNote vs In-Person closing types); scheduler flow Review → SelectDate → Conditions/Contacts/Comments; eClose opt-in; GFE-from-UClose; **invoicing (Stripe)**; escalations | `UClose/*`, `ClosingScheduler/*`, `Eclose/*` |
| **Memory Maker / Closing Gifts** | Client-gifting: notes/emails/cards (10 designs) / fonts (7) / physical gifts with monogram+personalization; LO Partner Points; order total incl. tax/shipping. *Ancillary / nice-to-have.* | `ClosingGifts/RedeemProducts` |

---

## Milestone 5 — Ancillary
Pre-Approval Letter · Amortization · Loan Calendar / Disclosures Calendar · Request to
Withdraw/Cancel (`ChangeLoanStatus`) · Pipeline playbook · Import (`FnmaImport` piggyback/HELOC) ·
Loan Lab · Ultimate Submission progress.

---

## Milestone 6 — Platform capabilities (cross-tenant; each its own spec)
- **AI platform** — one `AiPort` with **OpenAI / Anthropic-Claude / DeepSeek** adapters; provider, model,
  and API key selectable **per tenant** (keys stored encrypted in tenant config). Use cases TBD per
  feature (doc summarization, condition drafting, borrower comms, classification).
- **Partner API + webhooks** — a **public/partner REST API** + **signed inbound/outbound webhooks** so
  other companies' systems exchange data with the LOS. Per-tenant API keys / OAuth clients, webhook
  subscriptions, HMAC signing, delivery retries + dead-letter, event catalog (loan.created,
  status.changed, condition.cleared, …). Builds on the Milestone-0.5 multi-tenant + ports foundation.
- **Tenant admin & onboarding** — provision a company, its branding, users, integration + AI config,
  feature flags, plan/limits.

## Cross-cutting subsystems (built incrementally, behind ports)
- **Multi-tenancy** — `org_id` everywhere + RLS + TenantContext (foundation in Milestone 0.5; every new
  table/endpoint inherits it).
- **Cloud portability** — storage / auth / AI / email / payments adapters; Docker image runs anywhere.
- **Disclosure / UCD / eSign pipeline** — the spine connecting Generate Documents, CD, Change of
  Circumstance, UCD results, disclosure history. A major cluster; design its data model deliberately.
- **Stripe payments** — appraisal + closing invoices (two confirmed surfaces).
- **Document generation (PDF)** — pre-approval letters, disclosures, CD, lock confirmations, packages.
- **Vendor integration ports** — credit bureaus, DU/LPA, pricing, AMC (appraisal), VOE/VVOE, e-sign,
  title. Stub-first (Spec 1 decision); real adapters per subsystem.
- **MISMO 3.4 (iLAD)** — import/export; our ULAD-aligned model makes mapping mechanical.
- **Real-time** — status/milestone push (UWM uses SignalR → our WebSocket/STOMP).
- **Audit & compliance** — TRID/HMDA/fee-tolerance trails, date-of-discovery + disclosure clocks.

## Architectural implications (validated by the new intel)
- **Loan-scoped + borrower-scoped sub-resources** — UWM's `{borrowerId}` confirms our
  `/api/loans/{id}/borrowers/{bid}` shape; income/assets/REO hang off loan or borrower as appropriate.
- **Status-gating is pervasive** (VVOE only in Approved-With-Conditions; Package Type gated by status)
  → our loan **lifecycle state machine** is the right foundation; many features guard on loan status.
  Expand the lifecycle (add the FILE_IMPORTED/SETUP early states UWM uses; SUSPENDED resume edge).
- **Compliance is first-class** — needs robust audit, date tracking, and a disclosure/redisclosure
  clock engine; not bolt-on.
- **New integration categories** — payments (Stripe) and document generation (PDF) join the
  credit/AUS/pricing ports.
- **"Tracker" pattern** — Appraisal/VVOE are read-only status dashboards fed by vendor/back-office
  data, distinct from editable 1003 entity forms. A reusable read-model pattern.

## Reference
- `docs/reference/uwm-ease-frontend-schematic.md`, `…-wireframe-spec.md` — the 1003 + workspace UI
- `docs/reference/uwm-ease-backend-architecture-analysis.md` — platform/routing intel
- `docs/reference/uwm-ease-backend-addendum-docs-appraisal-vvoe-coc.md` — Generate Docs · Appraisal · VVOE · CoC
- `docs/reference/uwm-ease-master-reference.md` — consolidated ~65-route registry, Memory Maker, UClose
