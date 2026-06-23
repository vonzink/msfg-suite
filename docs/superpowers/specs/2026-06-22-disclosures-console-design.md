# TRID Disclosures (LE/CD) in the staff console (msfg-suite-web) — design

**Date:** 2026-06-22
**Status:** ✅ Approved (owner)
**Parent:** unified-integration-architecture (Phase A — fill staff-console gaps against the frozen contract)

## Goal
Surface the **TRID disclosures** workflow in the staff console (`msfg-suite-web`): issue Loan Estimates and
Closing Disclosures, view the disclosure history with TILA figures, record borrower receipt, and read the
loan's TRID **timing** and good-faith **tolerance** posture — wiring the **existing** suite disclosures API.
This is the next processing gap after Conditions. The suite `disclosures` module (V17) is complete, so this
slice is **FE-only — zero suite changes.**

## Context
The loan workspace has a disabled **"Closing Disclosure"** sidebar slot (in `MENU_FUTURE`) and a disabled
**"Preview CD"** button on `FeesPage`, both waiting. The suite `disclosures` module exposes a full
issue/receipt/timing/tolerance/history API; nothing surfaces it yet. The console feature convention is
established (see `ContactsPage` / the just-built `ConditionsPage`): a feature folder with the page, an
`*Api.ts` (generated openapi-fetch client wrappers + React-Query hooks), co-located dialogs, and `.test.tsx`.
Mirror it.

## Backend contract (verified — do not change)
All paths under `/api/loans/{loanId}/disclosures`, responses wrapped in `ApiResponse<…>`:

- `POST   …/loan-estimate`      (body `IssueDisclosureRequest`, optional) → `ApiResponse<DisclosureResponse>` (201).
- `POST   …/closing-disclosure` (body `IssueDisclosureRequest`, optional) → `ApiResponse<DisclosureResponse>` (201).
- `POST   …/{disclosureId}/receipt` (body `RecordReceiptRequest`)         → `ApiResponse<DisclosureResponse>`.
- `GET    …/timing`    → `ApiResponse<TimingResponse>`.
- `GET    …/tolerance` → `ApiResponse<ToleranceResponse>`.
- `GET    …`           → `ApiResponse<DisclosureResponse[]>` (full history, all versions, both kinds).
- `GET    …/{disclosureId}` → `ApiResponse<DisclosureResponse>`.

**DTOs / enums (exact field names confirmed via the generated client at build time — `npm run gen:api`):**
- `IssueDisclosureRequest = { deliveryMethod?, triggerCocId?, prepaymentPenalty? }` — all optional;
  `deliveryMethod` defaults to `EMAIL` server-side when absent. `triggerCocId` links a revised disclosure to a
  Change-of-Circumstance (out of scope here — see below). `prepaymentPenalty` (CD only) drives the
  `PREPAYMENT_PENALTY_ADDED` reset trigger.
- `RecordReceiptRequest = { receivedAt: LocalDate }` (ISO `YYYY-MM-DD`).
- `DisclosureResponse = { id, kind, version, status, apr, financeCharge, amountFinanced, totalOfPayments, tip,
  deliveryMethod, deliveredAt, receivedBasis, computedReceivedDate, earliestConsummationDate, documentId,
  resetTriggered, resetReasons[], requestedBy, requestedAt }`.
- `TimingResponse = { latestLeEarliestConsummation, latestCdEarliestConsummation, overallEarliestConsummation,
  consummationDate, consummationSatisfiesTiming, revisedLeDeadline, leDeliveryDeadline, leDeliveredOnTime }`
  (dates are `LocalDate` or null; the three `*Satisfies/*OnTime` are nullable booleans).
- `ToleranceResponse = { bucketTotals: Map<string,BigDecimal>, comparisonVsBaselineLe: ToleranceComparison | null }`.
  `bucketTotals` is keyed by `ToleranceBucket` name (`ZERO`, `TEN_PERCENT`, `UNLIMITED`).
  `ToleranceComparison = { zeroBucketExcess, tenPercentBaselineSum, tenPercentCurrentSum, tenPercentExcess,
  withinTolerance }`. `comparisonVsBaselineLe` is **null until an LE has been issued** (the baseline snapshot).
- Enums: `DisclosureKind = LOAN_ESTIMATE | CLOSING_DISCLOSURE`; `DisclosureStatus = PENDING | SENT | RECEIVED |
  ERROR`; `DeliveryMethod = IN_PERSON | MAIL | EMAIL | COURIER`; `ReceivedBasis = ACTUAL | CONSTRUCTIVE_PLUS_3`;
  `ResetReason = APR_INACCURATE | PRODUCT_CHANGED | PREPAYMENT_PENALTY_ADDED`.

## Components (msfg-suite-web — follow the existing feature convention)
- **Route + sidebar:** enable the disabled **"Closing Disclosure"** item (relabel the nav link to
  **"Disclosures"**, remove it from `MENU_FUTURE`) → route `loans/:loanId/disclosures` rendering
  `DisclosuresPage` inside `LoanWorkspace`'s outlet (`src/router.tsx` route table + `LoanSidebar.tsx`).
- **`disclosuresApi.ts`** — typed wrappers over the generated client + React-Query hooks:
  `useDisclosures(loanId)` (history list), `useTiming(loanId)`, `useTolerance(loanId)`,
  `useIssueLoanEstimate(loanId)`, `useIssueClosingDisclosure(loanId)`, `useRecordReceipt(loanId)`. Mutations
  invalidate all three queries (`['disclosures', loanId]`, `['disclosures-timing', loanId]`,
  `['disclosures-tolerance', loanId]`) on success. Mirror the existing `*Api.ts` shape
  (`getApiClient` / `handle` / `@/lib/api/envelope`). Export `KIND_LABELS`, `STATUS_LABELS`,
  `DELIVERY_LABELS`, `BUCKET_LABELS`, `RESET_REASON_LABELS`, and `disclosureKeys`.
- **`DisclosuresPage.tsx`** — three regions:
  1. **Action bar:** `Issue Loan Estimate` + `Issue Closing Disclosure` buttons → `IssueDisclosureDialog`.
  2. **Compliance summary:** two read-only cards. **Timing** card from `useTiming`: overall earliest
     consummation, consummation date + a satisfies/at-risk indicator (`consummationSatisfiesTiming`), LE
     delivery deadline + on-time indicator (`leDeliveredOnTime`), revised-LE deadline (when present).
     **Tolerance** card from `useTolerance`: per-bucket totals (ZERO / TEN_PERCENT / UNLIMITED) and, when
     `comparisonVsBaselineLe` is present, a within-tolerance / over-tolerance indicator with the zero-bucket
     and 10%-bucket excess; an explicit **"No Loan Estimate issued yet"** empty state when the comparison
     is null.
  3. **History table:** every issued version — kind, version, status, `apr` + TILA figures (`financeCharge`,
     `amountFinanced`, `totalOfPayments`, `tip`), delivery method, `deliveredAt`, `computedReceivedDate`, and a
     **reset** badge when `resetTriggered` (tooltip lists `resetReasons`). Empty state when no disclosures.
- **`IssueDisclosureDialog.tsx`** — a confirm dialog (issuing starts a TRID clock, so it is not a bare click):
  a `deliveryMethod` select (default `EMAIL`); for the Closing-Disclosure variant, an additional
  **"prepayment penalty added"** checkbox (sets `prepaymentPenalty`). On submit → the matching issue hook.
  Per-field local `useState` (matches the Contacts/Conditions convention — no RHF/Zod).
- **Row action — Record receipt:** for a row whose status is `PENDING`/`SENT`, a **Record receipt** action opens
  a small date dialog (`receivedAt`, default today) → `useRecordReceipt`; the row flips to `RECEIVED`.
- **Row action — View document:** when `documentId` is present, a **View document** link to the existing
  documents content endpoint (`GET /api/loans/{loanId}/documents/{documentId}/content`) for the generated
  H-24/H-25 rendering. Use a plain link/anchor against the configured API base — do **not** depend on the
  `documents` feature helpers (they have a pre-existing build-error chip).

## Data flow
`DisclosuresPage` → `useDisclosures` / `useTiming` / `useTolerance` (generated client GETs) → render. Issue and
receipt mutations call the generated client (POST) → on success invalidate the three query keys → React-Query
refetches. Sonner toasts on success/error (error surfaces the envelope `message`). Auth/token + the `/api`
proxy are the console's existing mechanisms (no change).

## Scope
- **In:** the workspace Disclosures page — issue LE, issue CD (with delivery method + prepayment-penalty),
  history table with TILA figures + reset badges, record receipt, read-only timing + tolerance compliance
  cards, view-generated-document link; enable the FeesPage **"Preview CD"** button to deep-link here. FE-only
  in msfg-suite-web, against the existing API. Zero suite changes.
- **Out (separate follow-ups):** linking `triggerCocId` from the Change-of-Circumstance screen into a revised
  disclosure (needs CoC↔disclosure UX); a pipeline-level disclosure-status badge (the suite
  `LoanListItemResponse` carries no disclosure field — needs a backend DTO addition, conductor-owned); wiring
  FeesPage's other disabled compliance buttons ("Review Tolerances", "Run QM Test", "Edit Payees").

## Testing
vitest + React Testing Library + MSW, co-located (`disclosures.test.tsx`), mocking the generated client /
React-Query per the console's existing test setup:
- History renders rows with kind/version/status/TILA figures; reset badge shows when `resetTriggered`.
- Issue Loan Estimate → dialog → POST `…/loan-estimate` called → history refetched.
- Issue Closing Disclosure with prepayment-penalty checked → POST body carries `prepaymentPenalty: true`.
- Record receipt → POST `…/{id}/receipt` with `receivedAt` → row status flips to `RECEIVED`.
- Timing card renders deadline + satisfies/on-time indicators.
- Tolerance card renders bucket totals AND the **null-baseline** empty state ("No Loan Estimate issued yet").

`npm run build` + `npm run lint` clean.

## Open questions / confirm-at-build
- Exact generated field names + operationIds for the disclosures paths — read from `npm run gen:api` output
  before writing the API wrappers. If the running suite's `/v3/api-docs` does not yet include the disclosures
  paths, run `gen:api` against the running suite first (as was needed for Conditions).
- The shape of `bucketTotals` in the generated client (a `Record<string, number>` / `{ [k]: number }`) — render
  defensively over whatever keys are present (ZERO / TEN_PERCENT / UNLIMITED), not a hard-coded triple.
- Money/number formatting: reuse the console's existing currency/number formatter used by FeesPage/Pricing.
