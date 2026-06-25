#!/usr/bin/env bash
# Create/update the PreSignUp Lambda + grant Cognito invoke + smoke-test it.
# This does NOT wire the pool trigger (that's wire-trigger.sh, the live change). OWNER-GATED.
# DRY-RUN by default (prints the plan). Pass --apply to execute.
set -euo pipefail
REGION=us-west-1
ACCT=116981808374
POOL=us-west-1_S6iE2uego
FN=msfg-cognito-presignup-autoconfirm
ROLE=arn:aws:iam::${ACCT}:role/MsfgPreTokenGenRole   # reused: basic logs only, no AWS calls
CLIENT=34rg0vqoobfv8hhvg8kunkd738                     # funnel/borrower app client
APPLY=false
[ "${1:-}" = "--apply" ] && APPLY=true
cd "$(dirname "$0")"

echo "▸ local handler test"; node test.mjs

if [ "$APPLY" != "true" ]; then
  echo; echo "DRY-RUN — would create/update Lambda $FN (role $ROLE, runtime nodejs20.x), add cognito invoke"
  echo "permission for pool $POOL, set FUNNEL_CLIENT_ID=$CLIENT, then invoke-test. Re-run with --apply."
  exit 0
fi

TMP=$(mktemp -d); cp index.mjs "$TMP/"; ( cd "$TMP" && zip -q fn.zip index.mjs )

if aws lambda get-function --function-name "$FN" --region "$REGION" >/dev/null 2>&1; then
  echo "▸ update existing $FN"
  aws lambda update-function-code --function-name "$FN" --zip-file "fileb://$TMP/fn.zip" --region "$REGION" \
    --query '{LastModified:LastModified}' --output table
  aws lambda update-function-configuration --function-name "$FN" --region "$REGION" \
    --environment "Variables={FUNNEL_CLIENT_ID=$CLIENT}" --query '{State:LastUpdateStatus}' --output table
else
  echo "▸ create $FN"
  aws lambda create-function --function-name "$FN" --runtime nodejs20.x --role "$ROLE" \
    --handler index.handler --architectures x86_64 --zip-file "fileb://$TMP/fn.zip" \
    --environment "Variables={FUNNEL_CLIENT_ID=$CLIENT}" --region "$REGION" \
    --query '{Arn:FunctionArn,State:State}' --output table
fi

echo "▸ grant Cognito invoke (idempotent)"
aws lambda add-permission --function-name "$FN" --statement-id cognito-presignup-invoke \
  --action lambda:InvokeFunction --principal cognito-idp.amazonaws.com \
  --source-arn "arn:aws:cognito-idp:${REGION}:${ACCT}:userpool/${POOL}" --region "$REGION" 2>/dev/null \
  && echo "  permission added" || echo "  permission already present (ok)"

echo "▸ invoke-test (funnel client → expect autoConfirmUser:true, autoVerifyEmail:true)"
cat > "$TMP/ev.json" <<JSON
{"triggerSource":"PreSignUp_SignUp","callerContext":{"clientId":"$CLIENT"},"request":{"userAttributes":{"email":"smoke@test.dev"}},"response":{}}
JSON
aws lambda invoke --function-name "$FN" --region "$REGION" \
  --payload "fileb://$TMP/ev.json" --cli-binary-format raw-in-base64-out "$TMP/out.json" >/dev/null
echo "  result: $(cat "$TMP/out.json")"
rm -rf "$TMP"
echo "Done. Next: wire-trigger.sh --apply to attach PreSignUp to the pool."
