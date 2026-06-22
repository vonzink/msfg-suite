# Local end-to-end funnel walk (msfg.us → mortgage-app → msfg-suite)

Runs the whole borrower funnel locally with **no AWS**, using the dev-header identity bridge.
Built by the `feat/local-e2e-funnel-harness` work (suite) + `feat/borrower-suite-repoint-slice` (mortgage-app).

**Shared dev constants** (identical across all apps):
- Dev org id: `00000000-0000-0000-0000-0000000000aa`
- Dev borrower sub: `00000000-0000-0000-0000-0000000000b0`
- Dev borrower email: `borrower@dev.local`
- Default loan officer (suite intake): `00000000-0000-0000-0000-000000000001`

## Ports
| App | URL | DB |
|---|---|---|
| msfg-suite (system of record) | http://localhost:8080 (Swagger `/swagger-ui.html`) | Postgres :5432 |
| mortgage-app backend | http://localhost:8081/api | H2 in-mem (`dev` profile) |
| mortgage-app FE (CRA) | http://localhost:3001 | — |
| msfg.us (Next.js) | http://localhost:3000 | Postgres :5434 |
| msfg-rag (optional) | http://localhost:8090 | Postgres :5433 |

DB ports never collide (5432 / 5433 / 5434).

## Environment (the `.env` files are gitignored — set these on disk)

**`mortgage-app/frontend/.env`**
```
PORT=3001
REACT_APP_API_URL=http://localhost:8080/api
REACT_APP_DEV_SUB=00000000-0000-0000-0000-0000000000b0
REACT_APP_DEV_ROLES=Borrower
REACT_APP_DEV_ORG=00000000-0000-0000-0000-0000000000aa
```

**`mortgage-app/backend`** — runs under the `dev,local` profiles; `application-local.properties` already sets
`suite.api.base-url`, `suite.dev.*`, and excludes the OAuth2 resource-server autoconfig (no Cognito locally).

**`msfg.us/.env.local`**
```
DATABASE_URL=postgresql://dev:dev@localhost:5434/msfg_web?schema=public
LOS_API_BASE=http://localhost:8081
NEXT_PUBLIC_APP_URL=http://localhost:3001
```
(`LOS_API_BASE` has no trailing `/api` — msfg.us appends `/api/loan-applications/intake`.)

## Boot order
1. **suite** — `cd ~/MSFG/msfg-suite && docker compose up -d && ./gradlew :app:bootRun --args='--spring.profiles.active=local'`
2. **mortgage-app backend** — `cd ~/MSFG/WebProjects/mortgage-app/backend && SPRING_PROFILES_ACTIVE=dev,local mvn spring-boot:run`
3. **mortgage-app FE** — `cd ~/MSFG/WebProjects/mortgage-app/frontend && npm install && npm start` (serves :3001)
4. **msfg.us** — `cd ~/MSFG/WebProjects/msfg.us && npm run db:up && npm run db:migrate && npm run dev` (serves :3000)
5. *(optional)* **msfg-rag** — see `msfg-rag/README.md` (needs `ANTHROPIC_API_KEY` + `OPENAI_API_KEY`); off the critical funnel path.

## Smoke test (no browser) — proves the keystone data flow
```bash
# 1) Create a loan in suite AS the dev borrower:
curl -s -X POST localhost:8080/api/loans/intake \
  -H 'Content-Type: application/json' \
  -H 'X-Dev-Sub: 00000000-0000-0000-0000-0000000000b0' \
  -H 'X-Dev-Roles: Borrower' -H 'X-Dev-Org: 00000000-0000-0000-0000-0000000000aa' \
  -d '{"sourceLeadId":"smoke-1","loanPurpose":"PURCHASE",
       "borrower":{"firstName":"Ann","lastName":"Buyer","email":"borrower@dev.local","phone":"555-0100"},
       "property":{"addressLine1":"1 Main St","city":"Denver","state":"CO","postalCode":"80202","estimatedValue":350000}}'
# → {"success":true,"data":{"loanId":"<uuid>","loanNumber":"..."}}

# 2) Read it back as the borrower (borrower-scoped via the borrower_party.user_id link):
curl -s 'localhost:8080/api/me/loans?page=0&size=10' \
  -H 'X-Dev-Sub: 00000000-0000-0000-0000-0000000000b0' -H 'X-Dev-Roles: Borrower' \
  -H 'X-Dev-Org: 00000000-0000-0000-0000-0000000000aa'
# → data.items[] includes the loan above.

# 3) Idempotency: re-run step 1 with the SAME sourceLeadId → same loanId (no duplicate loan).
```

## Browser walk
1. http://localhost:3000 (msfg.us) → run the apply wizard to the finish step.
2. The finish step posts the lead; the msfg.us server hand-off calls mortgage-app
   `:8081/api/loan-applications/intake`, which (via `SuiteClient`) creates the loan in **suite** and
   returns the **suite loan id**; the browser deep-links to `:3001/applications/<suiteLoanId>`.
3. The mortgage-app FE detail screen loads `GET :8080/api/loans/<suiteLoanId>` (from suite, with the FE's
   dev headers) — the loan shows.
4. `:3001/applications` (list) → `GET :8080/api/me/loans` → the loan is listed for the borrower.
5. Staff console (msfg-suite-web, run against suite :8080 as ADMIN — i.e. no dev headers) → the loan is visible.

## How identity works locally (no Cognito)
- suite `local` profile: `LocalDevSecurityConfig` honors `X-Dev-Sub` / `X-Dev-Roles` / `X-Dev-Org` headers
  (absent → dev ADMIN). It builds a synthetic principal through the real role-allowlist, so Phase F
  borrower scoping (`borrower_party.user_id` → `/me/loans`) genuinely applies.
- mortgage-app `local` profile: `LocalSecurityConfig` runs every request as the fixed dev borrower
  (`…00b0`). Its `SuiteClient` forwards `suite.dev.*` as `X-Dev-*` headers, so the loan suite creates is
  linked to the SAME borrower sub the FE uses → the borrower sees it in `/me/loans`.
- **Security:** the dev-header bridge and the mortgage-app local chain are `@Profile("local")` ONLY; never
  active in dev/prod (those validate real Cognito JWTs). Never enable the `local` profile in a deployed env.

## Still gated / not part of this pass
- **Real Cognito token E2E** — depends on the owner's `org_id` pre-token-generation Lambda; until then the
  walk uses the dev-header bridge.
- **Deploy / DNS** (`api.msfgco.com`, `los.msfgco.com`) — Phase C, human-gated.
- **msfg-rag corpus** — the AI chat degrades to "unavailable" without a populated pgvector corpus.

## Production follow-ups surfaced during the build (do before real traffic)
- **Intake default loan officer:** suite `/api/loans/intake` stamps `los.intake.default-loan-officer-id`
  (defaults to the dev admin `…0001`). In prod, funnel loans land with an officer id that matches no real
  user → they appear in no LO's owner-scoped pipeline until reassigned (org-wide roles still see them). Set
  the property per env and/or add an assignment step. (Design doc lists prod LO-resolution as open.)
- **Suite call inside the intake transaction:** mortgage-app `createFromIntake` makes the blocking
  `SuiteClient` HTTP call inside the `@Transactional` boundary — fine locally, but at scale holds a DB
  connection for the call duration. Move the suite call outside the tx (or shorten the tx) before prod load.
- **No retry on a failed suite hand-off:** if the first intake's suite call fails, the local row keeps
  `suite_loan_id = null` and is never retried (the idempotent re-entry returns the existing local row). Add
  an async re-drive over null `suite_loan_id` rows before prod.
