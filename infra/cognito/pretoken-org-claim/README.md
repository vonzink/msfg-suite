# Cognito Pre-Token-Generation Lambda — `msfg-cognito-pretoken-org-claim`

Source of truth for the pool `us-west-1_S6iE2uego` (acct `116981808374`, `us-west-1`) Pre Token
Generation V1_0 trigger. Previously untracked-in-AWS; version-controlled here as of the G8 fix.

- **Runtime:** `nodejs20.x` · **Handler:** `index.handler` · **Role:** `MsfgPreTokenGenRole`
- **Env:** `MSFG_ORG_ID` (required, the tenant UUID — currently `…00aa`) · `DEFAULT_GROUP` (optional, default `Borrower`)

## What it does
1. Injects a constant `org_id` claim (the suite fail-closes 401 on a missing/!UUID `org_id`). V1 ⇒
   **id token only**; the FE sends the id_token as bearer, so this is sufficient (see spec §4.2 for the
   V1→V2 option to also stamp the access token).
2. **G8 fix:** defaults any **group-less** user to `Borrower` *in the token* on every mint. Passwordless
   EMAIL_OTP auto-confirm does not reliably fire PostConfirmation, so the `Borrower` group is often never
   assigned → `cognito:groups` empty → the suite 403s the role-less token at the SecurityConfig filter,
   before `/api/me`. Defaulting here is the only place that can put the role into the token. Users with
   ANY group are untouched (never elevates/relabels staff). Pure claim override — no `AdminAddUserToGroup`,
   so no new IAM.

## Test
```
node test.mjs        # pure-logic, no AWS, no deps
```

## Deploy  ⚠️ OWNER-GATED PROD MUTATION (modifies the live auth pool)
```
./deploy.sh          # zips index.mjs + aws lambda update-function-code
```
Then **verify** (mirrors the proven token test): sign up a fresh funnel user, mint a token WITHOUT
manually adding any group, and confirm the id_token now carries `cognito:groups:["Borrower"]` + `org_id`,
and `GET https://los.msfgco.com/api/me` returns **200** with `role=BORROWER` (was 403 pre-fix).
