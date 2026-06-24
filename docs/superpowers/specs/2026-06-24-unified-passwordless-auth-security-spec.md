# Unified Passwordless Auth — Security Specification

**Status:** DRAFT — owner review required before any build (esp. prod-mutating Cognito/SNS/IAM steps).
**Date:** 2026-06-24 · **Pool:** `us-west-1_S6iE2uego` (ESSENTIALS, `us-west-1`, acct `116981808374`).
**Owner decisions baked in:** unified passwordless for ALL personas (email/SMS OTP + passkey first-factor choice); MFA optional; staff can both log in via OTP AND trigger a verification code to a borrower (phone/email); build it all now as one coordinated spec.
**Authoritative contract.** All `file:line` citations are against the current tree. Every prod-mutating step is marked **[OWNER-GATED PROD MUTATION]**.

---

## 1. Goal & scope

Ship one **unified passwordless authentication model** across all four MSFG systems sharing the single Cognito pool: borrowers (mortgage-app funnel/portal), agents, and staff (LO/Processor/Underwriter/Closer/Manager/Admin) on the suite console. Every persona signs in by **choosing a first factor — Email OTP, SMS OTP, or passkey (WebAuthn)** — with **no passwords required** in the steady state. MFA is **optional and reconciled as passkey-as-MFA** (see §2). Staff additionally get a **suite-owned "send a verification code to this borrower"** action (email now, SMS when the SMS rails are live). The deliverable spans four edit surfaces: (a) Cognito pool + app-client config, (b) the two `org_id`/role Lambdas, (c) the mortgage-app FE (real `USER_AUTH` SDK adapter), (d) the suite backend (reliable role assignment, the staff-initiated verification endpoint, hardening). The single hardest non-feature blocker — **the G8 role-assignment gap** (passwordless auto-confirm leaves `cognito:groups=null` → suite 403) — is fixed at both the Lambda source and the suite backstop. SMS is **designed in but ops-gated** behind SNS sandbox exit + 10DLC; email-OTP + passkey are the day-one factors.

---

## 2. Target auth model

### 2.1 The one constraint that shapes everything (Cognito MFA × passwordless)

Cognito will **not** run a classic second factor on top of a passwordless first factor. Per the AWS MFA matrix: when a user has **MFA configured (SMS/TOTP/email-MFA)**, they **lose the ability to sign in with EMAIL_OTP / SMS_OTP / WEB_AUTHN as a first factor** — they are forced back to password + that second factor. So "email-OTP login *plus* an optional TOTP second factor" is **not a coherent Cognito combination**; enrolling classic MFA silently disables passwordless for that user.

**Resolution — "MFA optional" is meaningful ONLY as passkey-as-MFA:**
- `MfaConfiguration = OPTIONAL` (not OFF — OFF blocks the passkey-MFA path).
- First factors: `EMAIL_OTP`, `SMS_OTP`, `WEB_AUTHN`.
- `WebAuthnConfiguration.UserVerification = MULTI_FACTOR_WITH_USER_VERIFICATION` → a user-verifying passkey (Face ID / Touch ID / PIN) is treated by Cognito as **satisfying MFA in a single step** (possession + inherence). This IS the optional "strong/MFA-grade" tier.
- **We do NOT expose classic TOTP/SMS-MFA enrollment to passwordless users.** In product copy, "Add MFA / make my account more secure" = **"Add a passkey"**, never "Add a TOTP app." Any high-risk step-up (e.g. wire-instruction changes) is implemented as an **app-driven second OTP challenge**, not via `SetUserMFAPreference`.
- **At least one OTP factor stays permanently enabled per user** as the passkey-recovery path (lost device → sign in with email-OTP → re-enroll a passkey).

This is honest: with passwordless, traditional "optional MFA" is largely a category error in Cognito. We deliver the *intent* (stronger optional auth) via passkeys, and we are explicit that classic TOTP/SMS-MFA is off the table for the passwordless population.

### 2.2 Per-persona flows

| Persona | Sign-in surface | First factors offered | "MFA" / strong-auth | Notes |
|---|---|---|---|---|
| **Borrower** | mortgage-app `/signin` + `/continue` (SDK `USER_AUTH`) | Email OTP (default), SMS OTP (when live), Passkey | Add a passkey (UV) | Auto-confirm on first OTP. G8 fix required (§4, §6). |
| **Agent** | mortgage-app `/signin` | Email OTP, SMS OTP, Passkey | Add a passkey | Same flow as borrower; role = `RealEstateAgent`. |
| **LO / Processor / UW / Closer / Manager / Admin** | suite console `msfg-suite-web` `/signin` (SDK `USER_AUTH`) **and** mortgage-app `/signin` | Email OTP, SMS OTP, Passkey | Add a passkey (recommended for staff) | Staff currently also have PASSWORD; keep `PASSWORD` in `AllowedFirstAuthFactors` during transition, then drop. |

**Factor preference ordering in every UI:** Passkey > Email OTP > SMS OTP. SMS is a fallback, never the default (phone numbers are weak identity + toll-fraud surface).

### 2.3 Staff-initiated borrower verification ("both")

A separate, server-side action — **not** a borrower login. An LO/Processor/Manager/Admin, scoped to a loan they can access, presses "Send verification code" and the borrower receives a code on their **suite-stored** email/phone. Two mechanisms were considered:
- **Cognito-native** (`AdminInitiateAuth USER_AUTH` with `PREFERRED_CHALLENGE`) — server-capable, rides the live EMAIL_OTP rails, but couples identity-verification to the IdP, requires the borrower to be a Cognito user with that channel verified, and the SMS half is blocked by the pool having no `phone_number`/SNS.
- **Suite-owned OTP** (chosen) — the suite mints a hashed, TTL'd, rate-limited one-time code and dispatches via a `VerificationCodePort` against the borrower's `borrower_party` contact. Full control of audit/rate-limit/channel; email works today; SMS swaps in a provider later with zero Cognito dependency.

**Decision: suite-owned OTP** (§6.2). It is decoupled from Cognito tier/pool state and matches the codebase's ports-everywhere principle.

---

## 3. Cognito configuration changes

All of the following are **[OWNER-GATED PROD MUTATION]** against `us-west-1_S6iE2uego`. Tier impact noted per row. **No PLUS upgrade is required** for the target model (passkeys, email/SMS OTP, TOTP, passkey-as-MFA all run on ESSENTIALS). PLUS is only needed if email-as-a-classic-second-factor or threat-protection/adaptive-auth is later required (§8).

### 3.1 Pool-level

| # | Change | API call | Tier | Gate |
|---|---|---|---|---|
| C1 | First factors → add SMS_OTP + WEB_AUTHN | `UpdateUserPool` `Policies.SignInPolicy.AllowedFirstAuthFactors = ["PASSWORD","EMAIL_OTP","SMS_OTP","WEB_AUTHN"]` | ESSENTIALS | **[OWNER-GATED]** |
| C2 | MFA optional | `SetUserPoolMfaConfig --mfa-configuration OPTIONAL` | ESSENTIALS | **[OWNER-GATED]** |
| C3 | WebAuthn RP config | `SetUserPoolMfaConfig --web-authn-configuration RelyingPartyId=msfgco.com,UserVerification=MULTI_FACTOR_WITH_USER_VERIFICATION` | ESSENTIALS | **[OWNER-GATED]** — RP-ID is ~immovable (§10) |
| C4 | `phone_number` already in schema (mutable, not required) — **no schema change**; just begin collecting it | n/a | ESSENTIALS | — |
| C5 | Attach SMS sender | `UpdateUserPool --sms-configuration SnsCallerArn=<role>,ExternalId=<id>,SnsRegion=us-west-1` | ESSENTIALS | **[OWNER-GATED]** — depends on §3.3 |
| C6 | (Optional) Custom SMS/Email sender Lambda for branded OTP | add trigger | ESSENTIALS | Deferred — not day-one |

**RP-ID decision:** `msfgco.com` (eTLD+1) so passkeys work across `app.msfgco.com` and `apply.msfgco.com`. **Consequence:** the suite console (`msfg-suite-web`) lives on a *different* host family → its passkeys bind to a **different RP ID** and will not interoperate with borrower passkeys. That is acceptable (different personas, different devices) but must be stated. **Dev passkeys** need `localhost` RP-ID → a separate dev pool or RP config; do not test prod passkeys from localhost. Changing the RP-ID later **invalidates every existing passkey** — pick it now, deliberately.

### 3.2 App clients

| Client | ClientId | Change | Gate |
|---|---|---|---|
| `mortgage-app-web` (funnel/borrower) | `34rg0vqoobfv8hhvg8kunkd738` | **None** — already has `ALLOW_USER_AUTH` (public client, correct for browser SDK). | — |
| Staff console (`msfg-suite-web`) | **none exists yet** | **Create or designate a public client with `ALLOW_USER_AUTH`** + callbacks for the console host + `openid email profile`. Then wire it into `public/config.json` (currently empty `authority`/`clientId`). | **[OWNER-GATED PROD MUTATION]** — new client |
| `msfg-us-web` (lead capture) | `2ujuqqsscjv4m5gr6qrvjlf3bn` | No passwordless login here; leave as-is (no `ALLOW_USER_AUTH`). | — |
| `MSFG Dashboard` (legacy Node) | `2t9edrhu5crf8vq3ivigv6jopf` | **Out of scope** — unrelated legacy dashboard. | — |

**Staff-console fork (decided):** Build a **custom SDK `USER_AUTH` login** in the console (mirroring the funnel) against a new/updated public client with `ALLOW_USER_AUTH`. Rejected alternative: Cognito **Managed Login** branding (renders the choice screen for free, but no managed-login branding exists on this pool yet and it gives no inline `/continue`-style hook or staff-send-code seam). Owner may flip to Managed Login if the inline UX is ever deemed optional — flagged in §10.

### 3.3 SNS / SMS production access (the heavy, ops-gated lift)

SMS is **fully designed but not day-one**. All **[OWNER-GATED PROD MUTATION + AWS support cases]**. Until done, the FE `availableFactors()` returns email-(+passkey-)only and the SMS toggle is hidden — the system degrades gracefully.

1. **IAM role** Cognito-assumable (`sts:AssumeRole` trust to `cognito-idp.amazonaws.com`), policy `sns:Publish`, trust condition `sts:ExternalId == SmsConfiguration.ExternalId`.
2. **Exit the SMS sandbox** (support case; currently `IsInSandbox=true` in us-west-1 AND us-east-1 → can only text manually-verified numbers). ~1 business day.
3. **Register an origination identity — 10DLC** (brand + campaign; describe use case as "account login one-time passcodes / 2FA for a mortgage origination platform" with opt-in language). Toll-free is the slower alternative. **Short codes out of scope.**
4. **Raise `MonthlySpendLimit`** from the current **$1/mo** sandbox cap to a deliberate production cap (support case). This cap is the toll-fraud blast-radius control — set it tight (§7).
5. Then C5 (attach SMS to the pool) + C1's `SMS_OTP` factor become functional.

### 3.4 WebAuthn enrollment (no pool mutation beyond C3)

Per-user, post-login, headless via the IDP JSON API (no Managed Login needed): `StartWebAuthnRegistration` (access-token auth, scope `aws.cognito.signin.user.admin`) → `navigator.credentials.create()` → `CompleteWebAuthnRegistration`. Sign-in via `USER_AUTH` → `WEB_AUTHN` challenge → `navigator.credentials.get()`. Headless apps must collect the username (email) before offering the WEB_AUTHN challenge (no discoverable-credential autofill outside Managed Login).

---

## 4. Lambda changes

Two Lambdas exist: `PreTokenGeneration = msfg-cognito-pretoken-org-claim` (V1_0; hardcodes `org_id` onto the **id token**), `PostConfirmation = msfg-cognito-postconfirm-borrower-group` (adds the `Borrower` group). **[OWNER-GATED — Lambda + Cognito, outside the suite repo.]**

### 4.1 Fix G8 (role-assignment gap) at the source — **primary fix**

**Root cause (verified):** authorization is sourced **solely** from `cognito:groups` (`CognitoRolesConverter.convert`, `app/.../config/CognitoRolesConverter.java:25-49`). Passwordless EMAIL_OTP auto-confirm does **not** reliably fire `PostConfirmation_ConfirmSignUp`, so the `Borrower` group is never added, so the token carries `cognito:groups=null`, so the converter mints **zero authorities** → the borrower authenticates but is role-less → 403 on everything except the `/me`/party allowlist, and `/me/loans` returns empty.

**Change:** move the `AdminAddUserToGroup(Borrower)` (for funnel-client users) **out of the unreliable `PostConfirmation` trigger and into `msfg-cognito-pretoken-org-claim`**, which fires on **every** token mint regardless of how the user confirmed. Make it **idempotent** (check existing groups; add `Borrower` only if absent and the user has no staff group). This restores in-request `ROLE_BORROWER`, which is required for the per-borrower read allowlist (`SecurityConfig.java:122-125`) and for `maybeLinkBorrower` email-linking (`UserAccountService.java:112-123`). This is the cleanest fix because it keeps `cognito:groups` authoritative; the suite backstop (§6.1) is defense-in-depth, not a substitute.

### 4.2 PreTokenGeneration V1 → V2 (id-token-only `org_id`) — recommended

**Constraint:** V1_0 only writes claims to the **id token**. The suite proved it must read `id_token` (carries `org_id` + `cognito:groups` → `/api/me` 200); the **access token has no `org_id` → 401**. The FE already prefers `id_token` as bearer (mortgage-app `apiClient`), so V1 *works* — but it is fragile: any path that sends the access token 401s (this is the pre-existing console bug, §5/§6).

**Recommendation: upgrade the trigger to V2_0** so `org_id` (and, if desired, the group override) land on **both** the id and access tokens. This (a) lets the console safely send either token, (b) removes the "must use id_token" footgun as the surface widens to SDK flows. **[OWNER-GATED — trigger version change.]** If the owner prefers to defer, the FE/console must be pinned to `id_token` everywhere (§5, §6 enforce this) and V1 stays.

### 4.3 Multi-tenant `org_id` (forward, not now)

The trigger hardcodes `org_id = …00aa` for **every** user. Safe today (one tenant). Before tenant #2: derive `org_id` per-user (a `custom:org_id` attribute set at creation, or a group→org map). Suite-created users get `custom:org_id` stamped in `CognitoUserAdminAdapter.createUser` once the attribute exists. **File as the gating multi-tenant decision; do not implement under this spec.** (§7.3.)

---

## 5. mortgage-app FE change-set

**Load-bearing constraint:** both FE repos depend only on `oidc-client-ts` + `react-oidc-context`, which speak **only** OAuth2 authorize/token — they have **no `InitiateAuth`/`RespondToAuthChallenge`/WebAuthn surface**. The current `CognitoOtpAdapter` is a **stub that throws** in prod (`CognitoOtpAdapter.js:12-13`); passwordless is vapor outside dev. **Central decision: add `@aws-sdk/client-cognito-identity-provider` (v3) and make `CognitoOtpAdapter` real**, calling `InitiateAuth`/`RespondToAuthChallenge` directly and minting tokens into the exact `oidc.user:<authority>:<clientId>` sessionStorage shape `apiClient` already reads — so `apiClient`, `RequireAuth`, `react-oidc-context` need **zero change**.

### 5.1 Files

| Action | Path | Purpose |
|---|---|---|
| add dep | `frontend/package.json` | `@aws-sdk/client-cognito-identity-provider` (+ `@simplewebauthn/browser` for the WebAuthn codec) |
| modify | `src/auth/cognitoConfig.js` | export `cognitoRegion`, `cognitoUserPoolClientId`, `cognitoAuthority` (SDK needs bare clientId+region; authority byte-identical to what `apiClient` keys on) |
| rewrite | `src/auth/passwordless/CognitoOtpAdapter.js` | real `USER_AUTH` adapter (the heart) |
| add | `src/auth/passwordless/cognitoSession.js` | `mintSession(AuthenticationResult)` → write `oidc.user:*` (oidc-client-ts `User` JSON shape) **and** call `userManager.storeUser()` so silent-renew adopts it (see §5.3) |
| modify | `src/auth/passwordless/PasswordlessAuthPort.js` | widen contract: `availableFactors()`, `start(username,factor)`, `respond(state,response)`, `registerPasskey/listPasskeys/deletePasskey(accessToken)` |
| modify | `src/auth/passwordless/DevPasswordlessAdapter.js` | mirror widened contract (accept any factor/code) + update `PasswordlessAuthPort.test.js` |
| add | `src/auth/passwordless/factors.js` | factor enum + labels |
| add | `src/auth/webauthn.js` | base64url↔ArrayBuffer + `navigator.credentials.create/get` wrappers (or thin `@simplewebauthn/browser` shim) |
| add | `src/components/auth/FactorChooser.js` | Email/SMS/passkey toggle, gated on `availableFactors()` + `window.PublicKeyCredential` |
| add | `src/components/auth/PhoneCollect.js` | E.164 phone capture for SMS factor |
| rewrite | `src/pages/ContinuePage.js` | use `FactorChooser`; **preserve the intake tail verbatim** (`createLoanFromIntake`+`carryOverData`+`navigate('/apply')`, `ContinuePage.js:52-54`) |
| add | `src/pages/SignInPage.js` | first-class passwordless login (replaces Hosted-UI redirect for borrower+staff) |
| modify | `src/pages/LandingPage.js`, `AuthRedirect.js`, `App.js` | route `/signin`; "Sign in" → `navigate('/signin')` instead of `signinRedirect()` |
| add | `src/pages/account/SecurityPage.js` | `RequireAuth`-gated: "Add a passkey" (`registerPasskey`), list/delete passkeys; **no classic TOTP/SMS-MFA enrollment** (§2.1) |
| extend | `src/services/mortgageService.js` | `sendBorrowerVerification(loanId, borrowerId, channel)` → suite endpoint (§6.2) |
| modify | `src/pages/LoanDashboardPage.js` and/or `admin/UsersAdmin.js` | staff "Send verification code" button, `useRoles().isLO||isAdmin`-gated |

**Unchanged (explicit):** `apiClient.js` (reads `id_token` from `oidc.user:<authority>:<clientId>`, the exact shape `mintSession` writes — this is what carries `org_id`+`cognito:groups` to the suite), `RequireAuth.js`, `index.js`/AuthProvider, `handoffToken.js`, `continuePrefill.js`, the `/continue` intake tail, the 401→`auth:expired` plumbing.

### 5.2 Factor-choice UX

State machine: `choose-factor → (collect-phone?) → start → (enter-code | passkey-ceremony) → done`.
- **OTP factors:** `start()` → Cognito sends code → show masked destination ("We texted a code to +1•••1234") → code input → `respond(state,{code})`.
- **Passkey:** `start(username,'WEB_AUTHN')` triggers `navigator.credentials.get()` inline (one tap, no code screen) → `respond(state,{assertion})`.
- `SignInPage` = the same `FactorChooser` minus the funnel-summary card and intake call → on success `navigate('/applications')`. Serves **both** borrowers and staff (matches "unified for ALL personas"); no separate staff login page in mortgage-app.

### 5.3 Token storage (CRITICAL)

`mintSession` writes the oidc-client-ts `User` JSON (`id_token`/`access_token`/`refresh_token`/`profile` from the decoded id-token payload/`expires_at`) under `oidc.user:${authority}:${clientId}`. **It must also call the `react-oidc-context` UserManager's `storeUser()`** so the library adopts the session and `automaticSilentRenew` can refresh via the `refresh_token` — otherwise tokens minted by the SDK are never renewed (the library didn't perform the sign-in). On `auth:expired`, fall back to re-running the passwordless `start` flow.

⚠️ **Pin Cognito's `USER_AUTH` challenge-parameter key names against the live pool during build** (`EMAIL_OTP_CODE`, `CREDENTIAL`, `CredentialCreationOptions`, `CODE_DELIVERY_DESTINATION`, etc. are version-sensitive). Do not trust any sketch's key names blindly.

### 5.4 Phone collection / passkey prereqs

- SMS-OTP needs a verified E.164 `phone_number` on the Cognito user. For a **never-logged-in borrower at `/continue`**, the pool must have `phone_number` set at funnel time (post-confirm/pre-token Lambda owner must coordinate), else Cognito silently can't text them. Self-service `UpdateUserAttributes`+`VerifyUserAttribute` only works post-login (so phone-add lives in `SecurityPage`, not at first `/continue`).
- WebAuthn requires HTTPS/secure context (localhost is the only HTTP exception) and an authenticated session to **enroll** — bake passkey enrollment into a post-login "secure your account" step.

---

## 6. Suite change-set (msfg-suite, Java/Spring)

Three workstreams. Migration head is **V25**; new migration is **V26** (single-threaded sequence per `CLAUDE.md` — author on the integration branch only). TDD throughout.

### 6.1 Workstream 1 — Reliable BORROWER role assignment (suite backstop for G8)

The suite-side default is the **durable backstop**; the Lambda fix (§4.1) is the primary. Build **both**.

**`identity/.../service/UserAccountService.java`** — in `resolveOrCreate`, after `String role = primaryRole(roles);` apply a default **only when `role == null`**:
```
private static final String DEFAULT_PARTY_ROLE = Role.BORROWER.name();
...
if (role == null) { role = DEFAULT_PARTY_ROLE; }   // G8 backstop: tenant-valid, group-less → least-privilege BORROWER
```
**Why this is fail-safe and never grants staff access:**
- `primaryRole` ranks staff roles **above** party roles and returns null **only** when there are zero recognized authorities (`UserAccountService.java:32-34,138-144`). A staff member always carries ≥1 staff group → never hits the default.
- `BORROWER` is the floor of the lattice; `LoanAccessGuard` admits a borrower only to their own linked loan and **excludes BORROWER from `hasOrgWideView()`** (`loan-core/.../LoanAccessGuard.java:34-36,51-54,91-128`). Linkage itself requires a verified-email match. A wrongly-defaulted user gets strictly nothing beyond their own linked rows.

**Hard rules:**
- The default is applied to the **persisted `user_account.role` ONLY** — **never** synthesized into the Spring `SecurityContext` authorities. Do **not** mint a `ROLE_BORROWER` `GrantedAuthority` from a self-asserted token; keep authority-minting in `CognitoRolesConverter` from the trusted group claim. (Full in-request function is the Lambda fix's job.)
- **No-downgrade guard:** apply the default unconditionally in the **insert** branch; in the **update** branch keep the existing `if (role != null && !role.equals(...))` guard so a transiently role-less token can **never** rewrite a stored staff role down to BORROWER. Only write the default when `existing == null` OR `existing.getRole() == null`.

**Tests** (extend `UserAccountServiceHelpersTest`): `primaryRole({})==null`; default path yields `BORROWER`; a staff authority set never defaults; a role-less token never downgrades a persisted staff row.

**Documented gap (no code):** `maybeLinkBorrower` gates on the **in-request** `ROLE_BORROWER` authority, not the defaulted persisted role — so with the backstop alone, email-auto-link is skipped (acceptable: Phase-A intake links deterministically by id, `IntakeService.java:130-135`). This is the concrete reason the Lambda fix is still wanted.

**Log** at INFO (sub + org only, PII-safe) whenever the default fires, so operators can measure Lambda misfire rate and confirm when §4.1 lands.

### 6.2 Workstream 2 — Staff-initiated borrower verification

**Endpoint** (new `BorrowerVerificationController` in the **identity** module — already owns user-admin + AWS SDK on classpath):
```
POST /api/identity/borrowers/{borrowerId}/send-verification
body: { "channel": "EMAIL" | "SMS", "loanId": "<uuid>" }   → 204 (generic; never echoes code/contact existence)
POST /api/identity/borrowers/{borrowerId}/verify-code
body: { "code": "<6-digit>", "loanId": "<uuid>" }          → 204 / generic failure
```
Placed **outside** `/api/admin/users/**` so it gets its own filter rule.

**Authorization (staff-only, tenant + loan scoped):**
- Filter layer (`SecurityConfig.filterChain`, **before** the `/api/**` catch-all `:128`):
  ```
  .requestMatchers(HttpMethod.POST, "/api/identity/borrowers/*/send-verification","/api/identity/borrowers/*/verify-code")
      .hasAnyRole("LO","PROCESSOR","MANAGER","ADMIN")
  ```
  Exclude BORROWER/REAL_ESTATE_AGENT entirely (a borrower must never trigger codes).
- Service layer (authoritative): `accessGuard.assertCanAccess(loanService.get(loanId))` — the identical gate used by `BorrowerService.revealSsn` (`parties/.../BorrowerService.java:151`) → org-wide for Processor/UW/Closer/Manager/Admin, owner-scoped for LO, 403 for borrower/agent/platform-admin. Then `borrowerService.isBorrowerInLoan(loanId, borrowerId)` (`:97`) to block cross-loan IDOR — but return the **same generic 204** for a non-existent borrower within an accessible loan (don't leak borrower-id existence). All reads run under the bound `@TenantId` → automatically tenant-scoped.

**Code lifecycle:** 6-digit numeric; store **only a salted one-way hash** (SHA-256 + per-row salt or platform MAC — NOT the reversible `EncryptedStringConverter`); TTL 5–10 min; `attempts` counter; single-use (`consumed_at`); constant-time compare; lock out after 5 failures (require a fresh send).

**Dispatch port (portability):** new `platform.notify.VerificationCodePort { void send(Channel, String destination, String code); }` with `EmailVerificationCodeAdapter` (today) + `SmsVerificationCodeAdapter` (`@ConditionalOnProperty`, dormant — mirrors the `CognitoUserAdminAdapter` dormant pattern). `destination` resolves from `borrower_party` (`cellPhone` for SMS, `email` for EMAIL, `BorrowerParty.java:60-71`); if absent, still return generic 204.

**Audit (mirror `PiiAccessLog`):** **new `verification_request` table, migration `V26__verification_request.sql`** — cols `id, org_id, loan_id, borrower_id, channel, code_hash, expires_at, attempts, consumed_at, created_at, created_by` with **FORCE RLS + `WITH CHECK` + `app_user` SELECT/INSERT/UPDATE** grants (UPDATE needed for attempts/consumed). `created_by` = principal sub (`@CreatedBy`). The audit row IS the verification record. (`PiiAccessRecorder.record(...)` is the fallback shape if a table is deemed overkill, but a dedicated table is recommended so OTP store + audit co-locate.)

### 6.3 Workstream 3 — Hardening (suite-relevant)

- **OTP rate-limit/lockout** (suite has no rate-limit infra today): **send throttle** per `(org_id, borrower_id)` and per acting staff `sub` (≤3 / 15 min) enforced by counting recent `verification_request` rows before insert (no new infra); over-limit → **429** via a new `TooManyRequestsException` in `platform.error` + a handler alongside the existing DIV→409/optimistic-lock→409 handlers. **Verify lockout:** increment `attempts`, dead after 5.
- **Enumeration:** `send-verification`/`verify-code` return the same generic response regardless of borrower/contact existence; the existing `OrgScopedJwtAuthenticationConverter` already yields a uniform 401 for missing/bad `org_id` (no tenant-existence leak). Cognito `PreventUserExistenceErrors` is already ON — keep it.
- **Token posture:** suite continues to **fail closed on missing/garbage `org_id`** (`OrgScopedJwtAuthenticationConverter.java:28-39`). If the Lambda stays V1 (id-token-only `org_id`), the **console must send `id_token`** — fix its `getToken()` which currently returns `access_token` → suite 401 (`msfg-suite-web` `CognitoAuth.ts:53-56`, pre-existing latent bug surfaced by this work).

---

## 7. Security hardening (cross-cutting)

### 7.1 OTP throttling & TTL
Cognito's native OTP rate-limiting is coarse and code TTL is short (single-digit minutes). Add **app-side per-username + per-IP request throttles** (Cognito-side OTP via WAF/Cognito; suite-side OTP via the §6.3 DB-count gate). Cap OTP **requests** (not just verifies) per user per window — this blunts email-bombing and SMS-pumping.

### 7.2 Enumeration
Covered: Cognito `PreventUserExistenceErrors` ON; suite endpoints return generic responses (§6.3); uniform 401 on bad `org_id`.

### 7.3 Single-tenant hardcoded `org_id` — forward path
**Risk:** the pre-token Lambda stamps `org_id=…00aa` for **every** user; the day tenant #2 onboards in the shared pool, all users would still be stamped `…00aa` → cross-tenant exposure. The suite is already built for many tenants (`@TenantId` everywhere + RLS backstop); the **only** single-tenant assumption is the Lambda constant.
**Mitigations:** (a) Lambda derives `org_id` per-user from `custom:org_id` (or group→org map) — file as the gating multi-tenant task (§4.3); (b) `CognitoUserAdminAdapter.createUser` stamps `custom:org_id` once the attribute exists; (c) **the deployment requirement from `CLAUDE.md`: run the app datasource as the non-owner `app_user`** so Postgres RLS (`FORCE`+`WITH CHECK`, fail-closed) is the live backstop if `@TenantId` ever sees a wrong `org_id`. **Verify (c) is true in the deployed env before multi-tenant.** No suite trust-model change needed now.

### 7.4 Audit logging of auth-sensitive actions
- Verification sends/verifies → `verification_request` table (§6.2).
- Passkey enroll/delete, staff-initiated OTP triggers, OTP failures, `createUser`/`resetPassword`/`linkUser` (the staff override that can overwrite a link, `BorrowerService.java:140-146`) → mirror into an append-only audit (dedicated `auth_event` log or `pii_access_log` with `subjectType="USER"`), independently reviewable rather than inferred from row `updatedBy`.
- Cognito emits sign-in events to **CloudTrail** (Plus adds exportable auth-event logs — not bought here, §8).

### 7.5 Toll-fraud (the #1 passwordless SMS risk)
Hard `MonthlySpendLimit`, **US-only destination allowlist**, CloudWatch SMS-spend alarms, app-side per-identifier SMS throttle. Email/passkey preferred; SMS opt-in only.

### 7.6 Recovery matrix
passkey lost → email-OTP (re-enroll passkey); email lost → SMS-OTP or staff-initiated verify (§6.2); both lost → manual KYC + admin reset. Encourage ≥2 passkeys/user.

---

## 8. Cost

| Item | Cost | Notes |
|---|---|---|
| Email OTP (current factor) | ~free | Cognito `COGNITO_DEFAULT` shared pool (~50/day shared) — move to SES before real volume |
| Passkeys (WebAuthn) | $0 | ESSENTIALS-included |
| TOTP MFA | $0 | Not used in the passwordless model (§2.1) |
| Cognito MAU | ESSENTIALS **$0.015/MAU, 10k free** | 21 users today → effectively $0 |
| SMS — per message (US) | **~$0.008–$0.012 all-in/segment** (~$0.00581–$0.00645 AWS + ~$0.0025 carrier) | 6-char OTP = 1 segment |
| SMS — 10DLC fixed | ~$1/mo number + ~$2–$10/mo campaign + ~$4 one-time brand | Toll-free alt: ~$2/mo + $0.0025/msg |
| SMS — est. monthly | At, say, 2,000 OTP SMS/mo: **~$16–$24 transport + ~$3–$11 fixed ≈ $20–$35/mo** | Scales linearly; cap via `MonthlySpendLimit` |
| **PLUS tier upgrade** | **$0.02/MAU (no free tier), +$0.005/MAU over ESSENTIALS** | **NOT required** for this spec. Only if email-as-classic-2FA or threat-protection/adaptive-auth is later wanted. For a regulated NPI SoR, consider PLUS for adaptive auth + compromised-credential + auth-event export — but its value narrows once fully passwordless. |

**Bottom line:** day-one (email-OTP + passkeys) is effectively $0. SMS adds ~$20–$35/mo + AWS support lead time. No tier upgrade needed.

---

## 9. Phasing / sequencing (build order, TDD)

Each phase: brainstorm→spec→plan→subagent build→2-stage review (+opus on security/money)→merge→close loop. Prod-mutating/owner-gated steps flagged.

| Phase | Work | Prod-mutating? |
|---|---|---|
| **P0 — Fix G8 (unblocks everything)** | Suite backstop (§6.1, TDD, V-none) **+** file the Lambda task (§4.1). Verify a group-less token → suite materializes `BORROWER`, no staff downgrade. | Suite: no. Lambda: **[OWNER-GATED]** |
| **P1 — Cognito enablement (email-OTP + passkey)** | C2 (MFA OPTIONAL), C3 (WebAuthn RP `msfgco.com`, UV=MULTI_FACTOR), C1 minus SMS (`["PASSWORD","EMAIL_OTP","WEB_AUTHN"]`). Decide RP-ID first. | **[OWNER-GATED PROD MUTATION ×3]** |
| **P2 — Staff console app client** | Create/designate public `ALLOW_USER_AUTH` client; wire `public/config.json`. | **[OWNER-GATED PROD MUTATION — new client]** |
| **P3 — mortgage-app FE** | Add SDK dep; real `CognitoOtpAdapter` + `mintSession`+`storeUser`; `FactorChooser`; `SignInPage`; `SecurityPage` (passkey enroll); rewrite `/continue` (preserve intake tail). Email-OTP + passkey live; SMS hidden via `availableFactors()`. | No (FE only) |
| **P4 — Suite staff-initiated verification** | `V26` table; `BorrowerVerificationController`/`Service`; `VerificationCodePort` + email adapter (SMS dormant); rate-limit+429; audit; filter rule. | No (suite). Email send: yes-runtime |
| **P5 — Staff console SDK login** | Mirror the §5 adapter into `msfg-suite-web` (TS); fix `getToken()`→`id_token`; `/signin` + `FactorChooser`; staff "send code" button. | No (FE only) |
| **P6 — SMS rails (parallel, ops-gated)** | §3.3 IAM role, sandbox exit, 10DLC, spend cap → C5 + add `SMS_OTP` to C1 → flip FE `availableFactors()`. | **[OWNER-GATED PROD MUTATION + AWS support cases]** |
| **P7 — (optional, deferred)** | PreTokenGeneration V1→V2 (§4.2); SES for email; PLUS-tier decision (§8). | **[OWNER-GATED]** |

P0 is the critical path; P1–P5 deliver the full email-OTP + passkey unified model + staff-send-code. P6 (SMS) runs in parallel and lands when ops clears.

---

## 10. Risks & open decisions (owner calls)

1. **RP-ID is ~immovable.** `msfgco.com` recommended (covers `app.`/`apply.`). Changing later invalidates all passkeys. **Confirm before P1.** Staff console (different host) = different RP ID → staff/borrower passkeys don't interoperate (acceptable?).
2. **PreTokenGeneration V1→V2.** Upgrade to put `org_id` on both tokens (removes the id-token-only footgun), or stay V1 and pin every client to `id_token`? **Owner call.** (Recommend V2.)
3. **Staff console login: SDK vs Managed Login.** Spec assumes SDK (custom `/signin`) for parity + the send-code seam. Managed Login is lower-effort if the inline UX is droppable. **Confirm SDK.**
4. **SMS go/no-go + budget.** ~$20–$35/mo + 10DLC lead time + toll-fraud surface. Email+passkey cover the core; is SMS day-2? **Owner call on whether P6 is in this cycle.**
5. **Email deliverability.** Cognito `COGNITO_DEFAULT` (~50/day shared) is inadequate for real OTP volume → **move to SES** (no SES identities exist today). When?
6. **PLUS tier.** Not needed for the model, but for a regulated NPI SoR, adaptive auth + compromised-credential + auth-event export may be worth +$0.005/MAU. **Owner call (defer ok).**
7. **Phone at funnel time.** SMS-OTP for a never-logged-in borrower needs `phone_number` set during funnel signup (Lambda/funnel coordination). Out of scope until P6, but flag the dependency.
8. **Multi-tenant `org_id` constant** is a latent cross-tenant risk the day tenant #2 lands (§7.3). Not in this spec; **must be solved before onboarding a second tenant** + confirm the non-owner-datasource RLS backstop is live in prod.

---

## 11. Verification plan

Mirror the live token test already proven (mint a token → call `GET /api/me` → expect 200 with the correct role; access-token-without-`org_id` → 401). One E2E proof per factor + per new endpoint.

| # | Proof | Expected |
|---|---|---|
| V1 | **Email-OTP borrower** — `USER_AUTH`(EMAIL_OTP) → code → `RespondToAuthChallenge` → mint → `GET /api/me` | 200, `role=BORROWER` (proves G8 fix end-to-end; the headline proof) |
| V2 | **Passkey borrower** — enroll via `Start/CompleteWebAuthnRegistration` (post email-OTP login), then sign in via `USER_AUTH`(WEB_AUTHN) → mint → `/api/me` | 200, role correct; assertion accepted at RP `msfgco.com` |
| V3 | **SMS-OTP** (post-P6, against a sandbox-verified number first) — `USER_AUTH`(SMS_OTP) → code → mint → `/api/me` | 200; `phone_number_verified=true` auto-set |
| V4 | **Staff email-OTP** (LO) → mint → `/api/me` | 200, `role=LO`; org-wide loans visible per `LoanAccessGuard` |
| V5 | **G8 backstop unit/IT** — materialize a token with `cognito:groups=null` + valid `org_id` | `user_account.role=BORROWER`; staff token never downgraded; no synthesized authority in SecurityContext |
| V6 | **Staff send-verification authz** — LO on owned loan → 204; LO on non-owned loan → 403; borrower/agent → 403; cross-loan borrowerId → generic 204 (no leak) | as stated |
| V7 | **Verify-code lifecycle** — correct code in TTL → 204; expired → generic fail; 6th wrong attempt → locked; wrong-then-correct after lock → fail | as stated |
| V8 | **Rate-limit** — 4th send in 15 min for a `(org,borrower)` → 429 | `TooManyRequestsException` |
| V9 | **Tenant isolation** — org-A staff send-code to org-B borrower | 404/403 (RLS + `@TenantId` block) |
| V10 | **Token storage** — after `mintSession`, `apiClient.getStoredUser()` returns the user; `react-oidc-context` sees the session; silent-renew refreshes via `refresh_token` | session adopted; no forced re-login at id-token expiry |
| V11 | **Audit** — every send/verify/passkey-enroll/staff-trigger writes an append-only row (who/when/borrower/channel) | rows present, code never plaintext |
| V12 | **Graceful degradation** — pre-P6 build with SMS not enabled | FE `availableFactors()` omits SMS; toggle hidden; email+passkey work |

---

**End of spec.** Build contract is P0→P5 (+P6 SMS parallel, +P7 deferred). The two highest-leverage actions: **fix G8 at both layers (P0)** and **make `CognitoOtpAdapter` real (P3)** — everything else hangs off those.
