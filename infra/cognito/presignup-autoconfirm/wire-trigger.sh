#!/usr/bin/env bash
# Attach the PreSignUp Lambda to the shared pool's LambdaConfig WITHOUT disturbing the existing
# PostConfirmation + PreTokenGeneration triggers. OWNER-GATED PROD MUTATION (fires on every funnel
# sign-up). update-user-pool RESETS omitted mutable fields, so we read-modify-write the FULL config
# (same allow-list as p1-enable-passkey.sh) and only add LambdaConfig.PreSignUp.
# DRY-RUN by default; --apply backs up the full pool config, wires, and verifies. --unwire removes it.
set -euo pipefail
REGION=us-west-1
ACCT=116981808374
POOL=us-west-1_S6iE2uego
PRESIGNUP_ARN=arn:aws:lambda:${REGION}:${ACCT}:function:msfg-cognito-presignup-autoconfirm
MODE=dryrun
case "${1:-}" in --apply) MODE=apply;; --unwire) MODE=unwire;; esac
cd "$(dirname "$0")"

echo "▸ verify the Lambda exists"
aws lambda get-function --function-name msfg-cognito-presignup-autoconfirm --region "$REGION" \
  --query '{State:Configuration.State}' --output text >/dev/null || { echo "✗ Lambda missing — run deploy.sh --apply first"; exit 1; }

echo "▸ describe-user-pool (read-only)…"
aws cognito-idp describe-user-pool --user-pool-id "$POOL" --region "$REGION" > /tmp/presignup-pool.json

PRESIGNUP_ARN="$PRESIGNUP_ARN" MODE="$MODE" python3 - <<'PY'
import json, os
pool = json.load(open('/tmp/presignup-pool.json'))['UserPool']
ALLOWED = ['Policies','DeletionProtection','LambdaConfig','AutoVerifiedAttributes',
           'VerificationMessageTemplate','SmsAuthenticationMessage','UserAttributeUpdateSettings',
           'MfaConfiguration','DeviceConfiguration','EmailConfiguration','SmsConfiguration',
           'UserPoolTags','AdminCreateUserConfig','UserPoolAddOns','AccountRecoverySetting']
out = {k: pool[k] for k in ALLOWED if k in pool}
out['UserPoolId'] = pool['Id']
if 'VerificationMessageTemplate' not in out:
    for k in ('EmailVerificationMessage','EmailVerificationSubject','SmsVerificationMessage'):
        if k in pool: out[k] = pool[k]
lc = dict(out.get('LambdaConfig') or {})
if os.environ['MODE'] == 'unwire':
    lc.pop('PreSignUp', None)
else:
    lc['PreSignUp'] = os.environ['PRESIGNUP_ARN']
out['LambdaConfig'] = lc
json.dump(out, open('/tmp/presignup-update.json','w'), indent=2)
print('LambdaConfig that will be written:')
print(json.dumps(lc, indent=2))
PY

echo
if [ "$MODE" = "dryrun" ]; then
  echo "DRY-RUN — nothing changed. Update JSON at /tmp/presignup-update.json. Re-run with --apply (or --unwire)."
  exit 0
fi

TS=$(date +%Y%m%dT%H%M%S); mkdir -p ../backups; cp /tmp/presignup-pool.json "../backups/pool-${TS}.json"
echo "▸ backup: ../backups/pool-${TS}.json"
echo "▸ update-user-pool ($MODE PreSignUp, preserve all else)…"
aws cognito-idp update-user-pool --region "$REGION" --cli-input-json file:///tmp/presignup-update.json
echo "▸ VERIFY LambdaConfig…"
aws cognito-idp describe-user-pool --user-pool-id "$POOL" --region "$REGION" --query 'UserPool.LambdaConfig' --output json
echo "Expect PreSignUp present (apply) or absent (unwire); PostConfirmation + PreTokenGeneration intact."
