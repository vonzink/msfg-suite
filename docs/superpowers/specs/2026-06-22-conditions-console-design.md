# Underwriting Conditions in the staff console (msfg-suite-web) — design

**Date:** 2026-06-22
**Status:** ✅ Approved (owner)
**Parent:** unified-integration-architecture (Phase A — fill staff-console gaps against the frozen contract)

## Goal
Surface the underwriting **Conditions** workflow in the staff console (`msfg-suite-web`): list/add/edit and
clear/waive/delete the conditions on a loan, wiring the **existing** suite conditions API. This is the marquee
processing gap (the console already covers the 1003, AUS, pricing/lock, fees, CoC, documents, contacts). The
suite backend is complete, so this slice is **FE-only — zero suite changes.**

## Context
The console is built-out; the loan workspace has a **disabled "Conditions" sidebar slot** waiting. The suite
`conditions` module (V21) exposes a full CRUD+lifecycle API; nothing surfaces it yet.

## Backend contract (verified — do not change)
- `GET    /api/loans/{loanId}/conditions` → `ApiResponse<ConditionListResponse>` where
  `ConditionListResponse = { count: int, conditions: ConditionResponse[] }`.
- `POST   /api/loans/{loanId}/conditions` (body `UpsertConditionRequest`) → `ApiResponse<ConditionResponse>` (201).
- `PATCH  /api/loans/{loanId}/conditions/{conditionId}` (body `UpsertConditionRequest`, partial) → `ApiResponse<ConditionResponse>`.
- `DELETE /api/loans/{loanId}/conditions/{conditionId}` → 204 (soft-delete).
- `ConditionResponse`: `{ id, loanId, conditionText, conditionType, status, assignedTo, dueDate, clearedAt,
  clearedBy, notes, createdAt, createdBy, updatedAt }`.
- `UpsertConditionRequest` (same DTO for create + update; nullable fields → PATCH updates only what's sent):
  `{ conditionText, conditionType, status, assignedTo, dueDate, notes }` (exact field names confirmed via the
  generated client at build time — `npm run gen:api`).
- Enums: `ConditionStatus = Outstanding | Cleared | Waived`; `ConditionType = PriorToDocs | PriorToFunding |
  AtClosing | PostClose | Other`.
- **Server-side lifecycle:** on create, status defaults `Outstanding`. On status → `Cleared`/`Waived` (create
  or PATCH) the server stamps `clearedAt` + `clearedBy` (current user); status → `Outstanding` clears them.
  **The FE only sets `status`** — it never sends clearedAt/clearedBy.

## Components (msfg-suite-web — follow the existing feature convention)
The console pattern (per `AusPage`/`ContactsPage`): a feature folder with the page, an `*Api.ts` (generated
openapi-fetch client wrappers + React-Query hooks), co-located dialogs/components, and `.test.tsx`. Mirror it.

- **Route + sidebar:** enable the existing disabled "Conditions" item → route `loans/:loanId/conditions`
  rendering `ConditionsPage` inside `LoanWorkspace`'s outlet (`src/main.tsx` route table + `LoanSidebar.tsx`).
- **`conditionsApi.ts`** — typed wrappers over the generated client for the four endpoints + React-Query hooks:
  `useConditions(loanId)` (list), `useCreateCondition`, `useUpdateCondition`, `useDeleteCondition`
  (invalidate the list query on success). Match the existing `*Api.ts` shape.
- **`ConditionsPage.tsx`** — a table of conditions with a **status filter** (default `Outstanding`; tabs/chips
  for Outstanding / Cleared / Waived / All) showing type, text, assignee, due date, and (when resolved)
  cleared-by/at. Empty state. **"Add condition"** button → dialog.
- **`ConditionDialog.tsx`** — add/edit form (React-Hook-Form + Zod): `conditionText` (required), `conditionType`
  (select), `assignedTo`, `dueDate` (date), `notes`. On submit → create or `update`.
- **Row actions:** **Clear** / **Waive** (PATCH `{status}` only), **Edit** (opens the dialog), **Delete** (confirm
  → DELETE). Sonner toasts; React-Query invalidation refetches. Reuse the console's Radix `Dialog`/`Select`/
  `Button` primitives.

## Data flow
`ConditionsPage` → `useConditions(loanId)` (generated client `GET …/conditions`) → render. Mutations call the
generated client (POST/PATCH/DELETE) → on success invalidate `['conditions', loanId]`. Auth/token + the
`/api` proxy are the console's existing mechanisms (no change).

## Scope
- **In:** the workspace Conditions page — full lifecycle (list + filter, add, edit, clear, waive, delete),
  FE-only in msfg-suite-web, against the existing API. Zero suite changes.
- **Out (separate follow-up):** an outstanding-conditions **count badge on the pipeline list** — the suite
  `LoanListItemResponse` doesn't carry that count, so it needs a small backend DTO addition (conductor-owned);
  deferred to keep this slice FE-only. (The pipeline already supports a `conditionsGt` *filter* server-side.)

## Testing
vitest + React Testing Library, co-located (`ConditionsPage.test.tsx`): list renders grouped by status; add →
dialog → create called → list refetched; clear/waive PATCHes `{status}` and the row moves; delete confirms →
removed. Mock the generated client / React-Query per the console's existing test setup. `npm run build` +
`npm run lint` clean.

## Open questions / confirm-at-build
- Exact `UpsertConditionRequest` field names + the generated client operationIds for conditions — read from
  `npm run gen:api` output before writing the API wrappers.
- Whether the console's generated client already includes the conditions paths (if `/v3/api-docs` pins them);
  if not, run `gen:api` against the running suite first.
- Whether `assignedTo` should be a free-text field or a staff picker — default to free-text for v1 (the backend
  stores a string); a picker is a later enhancement.
