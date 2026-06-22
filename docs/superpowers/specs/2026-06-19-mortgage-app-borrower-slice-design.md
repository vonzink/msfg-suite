# mortgage-app Borrower Slice — re-point onto msfg-suite (design)

**Date:** 2026-06-19
**Status:** ✅ Approved (owner) — first vertical slice of the mortgage-app→suite re-point
**Parent:** `docs/superpowers/specs/2026-06-18-unified-integration-architecture-design.md` (Phase A)

## Goal
Re-point the **borrower's two screens** in mortgage-app's React FE off mortgage-app's own backend and onto
**msfg-suite**, proving the cross-system seam (token → access model → CORS → envelope) with the smallest change.

## Scope
- **`/applications` (my-loans list)** → suite `GET /api/me/loans` (role-aware; borrower's own loans).
- **`/applications/:id` (loan view)** → suite `GET /api/loans/{id}` (summary) + `GET /api/loans/{id}/status/transitions`.
- **Deferred** (next slice): borrower's own 1003 *detail* (income/declarations) — needs a "my `borrowerId`"
  affordance in suite (the summary doesn't expose it). Out of scope here.

## Seam decisions
1. **FE → suite directly** (not via the future BFF).
2. **Token = the id_token the FE already sends** (carries `email` + `cognito:groups`). The Cognito pre-token
   Lambda injects `org_id` onto the token suite validates (id-vs-access confirmed by the parallel Cognito-prep);
   FE token usage unchanged.
3. **Thin adapter in `frontend/src/services/mortgageService.js`** maps suite envelopes
   (`{success,data}` / paged `{data:{items,total,page,pageSize}}`) → the shapes the components already expect
   (`{content,totalElements,totalPages,page,size}` / direct object), plus field renames
   (`primaryBorrowerName`→`borrowerName`, `loanNumber`→`applicationNumber`, …). **No component/route changes.**
4. **Config:** `REACT_APP_API_URL` → suite (`http://localhost:8080/api` locally). Suite CORS already allows
   `localhost:3000` (local) + `app.msfgco.com` (prod).
5. **No suite change** unless the field-map finds a borrower-view field genuinely missing (handled separately,
   under serialized suite control — borrower view needs a subset, so likely none).

## Testing / verification
- Adapter **unit tests** (jest): envelope + field mapping (list paged → `{content,…}`; detail unwrap; renames).
- `npm run build` compile check.
- Full borrower-**token** E2E (both apps live) is gated on the Cognito Lambda deploy; until then the re-point
  *mechanics* are verified against suite's `local` profile, and borrower *scoping* is already covered by Phase F tests.

## Out of scope
Borrower 1003-detail display; LO/agent screens; the BFF; document upload; writes. All later slices.
