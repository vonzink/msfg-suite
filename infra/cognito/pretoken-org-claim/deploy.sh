#!/usr/bin/env bash
# Deploy the pre-token-gen Lambda. OWNER-GATED — mutates the live auth pool's token minting.
set -euo pipefail
FN=msfg-cognito-pretoken-org-claim
REGION=us-west-1
cd "$(dirname "$0")"
TMP=$(mktemp -d)
cp index.mjs "$TMP/"
( cd "$TMP" && zip -q fn.zip index.mjs )
echo "Updating $FN in $REGION…"
aws lambda update-function-code --function-name "$FN" --region "$REGION" \
  --zip-file "fileb://$TMP/fn.zip" --query '{LastModified:LastModified,CodeSha256:CodeSha256}' --output table
rm -rf "$TMP"
echo "Done. Verify per README (fresh funnel user → id_token carries Borrower + org_id → /api/me 200)."
