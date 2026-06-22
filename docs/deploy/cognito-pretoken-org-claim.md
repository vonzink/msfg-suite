# Cognito Pre-Token-Generation Lambda — inject `org_id` for msfg-suite

**Status:** PROPOSAL / deploy artifact for owner review. Nothing here is deployed. Do NOT run any of
the AWS commands without reviewing the risks at the bottom.

## Why this exists

msfg-suite fail-closes (401) on any JWT that does **not** carry a top-level `org_id` claim that
parses as a UUID. The shared Cognito pool that backs `mortgage-app` (and the legacy
`dashboard.msfgco.com`) does **not** emit `org_id` today. To let borrower/agent/staff tokens from
that pool authenticate against msfg-suite, a **pre-token-generation Lambda** must inject a constant
MSFG `org_id` into the token msfg-suite validates.

### Exactly what msfg-suite reads (verified against source)

| Claim | How suite reads it | Source |
|---|---|---|
| `org_id` | **top-level, bare** (NOT `custom:org_id`). Must be non-blank and `UUID.fromString`-parseable or the request is rejected `401`. | `app/.../config/OrgScopedJwtAuthenticationConverter.java:29-37` (gate); `platform/.../security/JwtPrincipalAdapter.java:36,67-81` (read) |
| `cognito:groups` | a JSON **array** of group-name strings, mapped to suite `Role`s via an alias map. | `app/.../config/CognitoRolesConverter.java:25-62` |
| `email` | top-level string; used to materialize the local `user_account` row and to borrower-auto-link. | `JwtPrincipalAdapter.java:32,45-47` |
| `email_verified` | JSON boolean `true` (or the string `"true"`); **anything else → false**. The borrower auto-link refuses to stamp unless this is true. | `JwtPrincipalAdapter.java:37,84-100`; gate in `identity/.../UserAccountService.java:116` |

Group alias map currently accepted (case-sensitive):
`Admin→ADMIN`, `Manager→MANAGER`, `LO→LO`, `Processor→PROCESSOR`, `Borrower→BORROWER`,
`RealEstateAgent→REAL_ESTATE_AGENT`, plus exact enum names `UNDERWRITER` / `CLOSER` /
`PLATFORM_ADMIN`. The pool's `External` group is intentionally dropped. Unknown strings are dropped,
never mapped.

The canonical claim shape suite expects is mirrored in the dev profile (a useful reference):
`app/.../config/LocalDevSecurityConfig.java:55-78` builds a fake JWT with top-level `org_id`,
`cognito:groups`, `email_verified`.

---

## DECISIVE FINDING — which token, therefore which trigger version

**The SPA sends the Cognito *ID token* as the bearer**, not the access token.

> `frontend/src/services/apiClient.js:39` — `const token = user?.id_token || user?.access_token;`
> Comment at `:35-38`: "Prefer id_token over access_token: Cognito access tokens don't carry the
> `email` claim, but the backend needs it…". `cognitoConfig.js:30` sets `loadUserInfo: false` and
> scope is `openid email profile` — so the ID token is the carrier of identity claims.

msfg-suite validates the bearer by **signature + issuer** (`COGNITO_ISSUER`), not audience
(`SecurityConfig.java`), so it will validate either token — but **the claims must be present on the
token actually sent**, which is the **ID token**.

**Consequence:** the standard **V1 pre-token-generation trigger customizes the ID token**, which is
exactly the token suite receives. **V1 is sufficient. The `V2_0` trigger
(`claimsAndScopeOverrideDetails`, which is required to customize the *access* token) is NOT required
for the current SPA.** This corrects the assumption that an access-token V2 trigger is needed —
because suite is fed the ID token.

> Keep this re-verified at provision time: if the SPA is ever changed to send the access token (or a
> non-`oidc-client-ts` client is added), switch to the V2_0 Lambda variant (provided at the bottom)
> and set the trigger's **Lambda version** to `V2_0`, because V1 cannot edit the access token.

V1 ID-token customization can add/override custom claims and override `groups`. `cognito:groups` and
`email_verified` are native ID-token claims and are passed through unchanged; we only **add** `org_id`.

---

## 1. The Lambda source (Node.js 20.x)

### Primary — V1 trigger (customizes the ID token = the token suite validates)

`index.mjs`:

```js
// Cognito Pre Token Generation — V1 trigger.
// Injects a constant org_id claim into the ID token so msfg-suite (which fail-closes on a
// missing/!UUID org_id) accepts tokens from the shared pool. MSFG-only for now → org_id is a
// constant. cognito:groups and email_verified are native ID-token claims and pass through untouched.
//
// Trigger: "Pre token generation" with Lambda trigger version V1_0 (default). V1 edits the ID token.
// Env var MSFG_ORG_ID must be the MSFG org UUID: 00000000-0000-0000-0000-0000000000aa
//   (seeded in msfg-suite V3__multitenancy.sql; also the suite dev org).

const ORG_ID = process.env.MSFG_ORG_ID; // REQUIRED — set in the Lambda console / CDK

export const handler = async (event) => {
  if (!ORG_ID) {
    // Fail loud in logs but do NOT block sign-in: returning the event unchanged means suite
    // will 401 (no org_id) while the dashboard/mortgage-app keep working. Misconfig is visible
    // via suite 401s + this log line, not a pool-wide auth outage.
    console.error('MSFG_ORG_ID env var is not set — org_id will NOT be injected');
    return event;
  }

  // For MSFG-only-now every user maps to the one MSFG org. The future multi-tenant lookup
  // (per-user custom:org_id attribute or a DynamoDB/HTTP lookup) slots in right here.
  const orgId = ORG_ID;

  event.response = event.response || {};
  event.response.claimsOverrideDetails = {
    ...(event.response.claimsOverrideDetails || {}),
    claimsToAddOrOverride: {
      ...((event.response.claimsOverrideDetails || {}).claimsToAddOrOverride || {}),
      // BARE top-level claim — NOT custom:org_id. Suite reads bare org_id only.
      org_id: orgId,
    },
    // Do NOT set claimsToSuppress for groups/email_verified — leave them native + intact.
  };

  return event;
};
```

Notes:
- Cognito **prefixes custom attributes** with `custom:`. Adding a **claim** via
  `claimsToAddOrOverride.org_id` produces a **bare** `org_id` claim — which is what suite reads. A
  pool *custom attribute* would surface as `custom:org_id`, which suite does **not** read. The Lambda
  is therefore the only way to get the bare claim. (Matches `docs/cutover/cognito-deploy-seam.md`.)
- `org_id` is emitted as a **string** UUID. Suite does `UUID.fromString(claim.toString().trim())`,
  so a string is correct.
- We never touch `cognito:groups` or `email_verified` → they remain on the ID token natively.

### Variant — V2_0 trigger (ONLY if the bearer ever becomes the access token)

Not needed for the current SPA. Provided so the owner can swap without re-deriving. Requires setting
the trigger's **Lambda version = V2_0** in the pool.

```js
// Cognito Pre Token Generation — V2_0 trigger. Edits BOTH ID and access tokens.
const ORG_ID = process.env.MSFG_ORG_ID;

export const handler = async (event) => {
  if (!ORG_ID) { console.error('MSFG_ORG_ID not set'); return event; }
  const add = { org_id: ORG_ID };
  event.response = event.response || {};
  event.response.claimsAndScopeOverrideDetails = {
    idTokenGeneration: { claimsToAddOrOverride: add },
    accessTokenGeneration: { claimsToAddOrOverride: add },
  };
  return event;
};
```

> Caveat for V2_0 access tokens: Cognito puts custom claims on the access token under
> `claimsToAddOrOverride`, but reserved access-token claims cannot be overridden. Re-verify the
> minted access token actually carries bare `org_id` before relying on it. V2_0 may require the pool
> to be on the **Essentials/Plus** feature tier (advanced security / token customization) — confirm
> in the console; the V1 path has no such requirement.

---

## 2. Deploy runbook

### 2a. Package + create the Lambda

```bash
# region of the shared pool
REGION=us-west-1
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
MSFG_ORG_ID=00000000-0000-0000-0000-0000000000aa   # MSFG org UUID (suite V3 seed)

mkdir -p /tmp/pretoken && cp index.mjs /tmp/pretoken/        # index.mjs = the V1 source above
( cd /tmp/pretoken && zip -r function.zip index.mjs )

# Execution role: basic Lambda logging is all the function needs (no AWS API calls inside it).
aws iam create-role --role-name MsfgPreTokenGenRole \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
aws iam attach-role-policy --role-name MsfgPreTokenGenRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

aws lambda create-function \
  --function-name msfg-cognito-pretoken-org-claim \
  --runtime nodejs20.x --handler index.handler \
  --role arn:aws:iam::${ACCOUNT_ID}:role/MsfgPreTokenGenRole \
  --environment "Variables={MSFG_ORG_ID=${MSFG_ORG_ID}}" \
  --zip-file fileb:///tmp/pretoken/function.zip \
  --region ${REGION}
```

### 2b. Let Cognito invoke it (resource-based permission)

```bash
POOL_ID=us-west-1_S6iE2uego
aws lambda add-permission \
  --function-name msfg-cognito-pretoken-org-claim \
  --statement-id CognitoPreTokenInvoke \
  --action lambda:InvokeFunction \
  --principal cognito-idp.amazonaws.com \
  --source-arn arn:aws:cognito-idp:${REGION}:${ACCOUNT_ID}:userpool/${POOL_ID} \
  --region ${REGION}
```

### 2c. Wire it as the pool's Pre-Token-Generation trigger

The pool is **not** defined in `mortgage-app/infra` CDK (no Cognito stack exists — only
`documents-stack.ts` + `iam-stack.ts`; the pool is managed manually/shared with the dashboard). So
this is a config change on an existing, unmanaged pool. Two options:

**Option A — AWS CLI (V1 trigger):**

```bash
# WARNING: update-user-pool overwrites unspecified settings to defaults. Snapshot first and
# re-supply existing policies/config, OR prefer the console (Option B) for safety on a shared pool.
aws cognito-idp describe-user-pool --user-pool-id ${POOL_ID} --region ${REGION} > /tmp/pool-before.json

aws cognito-idp update-user-pool \
  --user-pool-id ${POOL_ID} --region ${REGION} \
  --lambda-config "PreTokenGeneration=arn:aws:lambda:${REGION}:${ACCOUNT_ID}:function:msfg-cognito-pretoken-org-claim" \
  # ...plus EVERY currently-set field from /tmp/pool-before.json (policies, MFA, schema, etc.)
```

- For **V1**, set `LambdaConfig.PreTokenGeneration` (the simple ARN field above).
- For the **V2_0** variant, instead set `LambdaConfig.PreTokenGenerationConfig =
  {LambdaArn=..., LambdaVersion=V2_0}`.

**Option B — AWS Console (recommended for this shared, hand-managed pool):**
1. Cognito → User pools → `us-west-1_S6iE2uego` (region us-west-1).
2. **User pool properties** → **Lambda triggers** → **Add Lambda trigger**.
3. Trigger type: **Authentication** → **Pre token generation**.
4. **Trigger event version:** `Basic features` / `V1_0` (ID token) for the primary path. Choose
   `V2_0` only if you deploy the V2 variant for access-token customization.
5. Assign Lambda: `msfg-cognito-pretoken-org-claim`. Save.

### 2d. CDK snippet (matches mortgage-app's CDK style — for IF/WHEN the pool is brought into CDK)

The pool isn't in CDK today; if the owner later codifies it (a new `cognito-stack.ts` alongside
`documents-stack.ts`/`iam-stack.ts`), this is the idiomatic wiring. **Importing the existing pool by
id does NOT let CDK attach a trigger** (`fromUserPoolId` is read-only), so a managed pool must be a
CDK-owned `UserPool` (or use the escape hatch on the L1 `CfnUserPool.lambdaConfig`). Shown as the
target shape:

```ts
// infra/lib/cognito-stack.ts  (style mirrors iam-stack.ts: typed props, CfnOutput exports)
import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as cognito from 'aws-cdk-lib/aws-cognito';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as path from 'path';

export interface CognitoStackProps extends cdk.StackProps {
  envName: string;
  msfgOrgId: string; // 00000000-0000-0000-0000-0000000000aa
}

export class CognitoStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: CognitoStackProps) {
    super(scope, id, props);

    const preTokenFn = new lambda.Function(this, 'PreTokenGenFn', {
      functionName: `Msfg-${props.envName}-PreTokenGenOrgClaim`,
      runtime: lambda.Runtime.NODEJS_20_X,
      handler: 'index.handler',
      code: lambda.Code.fromAsset(path.join(__dirname, '../lambda/pretoken')),
      environment: { MSFG_ORG_ID: props.msfgOrgId },
    });

    // On a CDK-OWNED pool you would do:
    //   const pool = new cognito.UserPool(this, 'LosPool', {
    //     selfSignUpEnabled: true,
    //     lambdaTriggers: { preTokenGeneration: preTokenFn },  // V1 (ID token)
    //   });
    //   pool.addClient('SpaClient', { authFlows: { userPassword: true } });
    //
    // For V2_0 (access-token) use the L1 escape hatch on the CfnUserPool:
    //   const cfnPool = pool.node.defaultChild as cognito.CfnUserPool;
    //   cfnPool.lambdaConfig = {
    //     preTokenGenerationConfig: { lambdaArn: preTokenFn.functionArn, lambdaVersion: 'V2_0' },
    //   };

    new cdk.CfnOutput(this, 'PreTokenGenFnArn', { value: preTokenFn.functionArn });
  }
}
```

### 2e. App-client flags (already flagged in the program)

On the SPA app client (`34rg0vqoobfv8hhvg8kunkd738`) / pool:
- **`ALLOW_USER_PASSWORD_AUTH`** explicit-auth flow enabled (borrower/agent username+password
  sign-in). On the existing client, add it to "Authentication flows".
- **Self-service sign-up enabled** on the pool (Hosted UI `/signup` is already used —
  `frontend/src/auth/cognitoConfig.js:52 buildCognitoSignupUrl`). Borrowers self-register; the
  borrower auto-link in suite then runs on first `/api/me`, gated on `email_verified`.
- Ensure **`email`** is a returned/required attribute and **email verification is ON** so
  `email_verified=true` actually appears (suite's borrower auto-link depends on it).

### 2f. Shared-pool safety (legacy dashboard + mortgage-app)

The pool is shared with `dashboard.msfgco.com` and `mortgage-app`. Adding `org_id` is **additive**:
both other consumers read `cognito:groups`/`email` and ignore unknown claims
(`CognitoJwtConverter.java` in mortgage-app reads only `cognito:groups` + `email`). **Risk is the
*trigger wiring*, not the claim:**
- A pre-token-gen trigger runs on **every** token issuance for **every** client of the pool. A
  throwing/timing-out Lambda would break sign-in for the dashboard and mortgage-app too. The handler
  above never throws and returns the event unchanged on misconfig.
- **Do not** use `update-user-pool` blindly (it can reset unspecified fields) — prefer the console
  (Option B) on this hand-managed shared pool, or snapshot + re-supply all fields.
- After wiring, **sanity-check the dashboard and mortgage-app still sign in** before announcing.

---

## 3. How `org_id` is sourced

- **Now (MSFG-only):** constant. Env var `MSFG_ORG_ID = 00000000-0000-0000-0000-0000000000aa` (the
  MSFG org seeded in `app/.../db/migration/V3__multitenancy.sql:13`, also the suite dev org). Every
  user → MSFG org.
- **Future (multi-tenant):** replace the single `const orgId = ORG_ID;` line with a per-user lookup:
  either (a) a Cognito **custom attribute** `custom:org_id` set per user, read in the Lambda from
  `event.request.userAttributes['custom:org_id']` and re-emitted as the **bare** `org_id` claim
  (suite still only reads bare), or (b) an external lookup (DynamoDB/HTTP) keyed by sub/email. The
  Lambda is the single mapping point; **suite needs no change** — `JwtPrincipalAdapter.orgId()`
  already abstracts the read, and `OrgTenantResolver`/`@TenantId` consume whatever UUID arrives.

---

## 4. Verification steps

1. **Mint a real token** (mirror the SPA: ID token via auth-code, or quick check via password auth):
   ```bash
   aws cognito-idp initiate-auth --region us-west-1 \
     --auth-flow USER_PASSWORD_AUTH \
     --client-id 34rg0vqoobfv8hhvg8kunkd738 \
     --auth-parameters USERNAME=<test-borrower-email>,PASSWORD=<pw>
   ```
   (Requires `ALLOW_USER_PASSWORD_AUTH` on the client. Otherwise sign in via the Hosted UI and pull
   `id_token` from the `oidc.user:...` sessionStorage entry — same one `apiClient.js:23` reads.)

2. **Decode the ID token payload** (the token suite receives) and confirm the three claims:
   ```bash
   ID_TOKEN=<the IdToken from step 1>
   echo "$ID_TOKEN" | cut -d. -f2 | tr '_-' '/+' | base64 -D 2>/dev/null | python3 -m json.tool
   ```
   Expect, on the **ID token**:
   - `org_id` = `00000000-0000-0000-0000-0000000000aa` (a valid UUID, **bare** — not `custom:org_id`)
   - `cognito:groups` = e.g. `["Borrower"]` or `["RealEstateAgent"]` (array present)
   - `email_verified` = `true` (boolean) and `email` present

3. **Hit suite `/api/me` with that ID token:**
   ```bash
   curl -s -H "Authorization: Bearer $ID_TOKEN" https://<api-domain>/api/me | python3 -m json.tool
   ```
   - `200` with the user materialized → `org_id` accepted, role mapped, user_account created.
   - `401 "missing org_id claim"` → the Lambda is not attached, not firing on this client, or
     emitted `custom:org_id` instead of bare `org_id`.
   - For a borrower whose `email_verified=true` and email matches a borrower row, confirm the
     auto-link stamped (re-call `/api/me/loans`).

4. **Regression:** confirm `dashboard.msfgco.com` and `mortgage-app` still sign in (shared pool).

---

## 5. Open questions / risks for the owner

1. **AWS account / role to deploy from.** This artifact assumes the account that owns
   `us-west-1_S6iE2uego` (the same account referenced by `${this.account}` in `iam-stack.ts:109`).
   Confirm the deploy identity has `lambda:CreateFunction`, `cognito-idp:UpdateUserPool`,
   `iam:CreateRole`.
2. **Shared-pool trigger blast radius.** The trigger fires for ALL pool clients (dashboard +
   mortgage-app + SPA). The handler is defensive (never throws), but any future edit must preserve
   that. Consider whether a **separate dedicated LOS pool** (per
   `docs/cutover/cognito-deploy-seam.md`, which assumes a *new* pool) is preferable to mutating the
   shared legacy pool — this artifact targets the **shared** pool per the current task framing; the
   seam doc's long-term plan is a new pool. **Decide which.**
3. **Token version re-verify.** Confirmed today the SPA sends the **ID token** → V1 trigger is
   correct. If any consumer switches to the access token, move to the V2_0 variant (and check the
   pool's feature tier permits V2_0 token customization).
4. **`email_verified` reliability.** Suite's borrower auto-link refuses to link unless
   `email_verified=true`. Confirm the pool's email verification is enforced for self-sign-up
   borrowers, or borrowers will authenticate but never auto-link to their loan.
5. **Group naming drift.** Suite maps `Borrower`/`RealEstateAgent`/`LO`/`Processor`/`Admin`/`Manager`
   (case-sensitive) + exact `UNDERWRITER`/`CLOSER`/`PLATFORM_ADMIN`. The pool currently also has an
   `External` group (dropped by suite). Ensure borrower/agent self-sign-up actually places users into
   `Borrower`/`RealEstateAgent` (pool group-assignment / pre-sign-up or post-confirmation logic) —
   the org_id Lambda does not assign groups.
6. **Pool not in IaC.** The pool is hand-managed (not in `mortgage-app/infra` CDK). Wiring via console
   means the trigger is not captured in code. Track this as drift, or codify the pool into CDK
   (snippet in §2d) as part of Phase 6.
7. **CORS / issuer env on suite** must already be set for deployed envs:
   `COGNITO_ISSUER=https://cognito-idp.us-west-1.amazonaws.com/us-west-1_S6iE2uego` and
   `LOS_CORS_ALLOWED_ORIGINS` per `docs/cutover/cognito-deploy-seam.md`.
```
