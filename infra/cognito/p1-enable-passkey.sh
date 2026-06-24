#!/usr/bin/env bash
# P1 — enable passkeys (+ optional MFA) on the shared pool. OWNER-GATED PROD MUTATION.
#
# C1: add WEB_AUTHN to Policies.SignInPolicy.AllowedFirstAuthFactors (keep PASSWORD, EMAIL_OTP;
#     SMS_OTP stays OUT — deferred). This goes through update-user-pool, which RESETS any mutable
#     field you omit — so we read-modify-write the FULL current config (preserving LambdaConfig =
#     the org_id trigger, AdminCreateUserConfig, EmailConfiguration, etc.) and add only WEB_AUTHN.
# C2+C3: set-user-pool-mfa-config --mfa-configuration OFF + WebAuthnConfiguration
#     (RelyingPartyId=msfgco.com, UserVerification=required). This API only touches
#     MFA/WebAuthn config — it does NOT reset other pool fields.
#     LIVE-API NOTES (corrected vs the spec): UserVerification enum is {preferred,required}
#     (NOT "MULTI_FACTOR_WITH_USER_VERIFICATION"); and MfaConfiguration=OPTIONAL is REJECTED
#     unless a classic MFA method (SMS/Email/TOTP) is enabled — which we deliberately do NOT
#     want. So MFA stays OFF and the passkey is a strong FIRST factor (UV=required = biometric),
#     which IS the "no classic MFA" passwordless model. WebAuthnConfiguration is accepted with OFF.
#
# Default = DRY-RUN (prints the exact update JSON + planned MFA call, mutates nothing).
# Pass --apply to: back up the full pool config → apply C1 → apply C2/C3 → verify.
set -euo pipefail
REGION=us-west-1
POOL=us-west-1_S6iE2uego
RP_ID=msfgco.com
APPLY=false
[ "${1:-}" = "--apply" ] && APPLY=true
cd "$(dirname "$0")"

echo "▸ describe-user-pool (read-only)…"
aws cognito-idp describe-user-pool --user-pool-id "$POOL" --region "$REGION" > /tmp/p1-pool.json

# Build the update-user-pool input: keep ONLY the mutable fields update accepts, rename Id→UserPoolId,
# add WEB_AUTHN. Drop read-only/create-only fields. Prefer VerificationMessageTemplate over the legacy
# Email/SmsVerification* trio (they conflict). Idempotent: WEB_AUTHN added only if absent.
python3 - <<'PY' > /tmp/p1-update.json
import json
pool = json.load(open('/tmp/p1-pool.json'))['UserPool']
ALLOWED = ['Policies','DeletionProtection','LambdaConfig','AutoVerifiedAttributes',
           'VerificationMessageTemplate','SmsAuthenticationMessage','UserAttributeUpdateSettings',
           'MfaConfiguration','DeviceConfiguration','EmailConfiguration','SmsConfiguration',
           'UserPoolTags','AdminCreateUserConfig','UserPoolAddOns','AccountRecoverySetting']
out = {k: pool[k] for k in ALLOWED if k in pool}
out['UserPoolId'] = pool['Id']
# legacy verification fields conflict with VerificationMessageTemplate — only include if no template
if 'VerificationMessageTemplate' not in out:
    for k in ('EmailVerificationMessage','EmailVerificationSubject','SmsVerificationMessage'):
        if k in pool: out[k] = pool[k]
pol = out.setdefault('Policies', {})
sip = pol.setdefault('SignInPolicy', {})
factors = sip.get('AllowedFirstAuthFactors') or []
if 'WEB_AUTHN' not in factors:
    factors = factors + ['WEB_AUTHN']
sip['AllowedFirstAuthFactors'] = factors
json.dump(out, open('/tmp/p1-update.json','w'), indent=2)
print(json.dumps(out, indent=2))
PY

echo
echo "── C1 update-user-pool input (preserves LambdaConfig etc.; adds WEB_AUTHN) ──"
python3 -c "import json;d=json.load(open('/tmp/p1-update.json'));print('AllowedFirstAuthFactors:',d['Policies']['SignInPolicy']['AllowedFirstAuthFactors']);print('LambdaConfig.PreTokenGeneration:',d.get('LambdaConfig',{}).get('PreTokenGeneration','MISSING!'));print('LambdaConfig.PostConfirmation:',d.get('LambdaConfig',{}).get('PostConfirmation','MISSING!'))"
echo "── C2/C3 set-user-pool-mfa-config: --mfa-configuration OFF --web-authn-configuration RelyingPartyId=$RP_ID,UserVerification=required ──"

if [ "$APPLY" != "true" ]; then
  echo
  echo "DRY-RUN ONLY — nothing changed. Full update JSON at /tmp/p1-update.json. Re-run with --apply to execute."
  exit 0
fi

TS=$(date +%Y%m%dT%H%M%S)
mkdir -p backups
cp /tmp/p1-pool.json "backups/pool-${TS}.json"
echo "▸ backup saved: backups/pool-${TS}.json"

echo "▸ C1: update-user-pool (add WEB_AUTHN, preserve all)…"
aws cognito-idp update-user-pool --region "$REGION" --cli-input-json file:///tmp/p1-update.json

echo "▸ C2/C3: set-user-pool-mfa-config (OPTIONAL + WebAuthn RP=$RP_ID)…"
aws cognito-idp set-user-pool-mfa-config --region "$REGION" --user-pool-id "$POOL" \
  --mfa-configuration OFF \
  --web-authn-configuration "RelyingPartyId=$RP_ID,UserVerification=required"

echo "▸ VERIFY…"
aws cognito-idp describe-user-pool --user-pool-id "$POOL" --region "$REGION" \
  --query 'UserPool.{Factors:Policies.SignInPolicy.AllowedFirstAuthFactors,PreToken:LambdaConfig.PreTokenGeneration,PostConfirm:LambdaConfig.PostConfirmation}' --output json
aws cognito-idp get-user-pool-mfa-config --user-pool-id "$POOL" --region "$REGION" --output json
echo "Done. Expect: Factors include WEB_AUTHN; both Lambdas intact; MfaConfiguration=OFF; WebAuthnConfiguration{RelyingPartyId=msfgco.com,UserVerification=required}."
