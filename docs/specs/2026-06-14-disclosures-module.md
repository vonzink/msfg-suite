# MSFG LOS — Disclosures Module (TRID: Loan Estimate + Closing Disclosure)

> The next milestone after the frontend work-order. TRID LE/CD for **closed-end consumer mortgages
> secured by real property or a co-op**. Decisions (Zack, 2026-06-14): **(a) Hybrid** — a
> `DisclosureVendorPort` computes APR + renders the regulated H-24/H-25 forms (stub-first, real
> DocMagic/IDS/Docutech later); **we** own the timing engine, tolerance bucketing, and reset-trigger
> logic, and store every figure. **(c) Advisory + audited, gate-ready** timing engine (no hard
> consummation block in v1; hooks designed in). **Full recommended slice**, one additive migration **V17**.
> Grounded in a verified TRID research pass (12 CFR part 1026 + CFPB guides). **Money- AND
> compliance-critical → opus review pass before merge** + the standard two-stage per task.

## ⚠️ Verified regulatory floor — bake these in EXACTLY (cite inline; corrections from the research pass marked ★)
- **Two "business day" definitions** — `1026.2(a)(6)`. **PRECISE** = all calendar days except Sundays
  and the federal legal holidays in 5 USC 6103(a) (**Saturdays COUNT**; observed-date shifting applies).
  **GENERAL** = days the creditor's offices are open for substantially all business functions
  (**tenant-configurable**).
- **LE timing** — `1026.19(e)(1)(iii)`: deliver/mail LE ≤ **3 GENERAL** business days after application
  **AND** ≤ **7 PRECISE** business days before consummation. ★ The 7-day rule is keyed to the
  **delivery/mailing act, not receipt** — do NOT model it as "consumer receives 7 days before" and do
  NOT stack the mailbox +3 onto it.
- **CD timing** — `1026.19(f)(1)(ii)`: consumer **RECEIVES** CD ≤ **3 PRECISE** business days before
  consummation (a *receipt* standard).
- **Mailbox rule** — `1026.19(e)(1)(iv)` (LE) / `(f)(1)(iii)` (CD): non-in-person delivery is *deemed*
  received **3 PRECISE business days after sent**. ★ **Rebuttable presumption** — a documented actual
  receipt (e-sign/e-view timestamp) overrides it. Model `computedReceivedDate` with a basis flag
  `ACTUAL | CONSTRUCTIVE_PLUS_3`.
- **Application = exactly 6 items** — `1026.2(a)(3)(ii)`: name, income, SSN, property address, est.
  property value, loan amount sought. ★ No 7th catch-all (Comment 2(a)(3)-1); creditor cannot defer the clock.
- **Consummation ≠ closing** — `1026.2(a)(13)`: when the consumer becomes contractually obligated,
  **per state law**. ★ Legally distinct from closing/funding (may coincide). Store `consummationDate`
  as its own field; **never hard-code it = closing/funding date**.
- **Tolerance buckets** — `1026.19(e)(3)`: **ZERO** per-item (creditor/broker/affiliate fees,
  non-shoppable third-party services, transfer taxes); **10% AGGREGATE** cumulative (recording fees +
  shoppable services where consumer chose **on the written list** *or did not shop*); **UNLIMITED**
  good-faith (`(iii)(A)-(E)`: prepaid interest, property insurance, escrow deposits, **off-list**
  consumer-selected providers, property taxes/non-required charges). ★ Membership turns on **retention**
  ("paid to"), not who collects (Comment 19(e)(3)(i)-3); the enumerated lists are **non-exhaustive
  commentary** over a residual general rule.
- **Tolerance cure** — `1026.19(e)(3)(v)`: refund excess + corrected CD, both ≤ **60 days after
  consummation** (zero + 10% buckets only). *(v1: detect/aggregate only; execution deferred.)*
- **Revised-LE re-baseline** — `1026.19(e)(3)(iv)(A)-(F)` (6 reasons) + `(e)(4)`: issue ≤ **3 business
  days** after sufficient info (rate-lock variant `(iv)(D)` = ≤3 after lock); **no revised LE on/after
  the CD**; consumer must receive any revised LE ≤ **4 business days before consummation**.
- **CD reset triggers — EXACTLY 3** — `1026.19(f)(2)(ii)`: (A) APR becomes inaccurate per `1026.22`,
  (B) loan **product** changes, (C) **prepayment penalty** added → new 3-business-day CD wait. ★
  Everything else pre-consummation = corrected CD at/before consummation, **no new wait** (`(f)(2)(i)`);
  post-consummation tracks are separate (`(f)(2)(iii)` 30-day, `(iv)` 60-day clerical, `(v)` 60-day refund) — don't conflate.
- **APR accuracy** — `1026.22(a)`: **±0.125 pp regular**, **±0.25 pp irregular**. ★ **CORRECTED:** the
  band is **symmetric** ("above or below"); overstatement relief is the **conditional** `1026.22(a)(5)(ii)`
  (mortgage-secured + finance charge also overstated within its tolerance + closer to actual) — do NOT
  code a blanket one-directional overstatement pass.
- **APR math is iterative** — Appendix J actuarial method, **no closed form**. The `qualification`
  module's closed-form amortization does NOT cover it → **delegated to the vendor port** (decision a).
  Finance Charge (`1026.4`), Amount Financed (`1026.18(b)`), TIP, Total of Payments ($100 tolerance,
  `1026.38(o)(1)`) likewise come from the port; we store them.
- **Coverage gate (5-part)** — `1026.19(e)/(f)`, `1026.3`: real-property/co-op + closed-end + consumer
  + not exempt + not reverse. **OUT:** HELOCs, reverse, chattel/mobile-home-only, business-purpose,
  non-natural-person. ★ **Co-ops are IN regardless of state real/personal-property treatment** (2017
  amendment) — don't gate on a "real property" check. `1026.3(h)` partial exemption = a per-tenant
  toggle (**deferred**). `1026.3(b)` high-dollar exemption never reaches real-property mortgages.
- ★ **UCD is MISMO v3.3.0** (NOT 3.4 — 3.4 is the AUS/ULAD surface), required only at **GSE delivery
  (post-closing)**; v2.0 mandate dates not final until ~Q4 2026 → **version-aware port stub, deferred**.

## Locked decisions
| Area | Decision |
|---|---|
| Module | New **`disclosures`** (`com.msfg.los.disclosures`), deps `:platform :loan-core :fees :coc :documents :qualification`. Migration **V17**. *(Pricing-lock terms read off `Loan` in v1; a pricing-snapshot refinement is deferred.)* |
| Math + forms | **`DisclosureVendorPort`** (stub-first) computes APR/finance-charge/amount-financed/TIP/total-of-payments **and** renders the H-24/H-25 form (bytes). We never hand-render the pixel-regulated forms. |
| Timing engine (OURS) | **`BusinessDayCalculator`** (both `1026.2(a)(6)` defs + 5 USC 6103(a) holiday set w/ observed-date shifting + mailbox +3 basis) — the crown jewel, deterministic, edge-case-tabled like `MortgageMath`. Deadline computation + the 3-trigger reset detector = **advisory + audited** in v1; a **hard consummation gate is designed-in (hooks) but OFF**. |
| Tolerance (OURS) | `TolerancePolicy.bucket(section, paidTo, consumerCanShop, onWrittenList)` → `ZERO\|TEN_PERCENT\|UNLIMITED` (retention-based). Good-faith comparison vs the **last good-faith LE snapshot** (baseline). v1 = bucket tagging + bucketed totals + advisory comparison; **cure execution + 60-day tracker deferred**. |
| Reset detector (OURS) | Compare new APR (from port) vs **last CD's stored APR** against the **symmetric** band (`apr_irregular_basis` picks 0.125/0.25; apply `(a)(5)(ii)` overstatement relief conditionally) **OR** product change **OR** prepay-penalty added → `reset_triggered` + which of the 3. |
| CoC wiring | Map `CocReason` → the 6 `(e)(3)(iv)` triggers; an **ACCEPTED** CoC that changes settlement charges computes the **revised-LE deadline** (3 business days) + audit event. `disclosures` reads `coc` (one-way dep). |
| Consummation | First-class **`consummationDate`** (state-law-driven) — additive nullable column on `loan`; settable via existing `PATCH /api/loans/{id}` (additive field). Never derived from closing/funding. |
| Tolerance facts | Additive **nullable** columns on `fee_line_item`: `paid_to`, `consumer_can_shop`, `on_written_list` (the bucket is **derived**, never stored on the live row — snapshotted into the issuance). Fees create/PATCH accept them (additive). |
| Documents | `DocumentType` += `LOAN_ESTIMATE`, `CLOSING_DISCLOSURE` (additive, before `OTHER`). Rendered forms stored via `DocumentService.storeGenerated`; downloaded via the existing binary content endpoint. |
| Tenancy | All new tables `TenantScopedEntity` + FORCE RLS + org FK + `app_user` grants (V15/V16 pattern); every endpoint `assertCanAccess(loanService.get(loanId))`; `disclosure_event` append-only (SELECT/INSERT grant, mirror `lock_event`). |
| Access | Any loan-accessor may issue/record (back-office roles are org-wide already); PLATFORM_ADMIN excluded; no extra role gate in v1 (a future hard gate adds role checks). |

## Data model — migration **V17** (2 new tables + 2 additive ALTERs)
### `disclosure_issuance` (immutable per version; "current" = latest version per loan+kind)
`org_id`(FK,NN) · `loan_id`(NN) · `kind varchar(20)`(LOAN_ESTIMATE|CLOSING_DISCLOSURE) · `version int`(per
loan+kind, max+1) · `status varchar(20)`(PENDING|SENT|RECEIVED|ERROR) · figures `apr numeric(9,5)`,
`finance_charge numeric(15,2)`, `amount_financed numeric(15,2)`, `total_of_payments numeric(15,2)`,
`tip numeric(9,5)` · `apr_irregular_basis boolean` · `prepayment_penalty boolean` ·
`product_description varchar(120)` · `delivery_method varchar(20)`(IN_PERSON|MAIL|EMAIL|COURIER) ·
`delivered_at timestamptz` · `received_at timestamptz`(nullable) · `received_basis varchar(24)`(ACTUAL|
CONSTRUCTIVE_PLUS_3) · `computed_received_date date` · `earliest_consummation_date date` ·
`document_id uuid` · `vendor_reference varchar(120)` · `snapshot jsonb NOT NULL DEFAULT '{}'` (immutable:
bucketed cost table + 8 cash-to-close rows + terms) · `trigger_coc_id uuid`(nullable) ·
`reset_triggered boolean NOT NULL DEFAULT false` · `reset_reasons jsonb NOT NULL DEFAULT '[]'` ·
`requested_by varchar(120)` · `requested_at timestamptz` · `error_message varchar(1000)` · audit/version
cols. Index `(org_id, loan_id, kind, version)`.
### `disclosure_event` (append-only audit — SELECT/INSERT grant only)
`org_id`(FK,NN) · `loan_id`(NN) · `disclosure_id uuid`(nullable) · `event_type varchar(40)`
(LE_ISSUED|CD_ISSUED|REVISED_LE_ISSUED|RECEIPT_RECORDED|RESET_TRIGGERED|REVISED_LE_DEADLINE_SET|
TOLERANCE_CHECK|COVERAGE_EVALUATED) · `detail jsonb NOT NULL DEFAULT '{}'` · `actor varchar(120)` ·
`occurred_at timestamptz` · audit cols. Index `(org_id, loan_id, occurred_at)`.
### Additive ALTERs (no renames)
`ALTER TABLE fee_line_item ADD paid_to varchar(40), ADD consumer_can_shop boolean, ADD on_written_list boolean;` ·
`ALTER TABLE loan ADD consummation_date date;`

## Ports (real-adapter wire contracts in javadoc; deterministic stub the only `@Component`)
- **`DisclosureVendorPort`**:
  - `DisclosureGenerationResult generate(DisclosureGenerationRequest)` → `{ apr, financeCharge,
    amountFinanced, totalOfPayments, tip, aprIrregularBasis, renderedDocument(bytes,contentType),
    vendorReference }`. Real adapter: DocMagic/IDS/Docutech "generate disclosure package" (MISMO 3.4
    loan data in, computed figures + H-24/H-25 PDF out; creditor remains liable).
  - `DeliveryResult send(DeliveryRequest)` → `{ vendorReference, sentAt, deliveryMethod }`. Real adapter:
    ESIGN `15 USC 7001(c)` consent must exist + be evidenced **before** e-delivery; UETA §9/§12.
  - `DeliveryStatus getStatus(vendorReference)` → `{ status, actualReceiptAt? }` — the e-view/e-sign
    timestamp that flips basis to ACTUAL.
  - version-aware `UcdExportResult exportUcd(...)` / `submitUcd(...)` — **MISMO v3.3.0** UCD for GSE
    delivery; **stub only, deferred**.
- **Stub** `StubDisclosureVendorAdapter`: deterministic per loanId+kind+version. APR = note rate +
  a deterministic finance-charge-derived bump (so reset-trigger tests can construct over-tolerance
  scenarios); figures internally consistent; renders a **placeholder** templated HTML form (escaped via
  `HtmlText`) stored as `LOAN_ESTIMATE`/`CLOSING_DISCLOSURE` — deliberately NOT a conforming H-24/H-25
  (the real adapter returns the regulated PDF; the stub only exercises the storage + download seam).
  `send` returns CONSTRUCTIVE_PLUS_3; `getStatus` deterministic. Honest stub javadoc: real APR is
  Appendix-J actuarial behind the real adapter.

## API (all `ApiResponse`; cross-org 404; no token 401; `{loanId}` scoped)
- `GET /api/loans/{loanId}/disclosures/coverage` → `{ covered, reasons[] }` (5-part gate; FE shows/hides the UI).
- `POST /api/loans/{loanId}/disclosures/loan-estimate` → 201 `DisclosureResponse` — gather cost table
  (fees) + terms → `port.generate` → store form → persist issuance (version max+1) → compute deadlines
  (LE 3-day + 7-day) → events. Optional body `{ triggerCocId?, deliveryMethod }` (revised LE when a prior LE exists).
- `POST /api/loans/{loanId}/disclosures/closing-disclosure` → 201 — same + **reset detection** vs the last CD.
- `GET /api/loans/{loanId}/disclosures` → history both kinds, newest-first.
- `GET /api/loans/{loanId}/disclosures/{disclosureId}` → figures + snapshot + deadlines + basis + doc id + reset reasons.
- `POST /api/loans/{loanId}/disclosures/{disclosureId}/receipt` `{ receivedAt }` → flips basis ACTUAL,
  recomputes `computed_received_date` + downstream earliest-consummation; audit event.
- `GET /api/loans/{loanId}/disclosures/timing` → computed deadlines + status (LE deadline met?, earliest
  consummation date, CD reset state, any revised-LE clock from an accepted CoC) — **advisory**.
- `GET /api/loans/{loanId}/disclosures/tolerance` → bucketed totals (zero per-item list, 10% cumulative
  sum, unlimited) + good-faith comparison vs the baseline LE — **advisory**.

## Testing (crown jewels independent-valued; opus pass on math + timing + reset)
- **`BusinessDayCalculatorTest`** — MortgageMath-grade table vs CFPB worked examples: PRECISE counts
  Saturdays + excludes Sundays/holidays w/ observed-date shifts (incl. Juneteenth, year boundaries);
  GENERAL uses the tenant config; `addBusinessDays`/`between`; mailbox +3; the LE-3, LE-7, CD-3 counts.
  **Pin the verified nuance**: LE-7 keyed to delivery (not receipt, no +3 stack); CD-3 keyed to receipt.
- **LE issuance E2E** — seed loan + bucketed fees → POST loan-estimate → 201, APR from stub, form stored
  + downloadable via documents content endpoint, version 1, deadlines computed; JDBC cross-checks.
- **CD reset E2E (crown jewel)** — CD v1; then (i) APR pushed past the symmetric band, (ii) product
  change, (iii) prepay-penalty added → each issues CD v2 with `reset_triggered` + correct reason; a
  within-tolerance/overstatement-relief change → **no** reset. Independent recompute of the band.
- **Tolerance bucketing** — fees across buckets → GET tolerance: zero per-item, 10% cumulative correct;
  a fee flipped off-list reclassifies to unlimited; baseline comparison flags an over-tolerance increase.
- **Receipt basis flip** — POST receipt → ACTUAL, received date + earliest-consummation recomputed.
- **Coverage gate** — HELOC/reverse/business → not covered (reasons); **co-op → covered**; purchase → covered.
- **CoC → revised-LE clock** — accept a settlement-charge CoC → revised-LE deadline = +3 business days + event.
- Cross-org 404; PLATFORM_ADMIN 403; PROCESSOR org-wide 200; **RLS IT** (both new tables);
  `OpenApiDocsIT` + dup-simple-name sweep; port stub determinism units.

## Out of scope / deferred (own seam, no v1 work beyond the stub)
Real DocMagic/IDS/Docutech adapter + real e-sign/e-consent capture · real UCD XML / GSE submission
(version-aware stub only) · **hard consummation-blocking gate** (hooks present, enforcement off) ·
tolerance-cure **execution** + 60-day refund tracker (bucketing/comparison only) · **ARM AP/AIR tables**
(`37(i)/(j)`,`38(m)/(n)`) — **fixed-rate only v1** (`1026.37(j)` forbids a blank AIR table, so fixed-only
is clean; pricing has no index/margin/caps yet) · in-house Appendix-J solver (delegated; revisit if the
vendor is dropped) · `1026.3(h)` partial-exemption toggle · construction one-vs-two-transaction handling ·
SMART Doc V3 / eNote · pricing-lock terms snapshot (read `Loan` in v1).

**Implementation plan:** `docs/superpowers/plans/2026-06-14-disclosures-module.md` (next).
