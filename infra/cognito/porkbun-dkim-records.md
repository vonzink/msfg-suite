# SES DKIM records for msfgco.com — add these in PORKBUN

`msfgco.com` DNS is authoritative at **Porkbun** (`curitiba.ns.porkbun.com`, …), NOT Route 53.
The earlier records were added to a non-authoritative Route 53 zone and have been removed.
Add the 3 CNAMEs below in Porkbun → **Domain Management → msfgco.com → DNS Records**.

SES is in **Easy DKIM** mode; once these resolve, the `msfgco.com` identity auto-verifies
(minutes–hours), `VerifiedForSendingStatus` flips to true, and the pool can switch to SES.

## The 3 records (Type = CNAME)

| Host (Porkbun "Host" field) | Answer / Target | TTL |
|---|---|---|
| `l6q4v6fnkmkz76wcz7gketyk7xhgr6al._domainkey` | `l6q4v6fnkmkz76wcz7gketyk7xhgr6al.dkim.amazonses.com` | 600 |
| `of6fqx723odllnkqoe6y5mjepadwjeyi._domainkey` | `of6fqx723odllnkqoe6y5mjepadwjeyi.dkim.amazonses.com` | 600 |
| `5pskwrpd6iooq3w3bdypwm3wuqxhqfij._domainkey` | `5pskwrpd6iooq3w3bdypwm3wuqxhqfij.dkim.amazonses.com` | 600 |

> In Porkbun, "Host" is the subdomain only (it appends `.msfgco.com`). Do NOT include the domain
> in the Host field. Type = CNAME, Answer = the `*.dkim.amazonses.com` value.

## Or via the Porkbun API (if you give me apikey + secretapikey)

```bash
for t in l6q4v6fnkmkz76wcz7gketyk7xhgr6al of6fqx723odllnkqoe6y5mjepadwjeyi 5pskwrpd6iooq3w3bdypwm3wuqxhqfij; do
  curl -s -X POST https://api.porkbun.com/api/json/v3/dns/create/msfgco.com \
    -H 'Content-Type: application/json' \
    -d "{\"apikey\":\"$PORKBUN_API_KEY\",\"secretapikey\":\"$PORKBUN_SECRET\",\"type\":\"CNAME\",\"name\":\"${t}._domainkey\",\"content\":\"${t}.dkim.amazonses.com\",\"ttl\":\"600\"}"
  echo
done
```

## After they're added
1. Verify: `aws sesv2 get-email-identity --email-identity msfgco.com --region us-west-1 --query VerifiedForSendingStatus` → `True`.
2. Once SES production access is also granted (sandbox exit, ~24h), run:
   `infra/cognito/switch-email-to-ses.sh --apply` (self-gates on both conditions).
3. Then OTP codes land in the inbox instead of spam.
