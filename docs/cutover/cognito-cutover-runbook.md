# Cognito Cutover Runbook — turning the real funnel on

**Status:** prep artifact for owner review. **No AWS changes have been made.** This consolidates the
three-repo cutover: what the owner does in AWS, the one code gap that remains, and the exact
dev-bridge fallbacks to flip off. The org_id pre-token Lambda + its AWS deploy steps live in
[`../deploy/cognito-pretoken-org-claim.md`](../deploy/cognito-pretoken-org-claim.md) — this doc does
not repeat them.

Pool today (shared, hand-managed, region `us-west-1`): **`us-west-1_S6iE2uego`** — backs
`mortgage-app` (`app.msfgco.com`), the legacy `dashboard.msfgco.com`, and the msfg.us session layer.

---

## 0. Two decisions to make first (owner)

### D-A · Shared pool vs. new dedicated LOS pool
The two existing design docs disagree, on purpose:
- [`../deploy/cognito-pretoken-org-claim.md`](../deploy/cognito-pretoken-org-claim.md) — add `org_id`
  to the **shared** pool `us-west-1_S6iE2uego`. This is the path that lights up the **borrower funnel
  now** (msfg.us + mortgage-app already use this pool) and releases msfg.us PR #9.
- [`cognito-deploy-seam.md`](cognito-deploy-seam.md) — a **new dedicated LOS pool** for the staff
  console (`suite-web`), isolated from mortgage-app.

These are not mutually exclusive: **shared pool gets `org_id` for the borrower funnel now**; a
dedicated staff pool is a later, separable step for the back-office console. **Recommendation: do the
shared-pool `org_id` Lambda now** (smallest change that unblocks the funnel + #9), defer the dedicated
pool. Decide before touching AWS.

### D-B · Passwordless mechanism (this is the one code gap — see §2)
The mortgage-app `/continue` page authenticates with **passwordless email OTP**, but the real adapter
is a **stub** today. Cognito offers two mechanisms; pick one before implementing:
- **Native email OTP** (`USER_AUTH` flow, `EMAIL_OTP`) — requires the pool on the **Essentials** tier.
- **Custom-auth-challenge Lambdas** (`DefineAuthChallenge` / `CreateAuthChallenge` /
  `VerifyAuthChallenge`) — works on any tier, more code (three more Lambdas).

---

## 1. Owner AWS actions (on the shared pool, per D-A)

1. **Deploy the `org_id` pre-token Lambda** and wire it as the pool's *Pre token generation* trigger
   (V1 / ID-token). Full source + CLI/console steps:
   [`../deploy/cognito-pretoken-org-claim.md`](../deploy/cognito-pretoken-org-claim.md).
   Env: `MSFG_ORG_ID=00000000-0000-0000-0000-0000000000aa` (the MSFG org seeded in suite
   `V3__multitenancy.sql`). ⚠️ Shared-pool blast radius — use the console, not blind `update-user-pool`.
2. **App-client auth flows** — on the SPA client `34rg0vqoobfv8hhvg8kunkd738` enable
   `ALLOW_USER_PASSWORD_AUTH` (and, for D-B native OTP, `ALLOW_USER_AUTH`).
3. **Self-service sign-up** enabled on the pool; **email verification ON** (suite's borrower
   auto-link refuses to stamp unless `email_verified=true`).
4. **Group placement** — borrower/agent self-sign-ups must land in the `Borrower` / `RealEstateAgent`
   groups (the org_id Lambda does NOT assign groups). Confirm via a pre-sign-up / post-confirmation
   rule. Group names are case-sensitive and must match the allowlist (`Admin`, `Manager`, `LO`,
   `Processor`, `Borrower`, `RealEstateAgent`, + exact `UNDERWRITER`/`CLOSER`/`PLATFORM_ADMIN`).
5. **A real `HANDOFF_TOKEN_SECRET`** (≥32 bytes) — set on **both** msfg.us and (if/when server-side
   verification is added) the app. Today the app FE only *decodes* the token, so the secret is not yet
   verified app-side; set it now so the future verification is a no-op flip.

Verification (mint a token → confirm bare `org_id` + `cognito:groups` + `email_verified` → `GET
/api/me` 200 → regression-check dashboard + mortgage-app still sign in) is in the Lambda doc §4.

---

## 2. The one code gap — `CognitoOtpAdapter` (mortgage-app FE)

`frontend/src/auth/passwordless/PasswordlessAuthPort.js` selects an adapter:
- dev (`REACT_APP_DEV_SUB` set) → `DevPasswordlessAdapter` (auto-verifies any code — what works locally)
- prod → **`CognitoOtpAdapter` — currently a stub that throws "not implemented"**

This is the only thing besides AWS config standing between the built `/continue` page and a live
borrower funnel. Implement it per the D-B choice:
- **Native EMAIL_OTP:** `requestCode(email)` → `InitiateAuth USER_AUTH` (PREFERRED_CHALLENGE
  `EMAIL_OTP`); `verifyCode(email, code)` → `RespondToAuthChallenge` → returns the id_token the SPA
  then carries.
- **Custom-auth-challenge:** the same two calls against the custom flow + the three challenge Lambdas.

It needs a live pool to test (the dev adapter is the local stand-in), so build it **after** D-B is
chosen and the pool flags (§1.2) are on. Until then the funnel runs on the dev adapter locally.

---

## 3. Dev-bridge flip-off inventory (everything that must be OFF in prod)

All of it is guarded by a profile or an env var — **nothing needs deleting; prod just must not set
the dev switches.** Verified file:line, three repos.

### msfg-suite (Spring profile `local` only)
| Bridge | Location | Off in prod by |
|---|---|---|
| `LocalDevSecurityConfig` — honors `X-Dev-Sub`/`X-Dev-Roles`/`X-Dev-Org`, default dev ADMIN, org `…00aa` | `app/.../config/LocalDevSecurityConfig.java` (`@Profile("local")`) | run with `--spring.profiles.active=prod` (or `dev`); never `local` |
| Real Cognito chain is the inverse | `app/.../config/SecurityConfig.java` (`@Profile("!local")`) | active automatically in non-local |
| RLS runtime-role check | `RlsRuntimeRoleVerifier.java` (`@Profile("!local & !test")`) | active in prod; needs the non-owner `app_user` datasource (see seam doc) |
| Required env (prod) | — | `COGNITO_ISSUER=https://cognito-idp.us-west-1.amazonaws.com/us-west-1_S6iE2uego`, `LOS_CORS_ALLOWED_ORIGINS`, `LOS_NPI_KEY` |

### mortgage-app
| Bridge | Location | Off in prod by |
|---|---|---|
| `LocalSecurityConfig` — fixed dev borrower `…00b0`, `borrower@dev.local`, group `Borrower` | `backend/.../config/LocalSecurityConfig.java` (`@Profile("local")`) | non-`local` profile (real `SecurityConfig` is `@Profile("!local")`) |
| FE auth bypass — skips Cognito redirect | `frontend/src/auth/RequireAuth.js` (`if REACT_APP_DEV_SUB`) | unset `REACT_APP_DEV_SUB` (prod `deploy.sh` does not set it) |
| FE dev headers `X-Dev-*` | `frontend/src/services/apiClient.js` | unset `REACT_APP_DEV_SUB`/`_ROLES`/`_ORG` |
| **Passwordless dev adapter (auto-verify OTP)** | `frontend/src/auth/passwordless/*` | unset `REACT_APP_DEV_SUB` → selects `CognitoOtpAdapter` (**must be implemented — §2**) |
| `SuiteClient` dev-header forwarding | `backend/.../integration/SuiteClient.java` | controller already passes `null` in prod — no action |

### msfg.us
| Bridge | Location | Off in prod by |
|---|---|---|
| `HANDOFF_TOKEN_SECRET` default `"local-unsigned-dev-secret"` | `src/app/api/v1/applications/route.ts`, `src/lib/env.ts` | set a real secret (§1.5) |
| Funnel dev bypass | `src/lib/env.ts` (`DEV_FUNNEL_BYPASS`, `DEV_SUB`/`DEV_ROLES`/`DEV_ORG`) | leave unset in prod |
| Auth enabled only when configured | `authConfigured()` in `src/lib/auth/cognito.ts` | set `COGNITO_CLIENT_ID` + `COGNITO_HOSTED_UI_DOMAIN` |

---

## 4. Prod env matrix (set these; never set the dev switches above)

**msfg-suite:** `SPRING_PROFILES_ACTIVE=prod` · `COGNITO_ISSUER=https://cognito-idp.us-west-1.amazonaws.com/us-west-1_S6iE2uego`
· `LOS_CORS_ALLOWED_ORIGINS=https://app.msfgco.com,https://los.msfgco.com` · `LOS_NPI_KEY=<aes-256 b64>`
· datasource as the non-owner `app_user` role · (when activating the Cognito user-admin adapter from
PR #8) `LOS_IDENTITY_USER_ADMIN=cognito` + `LOS_IDENTITY_COGNITO_USER_POOL_ID=us-west-1_S6iE2uego`.

**mortgage-app FE (prod build — dev vars absent):** `REACT_APP_API_URL` · `REACT_APP_COGNITO_AUTHORITY`
· `REACT_APP_COGNITO_CLIENT_ID=34rg0vqoobfv8hhvg8kunkd738` · `REACT_APP_COGNITO_DOMAIN` ·
`REACT_APP_COGNITO_REDIRECT_URI=https://app.msfgco.com/auth/callback`.

**msfg.us:** `COGNITO_CLIENT_ID` · `COGNITO_HOSTED_UI_DOMAIN` · `AUTH_REDIRECT_URI=https://msfg.us/auth/callback`
· `HANDOFF_TOKEN_SECRET=<real>` · `NEXT_PUBLIC_APP_URL=https://app.msfgco.com` · `LOS_API_BASE` ·
(never) `DEV_FUNNEL_BYPASS`/`DEV_SUB`/`DEV_ROLES`/`DEV_ORG`.

---

## 5. Order of operations + rollback

1. Owner: decide D-A + D-B.
2. Owner: deploy org_id Lambda + wire trigger (console) + flip app-client/pool flags (§1).
3. Owner: verify (Lambda doc §4) + regression-check dashboard + mortgage-app sign-in.
4. Code: implement `CognitoOtpAdapter` per D-B (§2); deploy mortgage-app with dev vars **unset**.
5. Code: msfg.us prod env (§4); **un-hold + merge PR #9**.
6. Suite: deploy with `prod` profile + the §4 env (suite is not yet deployed; that is its own step).

**Rollback:** the org_id claim is additive (other consumers ignore it); the only blast-radius risk is
the trigger itself — detach the Pre-token-generation trigger in the console to fully revert. Keep the
`/continue` dev adapter until `CognitoOtpAdapter` is verified against the live pool.
