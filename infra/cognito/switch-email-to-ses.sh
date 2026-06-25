#!/usr/bin/env bash
# Switch the shared Cognito pool's email sender from COGNITO_DEFAULT → Amazon SES.
# OWNER-GATED PROD MUTATION. Fixes OTP deliverability (COGNITO_DEFAULT lands in spam +
# 50/day shared cap). Mirrors the read-modify-write pattern of p1-enable-passkey.sh.
#
# EmailConfiguration becomes:
#   { EmailSendingAccount=DEVELOPER, SourceArn=<verified SES identity msfgco.com>, From="..." }
# update-user-pool RESETS any mutable field you omit, so we read the FULL current config and
# replay it (preserving LambdaConfig = the org_id pre-token trigger, the WEB_AUTHN/EMAIL_OTP
# first factors from P1, AdminCreateUserConfig, etc.), changing ONLY EmailConfiguration.
#
# SELF-GATED: refuses to --apply until BOTH preconditions hold, so it's safe to run anytime:
#   1. SES domain identity msfgco.com is DKIM-verified (VerifiedForSendingStatus=true), and
#   2. The SES account is OUT of the sandbox (ProductionAccessEnabled=true) — otherwise SES
#      only delivers to verified recipients, so real borrowers would get NO code (worse than
#      COGNITO_DEFAULT→spam). Override the sandbox gate with --allow-sandbox ONLY for testing
#      to already-verified recipient addresses.
#
# Default = DRY-RUN (prints the planned EmailConfiguration + the update JSON, mutates nothing).
# Pass --apply to back up the full pool config → switch → verify.
set -euo pipefail
REGION=us-west-1
POOL=us-west-1_S6iE2uego
ACCOUNT=116981808374
IDENTITY=msfgco.com
FROM="MSFG <no-reply@msfgco.com>"
SOURCE_ARN="arn:aws:ses:${REGION}:${ACCOUNT}:identity/${IDENTITY}"
APPLY=false
ALLOW_SANDBOX=false
for a in "$@"; do case "$a" in
  --apply) APPLY=true;;
  --allow-sandbox) ALLOW_SANDBOX=true;;
  -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0;;
  *) echo "Unknown option: $a (see --help)"; exit 1;;
esac; done
cd "$(dirname "$0")"

echo "▸ Preconditions…"
DKIM=$(aws sesv2 get-email-identity --email-identity "$IDENTITY" --region "$REGION" \
  --query 'VerifiedForSendingStatus' --output text 2>/dev/null || echo "ERR")
PROD=$(aws sesv2 get-account --region "$REGION" --query 'ProductionAccessEnabled' --output text 2>/dev/null || echo "ERR")
echo "   SES identity ${IDENTITY} verified : ${DKIM}"
echo "   SES production access (no sandbox): ${PROD}"

gate_ok=true
[ "$DKIM" = "True" ] || { echo "   ✗ identity not DKIM-verified yet"; gate_ok=false; }
if [ "$PROD" != "True" ] && [ "$ALLOW_SANDBOX" != "true" ]; then
  echo "   ✗ still in SES sandbox (pass --allow-sandbox to override for verified-recipient testing)"; gate_ok=false
fi

echo "▸ describe-user-pool (read-only)…"
aws cognito-idp describe-user-pool --user-pool-id "$POOL" --region "$REGION" > /tmp/ses-pool.json

# Build update input: keep ONLY mutable fields update accepts (same allow-list as the proven
# P1 script), rename Id→UserPoolId, swap EmailConfiguration to SES. Idempotent.
SOURCE_ARN="$SOURCE_ARN" FROM="$FROM" python3 - <<'PY'
import json, os
pool = json.load(open('/tmp/ses-pool.json'))['UserPool']
ALLOWED = ['Policies','DeletionProtection','LambdaConfig','AutoVerifiedAttributes',
           'VerificationMessageTemplate','SmsAuthenticationMessage','UserAttributeUpdateSettings',
           'MfaConfiguration','DeviceConfiguration','EmailConfiguration','SmsConfiguration',
           'UserPoolTags','AdminCreateUserConfig','UserPoolAddOns','AccountRecoverySetting']
out = {k: pool[k] for k in ALLOWED if k in pool}
out['UserPoolId'] = pool['Id']
if 'VerificationMessageTemplate' not in out:
    for k in ('EmailVerificationMessage','EmailVerificationSubject','SmsVerificationMessage'):
        if k in pool: out[k] = pool[k]
out['EmailConfiguration'] = {
    'EmailSendingAccount': 'DEVELOPER',
    'SourceArn': os.environ['SOURCE_ARN'],
    'From': os.environ['FROM'],
}
json.dump(out, open('/tmp/ses-update.json','w'), indent=2)
print(json.dumps(out['EmailConfiguration'], indent=2))
print('PreTokenGeneration:', out.get('LambdaConfig',{}).get('PreTokenGeneration','MISSING!'))
print('AllowedFirstAuthFactors:', out.get('Policies',{}).get('SignInPolicy',{}).get('AllowedFirstAuthFactors'))
PY

echo
echo "── planned EmailConfiguration (above) — LambdaConfig + factors preserved ──"

if [ "$APPLY" != "true" ]; then
  echo
  echo "DRY-RUN ONLY — nothing changed. Update JSON at /tmp/ses-update.json. Re-run with --apply."
  [ "$gate_ok" = true ] && echo "Preconditions MET — safe to --apply." || echo "Preconditions NOT met — --apply would abort."
  exit 0
fi

[ "$gate_ok" = true ] || { echo "✗ ABORT: preconditions not met (see above)."; exit 1; }

TS=$(date +%Y%m%dT%H%M%S)
mkdir -p backups
cp /tmp/ses-pool.json "backups/pool-${TS}.json"
echo "▸ backup saved: backups/pool-${TS}.json"

echo "▸ update-user-pool (EmailConfiguration → SES, preserve all)…"
aws cognito-idp update-user-pool --region "$REGION" --cli-input-json file:///tmp/ses-update.json

echo "▸ VERIFY…"
aws cognito-idp describe-user-pool --user-pool-id "$POOL" --region "$REGION" \
  --query 'UserPool.{Email:EmailConfiguration,Factors:Policies.SignInPolicy.AllowedFirstAuthFactors,PreToken:LambdaConfig.PreTokenGeneration}' --output json
echo "Done. Expect EmailSendingAccount=DEVELOPER + SourceArn=${SOURCE_ARN}; factors + Lambda intact."
echo "Now run the live flow (funnel → /continue → Email me a code) and confirm the code arrives in the INBOX."
