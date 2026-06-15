# Cutover Program — make MSFG-suite the single backend

Replacing `mortgage-app` (`/Users/zacharyzink/MSFG/WebProjects/mortgage-app`) with **MSFG-suite**
(this repo, backend) + **msfg-suite-web** (`/Users/zacharyzink/MSFG/msfg-suite-web`, frontend).
Full cutover · greenfield (no data migration) · build-alongside, flip at the end.

## Repo ownership
This program drives **both** repos directly (the old "backend session never edits frontend" rule is retired
for cutover work). Branch convention: `cutover/phase-N-*` per phase, in each repo.

## Documents
- Roadmap + Phase 0 design: `docs/superpowers/specs/2026-06-15-cutover-phase-0-foundation-design.md`
- Phase 0 plan: `docs/superpowers/plans/2026-06-15-cutover-phase-0-foundation.md`
- Parity definition-of-done: `docs/cutover/PARITY-CHECKLIST.md`
- Deploy-auth design: `docs/cutover/cognito-deploy-seam.md`
- Phase 0 D1 evidence: `docs/cutover/phase-0-e2e-evidence.md`

## Local run recipe
```bash
# Backend — /Users/zacharyzink/MSFG/msfg-suite
docker compose up -d
./gradlew :app:bootRun --args='--spring.profiles.active=local'
# → http://localhost:8080 · OpenAPI /v3/api-docs · Swagger /swagger-ui.html · dev ADMIN auto-auth (org …aa)

# Frontend — /Users/zacharyzink/MSFG/msfg-suite-web
nvm use && npm install
npm run gen:api      # regen typed client against the running backend
npm run dev          # → http://localhost:5173 (VITE_AUTH_MODE=local, base http://localhost:8080)
```
`.env.local` (`VITE_AUTH_MODE=local`, `VITE_API_BASE_URL=http://localhost:8080`) and `public/config.json`
(`authMode:"local"`) already ship local-ready. Backend local CORS allows `http://localhost:5173` + `:3000`.

### Port conflicts (observed locally)
The defaults (backend 8080, FE 5173) can collide with other local apps. To run on alternate ports:
- Backend: `./gradlew :app:bootRun --args='--spring.profiles.active=local --server.port=18080'`
- Frontend: point it at the backend and pick a CORS-allowed origin —
  `VITE_API_BASE_URL=http://localhost:18080 npm run dev -- --port 3000 --strictPort`
  (backend local CORS allows `:3000`), and regen the client against that port:
  `./node_modules/.bin/openapi-typescript http://localhost:18080/v3/api-docs -o src/lib/api/schema.d.ts`.

## Phase status
- ✅ Phase 0 — Foundation & seam: org_id fail-closed converter + filter hardening + enveloped 401
  entry point (8 tests), local create→pipeline→open verified, parity checklist + deploy-auth design written.
- Phases 1–6: see the roadmap table in the Phase 0 design doc.
