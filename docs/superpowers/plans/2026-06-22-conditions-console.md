# Conditions in the staff console (msfg-suite-web) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Conditions page to the loan workspace in msfg-suite-web — list/filter, add, edit, clear/waive, delete loan conditions — wiring the existing suite `/api/loans/{loanId}/conditions` API. FE-only; zero suite changes.

**Architecture:** A new `src/features/conditions/` feature mirroring the existing `contacts` feature exactly (openapi-fetch generated client + React-Query hooks in `conditionsApi.ts`; a `ConditionsPage.tsx` with an inline dialog using per-field `useState` — NOT RHF/Zod, matching the contacts convention; MSW/RTL test). Plus three wiring edits: regenerate the API types, add a child route, enable the sidebar item.

**Tech Stack:** React 19 / TS / Vite, React Router 7, @tanstack/react-query, openapi-fetch (generated `schema.d.ts`), Radix UI (`@/components/ui`), Tailwind, Sonner, vitest + RTL + MSW.

**Repo / branch:** `/Users/zacharyzink/MSFG/msfg-suite-web`, new branch `feat/conditions-console` off `main` (currently clean on `main`). Test: `npm test` (vitest). Build/lint: `npm run build`, `npm run lint`. No push/merge without owner approval.

**Backend contract (suite, already on main — do not change):**
- `GET /api/loans/{loanId}/conditions` → `{success, data:{count, conditions: ConditionResponse[]}}`
- `POST /api/loans/{loanId}/conditions` (body `UpsertConditionRequest`) → `{success, data: ConditionResponse}`
- `PATCH /api/loans/{loanId}/conditions/{conditionId}` (body `UpsertConditionRequest`, partial) → `{success, data: ConditionResponse}`
- `DELETE /api/loans/{loanId}/conditions/{conditionId}` → 204
- `ConditionResponse`: `{ id, loanId, conditionText, conditionType, status, assignedTo, dueDate, clearedAt, clearedBy, notes, createdAt, createdBy, updatedAt }`
- enums: `status` = `Outstanding|Cleared|Waived`; `conditionType` = `PriorToDocs|PriorToFunding|AtClosing|PostClose|Other`
- The server stamps `clearedAt`/`clearedBy` on status→Cleared/Waived; **the FE only sends `status`** for clear/waive.

---

## File Structure
- Modify: `src/lib/api/schema.d.ts` (regenerated — adds conditions paths/schemas)
- Create: `src/features/conditions/conditionsApi.ts` (types, query keys, hooks)
- Create: `src/features/conditions/ConditionsPage.tsx` (page + inline `ConditionDialog`)
- Create: `src/features/conditions/conditions.test.tsx` (MSW/RTL)
- Modify: `src/router.tsx` (add `conditions` child route)
- Modify: `src/components/shell/LoanSidebar.tsx` (enable "Conditions" NavLink; drop from `MENU_FUTURE`)

---

### Task 1: Regenerate the API client types (gen:api)

**Why first:** `schema.d.ts` has NO conditions paths/schemas today — nothing in Tasks 2–4 typechecks until they exist.

**Files:** Modify `src/lib/api/schema.d.ts` (generated)

- [ ] **Step 1: Ensure suite is running on :8080 with the conditions endpoints.** The `conditions` module is on suite `main` (V21). If not already up:
  `cd /Users/zacharyzink/MSFG/msfg-suite && docker compose up -d && ./gradlew :app:bootRun --args='--spring.profiles.active=local'` (wait for `Started LosApplication`). Confirm the endpoints are in the spec:
  `curl -s localhost:8080/v3/api-docs | grep -c 'conditions'` → expect > 0.

- [ ] **Step 2: Regenerate.** In msfg-suite-web: `npm run gen:api` (it reads the running suite's `/v3/api-docs`).

- [ ] **Step 3: Verify the conditions types landed.**
  Run: `grep -n 'loans/{loanId}/conditions' src/lib/api/schema.d.ts` → expect the two path entries.
  Run: `grep -nE 'ConditionResponse|UpsertConditionRequest|ConditionListResponse' src/lib/api/schema.d.ts` → expect schema entries.
  **Record the exact field names** of `UpsertConditionRequest` from the file (the plan assumes `conditionText, conditionType, status, assignedTo, dueDate, notes` — if the generated names differ, use the generated ones in Tasks 2–3).

- [ ] **Step 4: Commit.**
```bash
cd /Users/zacharyzink/MSFG/msfg-suite-web
git checkout -b feat/conditions-console 2>/dev/null || git checkout feat/conditions-console
git add src/lib/api/schema.d.ts
git commit -m "chore(api): regenerate client types — adds conditions endpoints

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `conditionsApi.ts` — typed hooks

**Files:** Create `src/features/conditions/conditionsApi.ts`
(No standalone test — thin generated-client wrappers, exercised by Task 3's page test, matching the `contacts` convention. Verified here by typecheck.)

- [ ] **Step 1: Implement** (mirrors `contactsApi.ts`; adds a PATCH hook + unwraps the `{count, conditions}` list envelope):
```ts
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import type { components } from "@/lib/api/schema"
import { getApiClient } from "@/lib/api/client"
import { handle } from "@/lib/api/envelope"

export type ConditionResponse = components["schemas"]["ConditionResponse"]
export type ConditionListResponse = components["schemas"]["ConditionListResponse"]
export type UpsertConditionRequest = components["schemas"]["UpsertConditionRequest"]
export type ConditionStatus = NonNullable<ConditionResponse["status"]>
export type ConditionType = NonNullable<ConditionResponse["conditionType"]>

export const STATUS_LABELS: Record<ConditionStatus, string> = {
  Outstanding: "Outstanding",
  Cleared: "Cleared",
  Waived: "Waived",
}
export const TYPE_LABELS: Record<ConditionType, string> = {
  PriorToDocs: "Prior to Docs",
  PriorToFunding: "Prior to Funding",
  AtClosing: "At Closing",
  PostClose: "Post-Close",
  Other: "Other",
}

export const conditionKeys = {
  list: (loanId: string) => ["conditions", loanId] as const,
}

export function useConditionsQuery(loanId: string) {
  return useQuery({
    queryKey: conditionKeys.list(loanId),
    queryFn: async () => {
      const res = await getApiClient().GET("/api/loans/{loanId}/conditions", {
        params: { path: { loanId } },
      })
      return handle<ConditionListResponse>(res).conditions ?? []
    },
    enabled: !!loanId,
  })
}

export function useCreateConditionMutation(loanId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body: UpsertConditionRequest) => {
      const res = await getApiClient().POST("/api/loans/{loanId}/conditions", {
        params: { path: { loanId } },
        body,
      })
      return handle<ConditionResponse>(res)
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: conditionKeys.list(loanId) }),
  })
}

export function useUpdateConditionMutation(loanId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ conditionId, body }: { conditionId: string; body: UpsertConditionRequest }) => {
      const res = await getApiClient().PATCH("/api/loans/{loanId}/conditions/{conditionId}", {
        params: { path: { loanId, conditionId } },
        body,
      })
      return handle<ConditionResponse>(res)
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: conditionKeys.list(loanId) }),
  })
}

export function useDeleteConditionMutation(loanId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (conditionId: string) => {
      const res = await getApiClient().DELETE("/api/loans/{loanId}/conditions/{conditionId}", {
        params: { path: { loanId, conditionId } },
      })
      return handle<unknown>(res)
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: conditionKeys.list(loanId) }),
  })
}
```
> If Task 1 recorded different `UpsertConditionRequest` field names, adjust the dialog in Task 3 (the API types here are derived from the schema, so they self-correct).

- [ ] **Step 2: Typecheck.** `npx tsc --noEmit` → no new errors in this file (Task 3 will consume it). Commit with Task 3 (or alone):
```bash
git add src/features/conditions/conditionsApi.ts && git commit -m "feat(conditions): API hooks (list/create/update/delete)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `ConditionsPage.tsx` + tests (the bulk)

**Files:** Create `src/features/conditions/ConditionsPage.tsx`, `src/features/conditions/conditions.test.tsx`

- [ ] **Step 1: Write the failing test** `conditions.test.tsx` (mirror `contacts.test.tsx`: MSW shared `server`, absolute URLs, `pointerEventsCheck: Never`):
```tsx
import { render, screen, within, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { PointerEventsCheckLevel } from "@testing-library/user-event"
import { describe, it, expect } from "vitest"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { http, HttpResponse } from "msw"
import type { ReactNode } from "react"
import { MemoryRouter, Routes, Route } from "react-router-dom"
import { server } from "@/test/msw/server"
import ConditionsPage from "./ConditionsPage"

const BASE = "http://localhost:8080/api/loans/L1/conditions"
function wrap(children: ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}
function renderAt() {
  return render(wrap(
    <MemoryRouter initialEntries={["/loans/L1/conditions"]}>
      <Routes><Route path="/loans/:loanId/conditions" element={<ConditionsPage />} /></Routes>
    </MemoryRouter>,
  ))
}
const listResp = (rows: unknown[]) => HttpResponse.json({ success: true, data: { count: rows.length, conditions: rows } })

describe("ConditionsPage", () => {
  it("shows empty state", async () => {
    server.use(http.get(BASE, () => listResp([])))
    renderAt()
    expect(await screen.findByText(/no conditions/i)).toBeInTheDocument()
  })

  it("renders an outstanding condition (default filter)", async () => {
    server.use(http.get(BASE, () => listResp([
      { id: "K1", loanId: "L1", conditionText: "2 months bank statements", conditionType: "PriorToDocs", status: "Outstanding", assignedTo: "Processor" },
    ])))
    renderAt()
    expect(await screen.findByText("2 months bank statements")).toBeInTheDocument()
    expect(screen.getByText("Prior to Docs")).toBeInTheDocument()
  })

  it("adds a condition via POST", async () => {
    let posted: Record<string, unknown> | null = null
    server.use(
      http.get(BASE, () => listResp([])),
      http.post(BASE, async ({ request }) => { posted = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ success: true, data: { id: "K1", loanId: "L1", conditionText: "Letter of explanation", conditionType: "PriorToFunding", status: "Outstanding" } }) }),
    )
    const user = userEvent.setup({ pointerEventsCheck: PointerEventsCheckLevel.Never })
    renderAt()
    await screen.findByText(/no conditions/i)
    await user.click(screen.getByRole("button", { name: /add condition/i }))
    const dialog = screen.getByRole("dialog")
    await user.type(within(dialog).getByLabelText(/^condition$/i), "Letter of explanation")
    await user.click(within(dialog).getByRole("button", { name: /^add$/i }))
    await waitFor(() => expect(posted).toMatchObject({ conditionText: "Letter of explanation" }))
  })

  it("clears a condition via PATCH {status:'Cleared'}", async () => {
    let patched: Record<string, unknown> | null = null
    server.use(
      http.get(BASE, () => listResp([{ id: "K1", loanId: "L1", conditionText: "Paystub", conditionType: "PriorToDocs", status: "Outstanding" }])),
      http.patch(`${BASE}/:conditionId`, async ({ request }) => { patched = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ success: true, data: { id: "K1", loanId: "L1", conditionText: "Paystub", conditionType: "PriorToDocs", status: "Cleared" } }) }),
    )
    const user = userEvent.setup({ pointerEventsCheck: PointerEventsCheckLevel.Never })
    renderAt()
    await screen.findByText("Paystub")
    await user.click(screen.getByLabelText("Clear condition K1"))
    await waitFor(() => expect(patched).toMatchObject({ status: "Cleared" }))
  })

  it("deletes a condition via DELETE", async () => {
    let deletedId: string | null = null
    server.use(
      http.get(BASE, () => listResp([{ id: "K1", loanId: "L1", conditionText: "Appraisal", conditionType: "AtClosing", status: "Outstanding" }])),
      http.delete(`${BASE}/:conditionId`, ({ params }) => { deletedId = params.conditionId as string; return HttpResponse.json({ success: true, data: null }) }),
    )
    const user = userEvent.setup({ pointerEventsCheck: PointerEventsCheckLevel.Never })
    renderAt()
    await screen.findByText("Appraisal")
    await user.click(screen.getByLabelText("Delete condition K1"))
    await waitFor(() => expect(deletedId).toBe("K1"))
  })
})
```

- [ ] **Step 2: Run — expect FAIL** (no ConditionsPage): `npm test -- src/features/conditions/conditions.test.tsx`

- [ ] **Step 3: Implement `ConditionsPage.tsx`** (mirror `ContactsPage.tsx`; inline `ConditionDialog` reused for add + edit; status filter; clear/waive/edit/delete row actions):
```tsx
import { useState } from "react"
import type { ReactNode } from "react"
import { useParams } from "react-router-dom"
import { SectionHeaderBar } from "@/components/shell/SectionHeaderBar"
import { AlertBanner } from "@/components/shell/AlertBanner"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger, DialogFooter } from "@/components/ui/dialog"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { ApiError } from "@/lib/api/errors"
import {
  TYPE_LABELS, STATUS_LABELS,
  useConditionsQuery, useCreateConditionMutation, useUpdateConditionMutation, useDeleteConditionMutation,
} from "./conditionsApi"
import type { ConditionResponse, ConditionType, ConditionStatus, UpsertConditionRequest } from "./conditionsApi"

const ALL_TYPES = Object.keys(TYPE_LABELS) as ConditionType[]
const STATUS_FILTERS: Array<ConditionStatus | "All"> = ["Outstanding", "Cleared", "Waived", "All"]

function ConditionDialog({ loanId, existing, trigger }: { loanId: string; existing?: ConditionResponse; trigger: ReactNode }) {
  const isEdit = !!existing
  const [open, setOpen] = useState(false)
  const [conditionText, setConditionText] = useState(existing?.conditionText ?? "")
  const [conditionType, setConditionType] = useState<ConditionType>((existing?.conditionType as ConditionType) ?? "PriorToDocs")
  const [assignedTo, setAssignedTo] = useState(existing?.assignedTo ?? "")
  const [dueDate, setDueDate] = useState(existing?.dueDate ?? "")
  const [notes, setNotes] = useState(existing?.notes ?? "")
  const [error, setError] = useState<string | null>(null)
  const createM = useCreateConditionMutation(loanId)
  const updateM = useUpdateConditionMutation(loanId)

  const reset = () => {
    setConditionText(existing?.conditionText ?? ""); setConditionType((existing?.conditionType as ConditionType) ?? "PriorToDocs")
    setAssignedTo(existing?.assignedTo ?? ""); setDueDate(existing?.dueDate ?? ""); setNotes(existing?.notes ?? ""); setError(null)
  }
  const submit = async () => {
    setError(null)
    const body: UpsertConditionRequest = { conditionText, conditionType }
    if (assignedTo) body.assignedTo = assignedTo
    if (dueDate) body.dueDate = dueDate
    if (notes) body.notes = notes
    try {
      if (isEdit && existing?.id) await updateM.mutateAsync({ conditionId: existing.id, body })
      else await createM.mutateAsync(body)
      setOpen(false); if (!isEdit) reset()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to save condition")
    }
  }
  const pending = createM.isPending || updateM.isPending

  return (
    <Dialog open={open} onOpenChange={(o) => { setOpen(o); if (!o) reset() }}>
      <DialogTrigger asChild>{trigger}</DialogTrigger>
      <DialogContent>
        <DialogHeader><DialogTitle>{isEdit ? "Edit Condition" : "Add Condition"}</DialogTitle></DialogHeader>
        <div className="space-y-3">
          {error && <AlertBanner variant="error">{error}</AlertBanner>}
          <div className="space-y-1">
            <Label htmlFor="cnd-type">Type</Label>
            <Select value={conditionType} onValueChange={(v) => setConditionType(v as ConditionType)}>
              <SelectTrigger id="cnd-type"><SelectValue /></SelectTrigger>
              <SelectContent>{ALL_TYPES.map((t) => <SelectItem key={t} value={t}>{TYPE_LABELS[t]}</SelectItem>)}</SelectContent>
            </Select>
          </div>
          <div className="space-y-1">
            <Label htmlFor="cnd-text">Condition</Label>
            <Input id="cnd-text" value={conditionText} onChange={(e) => setConditionText(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="cnd-assignee">Assigned To</Label>
            <Input id="cnd-assignee" value={assignedTo} onChange={(e) => setAssignedTo(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="cnd-due">Due Date</Label>
            <Input id="cnd-due" type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="cnd-notes">Notes</Label>
            <Input id="cnd-notes" value={notes} onChange={(e) => setNotes(e.target.value)} />
          </div>
          <DialogFooter>
            <Button onClick={submit} disabled={!conditionText || pending}>{isEdit ? "Save" : "Add"}</Button>
          </DialogFooter>
        </div>
      </DialogContent>
    </Dialog>
  )
}

export default function ConditionsPage() {
  const { loanId = "" } = useParams()
  const { data: conditions, isLoading } = useConditionsQuery(loanId)
  const updateM = useUpdateConditionMutation(loanId)
  const deleteM = useDeleteConditionMutation(loanId)
  const [filter, setFilter] = useState<ConditionStatus | "All">("Outstanding")

  const rows = (conditions ?? []).filter((c) => filter === "All" || c.status === filter)
  const setStatus = (c: ConditionResponse, status: ConditionStatus) => {
    if (!c.id) return
    updateM.mutate({ conditionId: c.id, body: { conditionText: c.conditionText, conditionType: c.conditionType, status } as UpsertConditionRequest })
  }

  return (
    <div>
      <SectionHeaderBar title="Conditions" actions={<ConditionDialog loanId={loanId} trigger={<Button size="sm">Add Condition</Button>} />} />
      <div className="flex gap-1 px-3 py-2">
        {STATUS_FILTERS.map((s) => (
          <Button key={s} size="sm" variant={filter === s ? "default" : "ghost"} onClick={() => setFilter(s)}>
            {s === "All" ? "All" : STATUS_LABELS[s]}
          </Button>
        ))}
      </div>
      {isLoading ? (
        <p className="p-4 text-sm text-muted-foreground">Loading…</p>
      ) : rows.length === 0 ? (
        <p className="p-4 text-sm text-muted-foreground">No conditions.</p>
      ) : (
        <table className="w-full text-sm">
          <thead><tr className="border-b text-left">
            <th className="px-3 py-2 font-medium">Type</th>
            <th className="px-3 py-2 font-medium">Condition</th>
            <th className="px-3 py-2 font-medium">Assigned To</th>
            <th className="px-3 py-2 font-medium">Due</th>
            <th className="px-3 py-2 font-medium">Status</th>
            <th className="px-3 py-2"></th>
          </tr></thead>
          <tbody>
            {rows.map((c) => (
              <tr key={c.id} className="border-b hover:bg-muted">
                <td className="px-3 py-2">{c.conditionType ? TYPE_LABELS[c.conditionType] : ""}</td>
                <td className="px-3 py-2">{c.conditionText}</td>
                <td className="px-3 py-2">{c.assignedTo}</td>
                <td className="px-3 py-2">{c.dueDate}</td>
                <td className="px-3 py-2">{c.status ? STATUS_LABELS[c.status] : ""}</td>
                <td className="px-3 py-2 space-x-1 whitespace-nowrap">
                  {c.status === "Outstanding" && (
                    <>
                      <Button variant="ghost" size="sm" aria-label={`Clear condition ${c.id}`} onClick={() => setStatus(c, "Cleared")}>Clear</Button>
                      <Button variant="ghost" size="sm" aria-label={`Waive condition ${c.id}`} onClick={() => setStatus(c, "Waived")}>Waive</Button>
                    </>
                  )}
                  <ConditionDialog loanId={loanId} existing={c} trigger={<Button variant="ghost" size="sm" aria-label={`Edit condition ${c.id}`}>Edit</Button>} />
                  <Button variant="ghost" size="sm" aria-label={`Delete condition ${c.id}`} onClick={() => c.id && deleteM.mutate(c.id)}>Delete</Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
```
> Verify `Button` supports `variant="default"` (read `src/components/ui/button.tsx`); if the active-filter variant differs, use the available one (e.g. `"secondary"`). Everything else (`SectionHeaderBar`/`AlertBanner`/`Dialog`/`Select`/`Input`/`Label`) is used exactly as `ContactsPage` does.

- [ ] **Step 4: Run — expect PASS:** `npm test -- src/features/conditions/conditions.test.tsx` (5 tests).

- [ ] **Step 5: Commit:**
```bash
git add src/features/conditions/ConditionsPage.tsx src/features/conditions/conditions.test.tsx
git commit -m "feat(conditions): workspace page — list/filter, add, edit, clear/waive, delete

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Wire the route + sidebar

**Files:** Modify `src/router.tsx`, `src/components/shell/LoanSidebar.tsx`

- [ ] **Step 1: Route.** In `src/router.tsx`, add the import (near the other workspace page imports):
```tsx
import ConditionsPage from "@/features/conditions/ConditionsPage"
```
and add this child inside the `/loans/:loanId` `children` array (next to the `contacts` entry):
```tsx
      { path: "conditions", element: <ConditionsPage /> },
```

- [ ] **Step 2: Sidebar.** In `src/components/shell/LoanSidebar.tsx`: remove `"Conditions"` from the `MENU_FUTURE` array, and add an enabled NavLink (copy the Contacts NavLink) — a sensible place is right after the Contacts NavLink:
```tsx
            <NavLink
              to={`/loans/${loanId}/conditions`}
              className={({ isActive }) => `border-b px-3 py-2 text-sm font-medium ${isActive ? "bg-secondary text-secondary-foreground" : "hover:bg-muted"}`}
            >
              Conditions
            </NavLink>
```

- [ ] **Step 3: Verify build + full suite.**
  `npm run build` → succeeds (typecheck clean). `npm run lint` → clean. `npm test` → full suite green (existing + the 5 new conditions tests).

- [ ] **Step 4: Commit:**
```bash
git add src/router.tsx src/components/shell/LoanSidebar.tsx
git commit -m "feat(conditions): enable the Conditions route + sidebar item

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review (completed)
- **Spec coverage:** list+filter → T3 (status filter, default Outstanding). add → T3 (ConditionDialog create). edit → T3 (ConditionDialog with `existing`). clear/waive → T3 (PATCH `{status}` only, server stamps clearedAt/By). delete → T3. API wiring → T2 (the four hooks, ConditionListResponse unwrap). Route + sidebar enable → T4. gen:api prerequisite → T1. Zero suite changes → confirmed. assignedTo free-text → T3 (Input). Pipeline-count → correctly OUT (not in any task).
- **Type consistency:** `UpsertConditionRequest`/`ConditionResponse`/`ConditionStatus`/`ConditionType` defined in T2, consumed identically in T3; hook names (`useConditionsQuery`/`useCreateConditionMutation`/`useUpdateConditionMutation`/`useDeleteConditionMutation`) match across T2/T3; `useUpdateConditionMutation` takes `{conditionId, body}` and is called that way in both clear/waive and edit.
- **Verify-at-build points (read real source first):** the exact `UpsertConditionRequest` field names (T1 records them; adjust T3 dialog if different); `Button` variant names in `src/components/ui/button.tsx`; that `@/test/msw/server` + `npm run gen:api` exist as the Explore report described.
- **Placeholders:** none — full code per file; T1 is codegen-verify; the "verify variant" note points at a real file, not a placeholder.
