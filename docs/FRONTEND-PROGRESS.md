# Backend Progress Report — for the Frontend Team

**Date:** 2026-06-11 · **Backend `main`:** `8242959` · **Tests:** 252 green · **Migrations:** through V13

> **TL;DR — regenerate once and swap three stubs.** The backend is running in the `local`
> profile at `http://localhost:8080` on the latest `main`. Run `npm run gen:api` against
> `http://localhost:8080/v3/api-docs` and you can swap `FeesProvider`, `CocProvider`, and the
> Document Manager / pre-approval stubs for real adapters. Zero screen changes expected — every
> change since your work-order has been additive.

---

## Module status

| Work-order item | Status | Swap your stub? |
|---|---|---|
| §3 Contract nits (pipeline, summary fields, `loanOfficerId`, transitions) | ✅ Shipped & live | n/a (already typed) |
| §1 Fees (line items + totals + invoices) | ✅ Shipped & live (+ upsert upgrade) | **Yes — `FeesProvider`** |
| §2 Change of Circumstance (draft / submit / history / decision) | ✅ Shipped & live | **Yes — `CocProvider`** |
| Document Manager (upload / list / download / delete) | ✅ Shipped & live | **Yes** |
| Pre-approval letter generation | ✅ Shipped & live (HTML letter; PDF later) | **Yes** |
| §4 Pricing/Lock | ⏳ Not built yet | No — keep static stub |
| §4 AUS (DU/LPA run + history) | ⏳ Not built yet | No — keep `LocalAusAdapter` |
| Disclosures | ⏳ Not built yet | No |

The full 1003 surface (borrowers/PII, employment & income, assets & liabilities, REO,
loan info, qualification calcs, declarations & HMDA) has been live since your last
regen — unchanged, still additive-only.

---

## Endpoint quick reference (new since the 1003)

Field-level detail lives in your `docs/HANDOFF-FROM-BACKEND.md` (dated reply sections) and the
generated client itself. All endpoints are tenant- + loan-scoped: cross-org → `404`, no token → `401`.

### Fees — `src/features/fees/model.ts` parity
- **`PUT /api/loans/{loanId}/fees` — recommended write path.** Upsert keyed by `(section,label)`
  (your Record key `${sectionId}:${label}`). Body `{section, label, amount, sellerConcession, percent?}`;
  creates or replaces in place (same `id`/`ordinal`), `200` both ways, idempotent.
- `POST | GET | PATCH | DELETE /api/loans/{loanId}/fees[/{feeId}]` — id-based CRUD also available
  (duplicate `section+label` on POST → `409`).
- `GET /api/loans/{loanId}/fees/totals` → `{ sectionTotals: {A…REC}, categoryTotals: { origination,
  didNotShop, didShop, taxesGov, escrowPrepaids } }` — server-computed, mirrors your formulas
  (`escrowPrepaids = F+G`), every section present (0 if empty).
- `GET | PUT /api/loans/{loanId}/fees/invoices` — invoice entries, upsert by `feeLabel`;
  the boolean JSON field is literally `"final"`.
- **Negative `amount`/`sellerConcession` are valid** (prorations / section-L credits). `percent ≥ 0`,
  `label` non-blank. Duplicate/race conflicts always surface as `409`, never 500.

### Change of Circumstance — `src/features/coc/cocModel.ts` parity
- `GET | PUT /api/loans/{loanId}/coc/draft` — 1:1 draft; GET before any save → `200` with empty arrays.
- `POST /api/loans/{loanId}/coc/submit` → `201` PENDING history entry and **clears the draft**.
  `reason` required → `400` with `fields.reason`.
- `GET /api/loans/{loanId}/coc/history` — newest-first.
- `POST /api/loans/{loanId}/coc/history/{entryId}/decision` `{decision: "ACCEPT"|"DENY"}` —
  UNDERWRITER/ADMIN only (else `403`); PENDING-only (else `409`); `decisionBy`/`decisionDate` set server-side.

### Document Manager
- `POST /api/loans/{loanId}/documents` — **multipart** (`file` + `documentType` + optional `category`).
  25 MB cap; empty file → `400`.
- `GET /api/loans/{loanId}/documents?type=` — paged metadata list (never loads bytes).
- `GET /api/loans/{loanId}/documents/{id}/content` — **binary download. This is the one endpoint that
  does NOT use the `ApiResponse` envelope** (raw bytes + content headers) — handle it specially in the client.
- `DELETE /api/loans/{loanId}/documents/{id}`.
- `POST /api/loans/{loanId}/documents/pre-approval` — generates + stores the pre-approval letter
  (templated HTML today, PDF generation later) and returns its metadata; fetch via the content endpoint.
- `GET /api/loans/{loanId}/documents?type=PRE_APPROVAL` — the paged list your pre-approval screen needs.

### Contract nits (§3) — recap
- `POST /api/loans` — `loanOfficerId` optional; defaults to the authenticated principal.
- `GET /api/loans` rows include `primaryBorrowerName`, `propertyCity`, `propertyState`, `updatedAt`.
- `GET /api/loans/{id}` includes `lienPriority`, `amortizationType`, `addressLine1/2`, `postalCode`, `estimatedValue`.
- `GET /api/loans/{id}/status/transitions` → `{currentStatus, allowedTransitions[]}` — **role-aware**;
  drive the status dropdown from it and you'll never offer a `403` target.

---

## Contract invariants (unchanged, guaranteed)
- `{ success, data }` envelope everywhere (except the binary document download, noted above);
  paged shape `{items, page, size, total, totalPages}`.
- Flat errors: `{ success:false, code, message, fields, timestamp }` with the usual
  `400/401/403/404` semantics (`404` for anything cross-tenant — existence never leaks).
- **Additive-only:** no shipped path or field has been renamed or removed, and that policy holds
  for everything still coming.
- `/v3/api-docs` is CI-guarded (`OpenApiDocsIT`), so client generation should never hit a broken spec.

## Known caveats (code around these for now)
1. **CoC decisions under real (non-`local`) auth:** the loan-access guard currently admits only
   ADMIN or the loan's own LO, so a true underwriter (different user than the LO) gets `403` on the
   decision endpoint. In the `local` profile everyone is the dev ADMIN, so your screens work fine
   today. A role-access-model fix is queued next on the backend — no FE change expected, it will
   just start returning `200` for real underwriters.
2. **Malformed enum values → `500` today** (e.g. `"decision":"ACCEPTED"` instead of `ACCEPT`,
   or a `CocReason` typo). Send exact enum constants from the generated types. A fix mapping these
   to `400 VALIDATION_ERROR` is already in flight.
3. **Invoice ⇄ document linking isn't wired yet.** Upload works, invoice rows work, but the field
   binding an invoice entry to an uploaded document comes with a later batch.

## What's next on the backend
**Pricing/Lock → AUS (DU/LPA behind ports, stub-first) → disclosures.** Next migration: V14.
We'll append a dated section to `docs/HANDOFF-FROM-BACKEND.md` as each lands, same as always,
and the local backend will be restarted on every merge so `gen:api` always reflects `main`.
