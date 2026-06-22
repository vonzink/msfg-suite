# mortgage-app Transition Page (funnel → authenticated, prefilled application) — design

**Date:** 2026-06-22
**Status:** ✅ Approved (owner)
**Parent:** `docs/superpowers/specs/2026-06-18-unified-integration-architecture-design.md` (Phase A) ·
builds on the local-e2e funnel harness (`2026-06-22-local-e2e-funnel-harness-design.md`) + Phase F identity.

## Goal
Rework the first page a borrower lands on in mortgage-app when arriving from the msfg.us apply funnel, so it
flows better: greet them, show the data we already captured (reassurance + proof nothing is lost), let them
verify identity **passwordless (email one-time code)** — creating the account silently if new — and drop them
into a **prefilled** application.

## Why now
Today: msfg.us "finish" → `Continue in the MSFG app` → mortgage-app `LandingPage` (`/`), a generic hero whose
"Sign in / Create account" buttons **redirect out to Cognito Hosted UI**, then bounce to `/applications` (a
blank list). Nothing is prefilled; auth is a full redirect, not inline. By the finish step the funnel already
holds most of a 1003 (loan purpose, price, down payment, credit band, income, property use/type, officer).

## Locked decisions
- **D-T1 — Approach 1 (single welcoming screen):** one page = greeting + carried-over summary + inline
  email-OTP; verify → straight into the prefilled application. (Chosen over verify-then-review and
  auth-light-straight-to-form.)
- **D-T2 — Passwordless email one-time code** (not password, not magic link): code entered on-page keeps the
  borrower in the flow on the same device/tab. Account created silently on first verify.
- **D-T3 — Dedicated route `/continue`** (the funnel deep-link target). The generic `/` `LandingPage` stays
  for direct/returning visitors. `/continue` is **public** (not wrapped in `RequireAuth`) — it performs its
  own auth.
- **D-T4 — Loan creation deferred to post-auth at `/continue`** (revised 2026-06-22 after reading the code).
  msfg.us's hand-off **no longer creates the loan**; it mints a token carrying the funnel data. After the
  borrower verifies (OTP) on `/continue`, the page calls the **existing** suite `POST /api/loans/intake`
  *as the now-authenticated borrower* — the loan is created and linked to them by the intake path already
  built and tested (A4 `linkById` to the caller's sub). This is the D7 "loan born at first authenticated
  apply" model and needs **zero suite changes** (no unclaimed-borrower, no claim-on-`/me`, no dev-bridge
  email field).
- **D-T5 — Signed hand-off token carries a NON-SENSITIVE subset.** The token holds loan purpose, borrower
  name/email/phone, property (address/use/type), and display facts (purchase price, down-payment %) — enough
  to create the loan and show a "we've got your basics" summary. It **excludes income, credit band, and any
  SSN** — those never ride in the URL; they're confirmed/collected in the application form. `/continue`
  renders the summary from it pre-auth and POSTs the create-relevant fields to suite intake post-auth. Signed
  with `jose` HS256 + `HANDOFF_TOKEN_SECRET` (the signature is integrity, not confidentiality — hence no PII);
  locally an unsigned payload is accepted.
- **D-T6 — Local-first:** everything runs behind dev adapters now (the page, a dev passwordless adapter that
  auto-verifies as the dev borrower, the existing dev-header bridge). The **real Cognito email-OTP adapter +
  production token signing + Cognito passwordless enablement are a deferred, owner-gated slice.**

## The flow (end state)
1. **msfg.us finish** → `/api/v1/applications` mints a short-TTL **signed hand-off token** carrying the
   `funnelToIntake` DTO (`{ sourceLeadId, loanPurpose, borrower, property, financials, loanOfficer }`) →
   returns it → FinishStep redirects the browser to `app.msfgco.com/continue?t=<token>`. **No loan created here.**
2. **`/continue`** (mortgage-app FE):
   - Verify/decode the token; render Approach-1 UI: greeting (name), carried-over summary (from the token),
     email (prefilled), email-OTP panel.
   - `requestCode(email)` → email a 6-digit code. State → enter-code.
   - `verifyCode(email, code)` → on success the borrower is authenticated (account created if new).
   - POST the token's intake DTO to suite **`POST /api/loans/intake`** as the authenticated borrower →
     loan created + linked to them (existing A4 path; idempotent on `sourceLeadId`).
   - Navigate into the **prefilled** application, seeded from the token's DTO (+ the just-created suite loan).

## Components / seams (each isolated, testable)

### 1. `ContinuePage` (mortgage-app FE, route `/continue`)
- A small state machine: `email → code → verified`. Renders the Approach-1 layout (greeting + summary card +
  auth panel; Continue gated until verified). Reads the hand-off token for `email` + `summary`.
- Files: `frontend/src/pages/ContinuePage.js` (+ `ContinuePage.design.css`), route added in
  `frontend/src/App.js` as a public route (NOT inside `RequireAuth`).
- Tests: RTL/jest state-machine test (email → request → code → verify → navigate), mocking the auth port +
  the token decode.

### 2. `PasswordlessAuthPort` (FE auth seam)
- Interface: `requestCode(email): Promise<{ sent: boolean }>` and
  `verifyCode(email, code): Promise<{ ok: boolean; reason?: string }>`. On success the adapter establishes the
  authenticated session the rest of the app reads.
- **Local dev adapter** (`REACT_APP_DEV_SUB` set): `requestCode` is a no-op success; `verifyCode` accepts any
  code (or a fixed dev code) and resolves authenticated as the dev borrower. The app already treats the dev
  borrower as signed-in via the B4 `RequireAuth` bypass + the apiClient `X-Dev-*` headers, so no real token is
  minted locally.
- **Cognito email-OTP adapter** (deferred, owner-gated): `requestCode` → Cognito custom-auth `InitiateAuth`
  (CUSTOM_AUTH) / managed email-OTP → `CreateAuthChallenge` emails the code; `verifyCode` →
  `RespondToAuthChallenge` → tokens stored where `react-oidc-context`/apiClient read them. Documented as a
  contract on the port; implemented when the owner enables Cognito passwordless.
- Files: `frontend/src/auth/passwordless/PasswordlessAuthPort.js` (the interface + adapter selection by env),
  `…/DevPasswordlessAdapter.js`, `…/CognitoOtpAdapter.js` (stub with the real contract). Tests: dev adapter
  unit test.

### 3. Hand-off token (msfg.us → mortgage-app)
- msfg.us mints a short-TTL (≈10 min) signed JWT whose payload is a **non-sensitive `HandoffPayload`**:
  `{ sourceLeadId, loanPurpose, borrower{firstName,lastName,email,phone}, property{addressLine,city,state,
  zipCode,propertyUse,propertyType,propertyValue}, display{purchasePrice,downPaymentPercent}, loanOfficer }`
  — built from the lead's funnel fields (the same source as `funnelToIntake`), **omitting `financials`
  (income/credit band) and SSN**. This object renders the summary AND supplies the create-relevant fields
  `/continue` posts to suite intake. Signed with `jose` `SignJWT` HS256 + `HANDOFF_TOKEN_SECRET` (env, already
  a dep — mirrors `src/lib/auth/cognito.ts`).
- mortgage-app verifies/decodes the token on `/continue`. **Locally** (dev flag) an unsigned base64 payload
  is accepted so the flow runs without shared-secret setup.
- Files: msfg.us `src/server/integrations/los/handoffToken.ts` (mint); `/api/v1/applications/route.ts`
  returns `{ handoffToken }` (both the dev-bypass and real branches; it no longer needs to create the loan —
  it just computes `funnelToIntake(...)` as today and signs it). mortgage-app
  `frontend/src/auth/handoffToken.js` (verify/decode). Tests: msfg.us mint/verify unit test (vitest);
  mortgage-app decode unit test.
- Replaces today's behavior where the route created the loan and returned `applicationId`; FinishStep now
  redirects to `${APP_URL}/continue?t=<handoffToken>`.

### 4. Prefill into the application
- On `verified`, `ContinuePage` navigates to the application route passing the summary as initial values
  (React Router state), and the application also loads the claimed suite loan as the source of truth.
- `ApplicationForm` already supports initial values (`defaultValues` + its edit-load mapping). Map the funnel
  summary onto the fields the form already has: `loanPurpose`, `propertyValue` (from purchasePrice),
  down-payment, borrower `firstName`/`lastName`/`email`, `annualIncome`/income source, credit band,
  property use → occupancy, property type → construction. First cut covers exactly those.
- Files: `frontend/src/pages/ContinuePage.js` (navigation + state), `frontend/src/components/forms/
  ApplicationForm.js` (accept seeded initial values from route state). Tests: a mapping unit test
  (summary → form initial values).

### 5. Post-auth intake call (NO suite change)
- On `verified`, `/continue` POSTs the token's intake DTO to the **existing** suite `POST /api/loans/intake`
  as the authenticated borrower. That path (A4) creates the loan + links the borrower to the caller's `sub`
  (`linkById`) and is idempotent on `sourceLeadId`. **No suite code changes** — endpoint, link, and
  idempotency already exist.
- Locally: the borrower is the dev identity; the apiClient already sends `X-Dev-*`, so intake links the loan
  to the dev borrower's sub (exactly as in the local walk we ran). No email-match / dev-bridge changes.
- The DTO field names must map to suite's `IntakeRequest` (`sourceLeadId`, `loanPurpose` enum, nested
  `borrower{firstName,lastName,email,phone}`, `property{addressLine1,city,state,postalCode,estimatedValue}`)
  — `/continue` adapts the msfg.us DTO (which uses `addressLine`/`zipCode`/`propertyValue` + `loanPurpose`
  "Purchase") to that shape, the same mapping `SuiteClient` already does.
- Files: mortgage-app `frontend/src/services/mortgageService.js` (add `createLoanFromIntake(dto)` →
  `POST /api/loans/intake`), called from `ContinuePage` post-verify. Tests: unit test for the new service fn
  (mocked apiClient) + the DTO→IntakeRequest mapping.

## Local-first vs deferred
- **Buildable + viewable now (dev adapters):** the `/continue` page, the dev passwordless adapter
  (auto-verify → dev borrower), the unsigned local hand-off payload, the post-auth call to the existing
  suite intake (links the loan to the dev borrower), and the prefilled application — all exercisable in the
  running stack.
- **Deferred, owner-gated (own follow-up):** the Cognito email-OTP adapter + enabling Cognito passwordless
  (custom-auth Lambdas / managed OTP); production HMAC secret for the hand-off token.

## Scope
- **In (all FE + msfg.us — zero suite changes):** `/continue` page (Approach 1) · `PasswordlessAuthPort` +
  dev adapter (+ Cognito adapter contract, stubbed) · hand-off token mint (msfg.us) / verify (mortgage-app) ·
  post-auth call to the existing suite intake · prefill seam into the existing form for the funnel fields.
- **Out (follow-ups):** real Cognito passwordless enablement (AWS) · exhaustive field-by-field 1003 prefill
  beyond the funnel fields · re-pointing the application *writes* to suite · the existing
  doc-workspace/app-settings 500s (separate chip `task_2fd57691`).

## Testing
- FE (jest/RTL): `ContinuePage` state machine (email→code→verify→navigate, port mocked); the
  msfg.us-DTO→`IntakeRequest` mapping + the DTO→form-initial-values mapping; dev passwordless adapter;
  hand-off token decode.
- msfg.us (vitest): hand-off token mint/verify; `/api/v1/applications` returns `{ handoffToken }`; FinishStep
  redirect builds `/continue?t=…`.
- **suite: none** (the existing intake endpoint is reused unchanged).
- E2E: the running-stack walk (msfg.us wizard → `/continue` → dev-verify → loan created in suite → prefilled
  app), per `docs/local-e2e.md`.

## Open questions / follow-ups
- Exact Cognito passwordless mechanism (managed email-OTP vs. custom-auth Lambda triggers) — settle when the
  owner enables it; the port contract is written to accommodate either.
- Whether to also surface a "not you? / use a different email" affordance on `/continue` (the token carries one
  email). Minor; default to showing the prefilled email editable.
- Hand-off token signing key management (shared HMAC secret vs. msfg.us JWKS) — HMAC for v1.
