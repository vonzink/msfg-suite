# Deployed Cognito Seam (design — provisioned in Phase 6, not now)

The **new dedicated LOS Cognito user pool** that backs MSFG-suite + msfg-suite-web in deployed
(non-`local`) environments. Local dev needs none (dev auto-auth on org `…aa`).

## Decisions
- **New pool**, isolated from `mortgage-app`'s `us-west-1_S6iE2uego`. Staff users (re)created in it.
- **Groups** named EXACTLY (case-sensitive — backend `CognitoRolesConverter` exact-matches the `Role` enum,
  frontend `Role` union matches the same): `LO`, `PROCESSOR`, `UNDERWRITER`, `CLOSER`, `ADMIN`, `PLATFORM_ADMIN`.
- **`org_id` claim**: a **pre-token-generation Lambda** injects the **bare** `org_id` claim (not `custom:org_id`).
  - Backend reads bare `org_id` (`TenantContextFilter`, and now the `OrgScopedJwtAuthenticationConverter` which
    REQUIRES it — Phase 0). Frontend reads `org_id` then falls back to `custom:org_id`.
  - The bare claim only appears via the Lambda — a Cognito custom attribute surfaces as `custom:org_id`, which the
    backend does NOT read. So the Lambda is required; map user→org (all users → MSFG org
    `00000000-0000-0000-0000-0000000000aa` initially; the lookup generalizes for future tenants).

## Token shape — RISK TO VALIDATE at first provision (Phase 6)
- The SPA sends a bearer token via `authProvider.getToken()` (oidc-client-ts). Confirm whether that is the **id**
  or **access** token, because the pre-token-gen Lambda must inject `org_id` + `cognito:groups` into the token the
  SPA actually sends. `cognito:groups` is native to both id and access tokens; **custom claims via the V2 access-token
  trigger** are needed if the access token is the bearer. Backend validates by signature/issuer (`COGNITO_ISSUER`),
  not audience, so either token validates — but the **claims must be present in the sent token**.
- Action at provision: mint a real token, inspect it for bare `org_id` + `cognito:groups`, confirm the backend's
  `OrgScopedJwtAuthenticationConverter` accepts it (Phase 0 fail-closed converter) and `TenantContextFilter` binds the tenant.

## Env wiring (deploy)
- Backend: `COGNITO_ISSUER=https://cognito-idp.{region}.amazonaws.com/{userPoolId}` ·
  `LOS_CORS_ALLOWED_ORIGINS=https://{spa-domain}` · datasource as the **non-owner** `app_user` role (engages RLS).
- Frontend `config.json` (uploaded per-env by `scripts/deploy.sh`): `authMode:"cognito"`, `apiBaseUrl:"https://{api-domain}"`,
  `cognito.{authority,clientId,domain,redirectUri,logoutUri,scopes}`. CDK `MsfgLosWebSpaStack` takes `cognitoDomain` (CSP) +
  `apiOrigin` as context; it does NOT create the pool.

## Out of scope here
Pool/Lambda provisioning, user migration, DNS — all Phase 6.
