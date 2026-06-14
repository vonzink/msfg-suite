# Backend Progress Report — for the Frontend Team

**Date:** 2026-06-14 · **Backend `main`:** `c568c51` (code) · **Tests:** 400 green · **Migrations:** through V16 · **16 Gradle modules**

> **TL;DR — your entire work-order is shipped and live.** The full 1003 (URLA) plus every
> processing module you requested (§1–§6) is merged, on a running backend at
> `http://localhost:8080`. Run `npm run gen:api` once against `/v3/api-docs` and every stub adapter
> in your app can be swapped for the real one. **Disclosures (TRID LE/CD) is the next backend
> milestone — in design now, no endpoints yet** (don't expect a `/disclosures` surface until it lands).

---

## Work-order scoreboard — COMPLETE ✅

| Your work-order item | Status | Your stub to swap |
|---|---|---|
| §1 Fees (line items + totals + invoices) | ✅ Live (+ `PUT` upsert path) | `FeesProvider` |
| §2 Change of Circumstance (draft/submit/history/decision) | ✅ Live | `CocProvider` |
| §3 Contract-nits (pipeline enrich, summary fields, optional `loanOfficerId`, transitions) | ✅ Live | n/a (already typed) |
| §4 Document Manager + Pre-Approval letter | ✅ Live | doc/pre-approval stubs |
| §4 Pricing / Rate Lock | ✅ Live | static pricing stub |
| §4 AUS + Credit Vendors (DU/LPA runs, credit ordering) | ✅ Live | `LocalAusAdapter` |
| §5 Enum/validation → 400 (not 500) | ✅ Live | n/a |
| §6 Contacts (loan people roster) | ✅ Live | `LocalContactsAdapter` |

Plus the **full 1003 (URLA)** — Personal Info & PII (audited SSN reveal), Employment & Income, Assets
& Liabilities, REO, Loan Information, Qualification calc, Declarations & HMDA — all live since earlier
and unchanged. Everything is **additive-only**: no shipped path or field has ever been renamed or removed.

---

## Live endpoint surface (verified against the running server today)

All under `/api`, tenant- + loan-scoped (cross-org → 404, no token → 401), `ApiResponse` envelope. Exact
schemas: `/v3/api-docs`. Field-level detail per module: the dated sections in
`docs/HANDOFF-FROM-BACKEND.md` (in your repo).

- **Loans / pipeline** — `POST|GET|PATCH /api/loans[/{id}]` (optional `loanOfficerId`; enriched pipeline
  rows; summary carries lien/amortization/address/estimatedValue); `GET …/status/transitions` (role-aware).
- **Borrowers & PII** — `…/borrowers[/{id}]`, masked SSN + audited `…/reveal-ssn`; address history.
- **Employment & Income** — `…/employments`, `…/income[/{id}]`, `…/income/summary`, VOI verifications.
- **Assets & Liabilities** — `…/assets`, `…/liabilities` (DTI include/exclude), summaries, VOA.
- **REO** — `…/reo[/{id}]` + `…/reo/summary`.
- **Qualification** (read-only) — `GET …/calculations` (LTV/CLTV/TLTV, P&I, PITI, net rental, DTI; any
  figure may be `null` when inputs are missing — never a 500).
- **Declarations & HMDA** — `GET|PUT …/declarations`, `…/demographics`.
- **Fees** — `PUT …/fees` (upsert by section+label, recommended) · id-based `POST|GET|PATCH|DELETE`
  · `GET …/fees/totals` · `GET|PUT …/fees/invoices`. Negative amounts allowed (credits); 409 on conflict.
- **Change of Circumstance** — `GET|PUT …/coc/draft` · `POST …/coc/submit` (reason required → 400
  `fields.reason`) · `GET …/coc/history` · `POST …/coc/history/{id}/decision` (UNDERWRITER/ADMIN → else 403).
- **Document Manager** — multipart `POST …/documents` · paged `GET …/documents?type=` · **binary**
  `GET …/documents/{id}/content` (the one NON-enveloped endpoint — fetch as blob) · `DELETE` ·
  `POST …/documents/pre-approval`.
- **Pricing / Rate Lock** — `GET …/pricing` (always 200) · `GET …/pricing/adjustments` (at-lock
  snapshot) · `GET …/pricing/lock/history` · `POST …/pricing/lock/{control-your-price|extend|rate-change|relock}`
  (409 `LOCK_STATE_CONFLICT` on wrong state) · `POST …/pricing/lock-confirmation`.
- **AUS + Credit** — org creds (ADMIN) `GET|PUT /api/org/vendor-credentials[/{vendor}]` (write-only,
  masked) · loan overrides `…/aus/credentials` · `GET|PUT …/aus/profile` (with `credentialSource`) ·
  `POST …/aus/run {DU|LPA|ONE_CLICK}` · `GET …/aus/history` · `POST …/credit/order` · `GET …/credit/orders`.
- **Contacts** — `POST|GET|PATCH|DELETE …/contacts[/{id}]` (`role` enum ×8, name, company, phone, email).
- **Admin** — `/api/admin/**` (PLATFORM_ADMIN only).

## Contract invariants (unchanged, guaranteed)
- `{ success, data }` everywhere except the binary document download; paged `{items, page, size, total, totalPages}`.
- Flat errors `{ success:false, code, message, fields, timestamp }` with `400/401/403/404` semantics
  (404 = not found **or** cross-tenant — existence never leaks across orgs).
- **Additive-only**, and that holds for everything still coming.
- `/v3/api-docs` is CI-guarded — client generation never hits a broken spec.

## Recent hardening (no action needed — behavioral only, zero contract change)
- **Malformed enum / bad JSON body → 400** (`VALIDATION_ERROR`), not 500 — your typed client renders these cleanly now.
- **Role access** — back-office roles (PROCESSOR/UNDERWRITER/CLOSER) have **org-wide** loan visibility;
  LOs are scoped to their own loans; PLATFORM_ADMIN has no loan access. (Matters under real Cognito; in
  `local` you're the dev ADMIN and see everything.)
- **Security** — generated documents HTML-escape user input + send `nosniff`; the `cognito:groups` claim
  is allowlisted against known roles. No new endpoints, no `gen:api` needed for these.

## Resolved caveats from the last report
- The "underwriter gets 403 on CoC decisions under real auth" caveat — **fixed** (org-wide access model).
- Malformed-enum → 500 — **fixed** (now 400).

## What's next on the backend
**Disclosures (TRID — Loan Estimate + Closing Disclosure)** is the next milestone, in design now (spec
written, not yet built). When it lands it will add a `/api/loans/{loanId}/disclosures/**` surface
(issue LE/CD, timing/deadline status, tolerance buckets, coverage check) plus `LOAN_ESTIMATE` /
`CLOSING_DISCLOSURE` document types — all additive. The regulated forms + APR are computed behind a
vendor port (stub-first), same pattern as AUS. **There is nothing for you to build against here yet** —
I'll append a dated section to `docs/HANDOFF-FROM-BACKEND.md` with the endpoint inventory when it ships,
as always. After Disclosures: real vendor adapters (DU/LPA/credit/disclosure-vendor onboarding) and the AI milestone.

Anything you need that isn't here → file it in `docs/HANDOFF-BACKEND-REQUESTS.md` and I'll pick it up.
