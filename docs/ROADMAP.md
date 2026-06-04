# MSFG LOS — Roadmap

The living plan. Each phase is its own **spec → plan → build** cycle (see `docs/specs/` and
`docs/superpowers/plans/`). UWM EASE intel that informs each subsystem lives in `docs/reference/`.

**Conventions carried from Spec 1:** modular monolith, MISMO/ULAD-aligned model, ports-and-adapters
for vendors (stub-first), Cognito JWT, loan-scoped access, Flyway, TDD. UWM is loan-scoped
`/Loan/{loanId}/{borrowerId}/{Controller}/{Action}` — our REST mirrors it (`/api/loans/{id}/...`).

---

## ✅ Milestone 0 — Foundation (DONE, merged `f2437ad`)
Spec 1: Core loan spine — Loan aggregate, lifecycle state machine, pipeline, loan-scoped access,
borrowers, NPI crypto, Cognito security. `platform · app · loan-core · parties`. 37 tests.

---

## Milestone 1 — The 1003 (URLA application)  ← NEXT
The data heart. Maps 1:1 to UWM's `Origination`-area controllers. Builds entirely on the spine.

| Spec | Delivers | UWM analog |
|---|---|---|
| **S2** | Personal Information — full borrower PII, addresses, contact, citizenship (NPI encryption goes live) | `Borrower` |
| **S3** | Employment & Income — income entities, doc-less VOI/tax-transcript status | `Income` |
| **S4** | Assets & Liabilities — entity grids, verification, DTI include/exclude | `Asset`, `LoanLiabilities` |
| **S5** | REO + Loan Information + **calc engine** (LTV/CLTV/TLTV, DTI, Housing Expense comparison, Details-of-Transaction ledger, cash-to-close) | `RealEstate`, `LoanInformation`, `Expense`, `DetailsOfTransaction` |
| **S6** | Declarations + Government Monitoring (HMDA demographics, per-borrower Q&A) | `Declarations`, `GovernmentMonitoring` |

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

## Cross-cutting subsystems (built incrementally, behind ports)
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
