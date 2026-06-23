# TRID Disclosures (LE/CD) Console Feature — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a loan **Disclosures** page to the staff console (`msfg-suite-web`) that wires the existing suite TRID disclosures API — issue Loan Estimate / Closing Disclosure, history with TILA figures + reset badges, record receipt, and read-only timing + tolerance compliance cards.

**Architecture:** FE-only in `msfg-suite-web`, mirroring the just-built Conditions feature convention: a `disclosures` feature folder with a thin typed `disclosuresApi.ts` (openapi-fetch wrappers + React-Query hooks over the generated client), an extracted `IssueDisclosureDialog`, a `DisclosuresPage` composing read-only compliance cards + a history table + an inline receipt dialog, and a co-located MSW test. Wire a route + sidebar item and deep-link the FeesPage "Preview CD" button. **Zero suite changes.**

**Tech Stack:** Vite + React 19 + TypeScript, React Router 7, @tanstack/react-query, openapi-fetch (generated `schema.d.ts`), Radix-based `@/components/ui` primitives, Tailwind, Sonner, vitest + React Testing Library + MSW.

**Repo:** `/Users/zacharyzink/MSFG/msfg-suite-web` (run all commands from there).

**Pre-flight (already verified — do NOT re-do):** the generated client `src/lib/api/schema.d.ts` already contains all disclosures paths and component schemas (`DisclosureResponse`, `TimingResponse`, `ToleranceResponse`, `IssueDisclosureRequest`, `RecordReceiptRequest`, `ToleranceComparison`). Monetary fields (`apr`, `financeCharge`, `amountFinanced`, `totalOfPayments`, `tip`, tolerance sums) are `number`; dates are ISO `string`; `bucketTotals` is `{ [key: string]: number }`. **No `npm run gen:api` is required.**

---

## File Structure

| File | Responsibility |
|---|---|
| `src/features/disclosures/disclosuresApi.ts` (create) | Types re-exported from the generated schema, label maps, query keys, the six React-Query hooks, and a `documentContentUrl` helper. |
| `src/features/disclosures/IssueDisclosureDialog.tsx` (create) | A confirm dialog for issuing an LE or CD (delivery-method select; prepayment-penalty checkbox for the CD variant). Pure-ish: takes an `onIssue` callback. |
| `src/features/disclosures/DisclosuresPage.tsx` (create) | The page: action bar (two `IssueDisclosureDialog`), `TimingCard` + `ToleranceCard` (local components), history table with reset badge + `RecordReceiptDialog` (local) + view-document link. |
| `src/features/disclosures/disclosures.test.tsx` (create) | vitest + RTL + MSW integration tests for the page. |
| `src/router.tsx` (modify) | Import `DisclosuresPage`; add `{ path: "disclosures", element: <DisclosuresPage /> }`. |
| `src/components/shell/LoanSidebar.tsx` (modify) | Add a "Disclosures" `NavLink`; remove `"Closing Disclosure"` from `MENU_FUTURE`. |
| `src/features/fees/FeesPage.tsx` (modify) | Enable the disabled "Preview CD" button → deep-link to the loan's Disclosures page. |

---

### Task 1: `disclosuresApi.ts` — typed wrappers + React-Query hooks

**Files:**
- Create: `src/features/disclosures/disclosuresApi.ts`

This mirrors `src/features/conditions/conditionsApi.ts` exactly in shape (`getApiClient` / `handle` / `@/lib/api/envelope`). It is a thin generated-client wrapper with no behavior of its own — it is type-checked here and exercised behaviorally by the page test in Task 4 (same convention as `conditionsApi.ts`, which had no standalone unit test).

- [ ] **Step 1: Write the file**

```ts
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import type { components } from "@/lib/api/schema"
import { getApiClient, resolveBaseUrl } from "@/lib/api/client"
import { handle } from "@/lib/api/envelope"

export type DisclosureResponse = components["schemas"]["DisclosureResponse"]
export type TimingResponse = components["schemas"]["TimingResponse"]
export type ToleranceResponse = components["schemas"]["ToleranceResponse"]
export type IssueDisclosureRequest = components["schemas"]["IssueDisclosureRequest"]
export type DisclosureKind = NonNullable<DisclosureResponse["kind"]>
export type DisclosureStatus = NonNullable<DisclosureResponse["status"]>
export type DeliveryMethod = NonNullable<IssueDisclosureRequest["deliveryMethod"]>
export type ResetReason = NonNullable<DisclosureResponse["resetReasons"]>[number]

export const KIND_LABELS: Record<DisclosureKind, string> = {
  LOAN_ESTIMATE: "Loan Estimate",
  CLOSING_DISCLOSURE: "Closing Disclosure",
}
export const STATUS_LABELS: Record<DisclosureStatus, string> = {
  PENDING: "Pending",
  SENT: "Sent",
  RECEIVED: "Received",
  ERROR: "Error",
}
export const DELIVERY_LABELS: Record<DeliveryMethod, string> = {
  IN_PERSON: "In person",
  MAIL: "Mail",
  EMAIL: "Email",
  COURIER: "Courier",
}
export const RESET_REASON_LABELS: Record<ResetReason, string> = {
  APR_INACCURATE: "APR inaccurate",
  PRODUCT_CHANGED: "Product changed",
  PREPAYMENT_PENALTY_ADDED: "Prepayment penalty added",
}
export const BUCKET_LABELS: Record<string, string> = {
  ZERO: "Zero tolerance",
  TEN_PERCENT: "10% cumulative",
  UNLIMITED: "No tolerance limit",
}

export const disclosureKeys = {
  list: (loanId: string) => ["disclosures", loanId] as const,
  timing: (loanId: string) => ["disclosures-timing", loanId] as const,
  tolerance: (loanId: string) => ["disclosures-tolerance", loanId] as const,
}

/** Direct URL to the generated H-24/H-25 document bytes (served by the documents module). */
export function documentContentUrl(loanId: string, documentId: string): string {
  return `${resolveBaseUrl()}/api/loans/${loanId}/documents/${documentId}/content`
}

function invalidateAll(qc: ReturnType<typeof useQueryClient>, loanId: string) {
  qc.invalidateQueries({ queryKey: disclosureKeys.list(loanId) })
  qc.invalidateQueries({ queryKey: disclosureKeys.timing(loanId) })
  qc.invalidateQueries({ queryKey: disclosureKeys.tolerance(loanId) })
}

export function useDisclosuresQuery(loanId: string) {
  return useQuery({
    queryKey: disclosureKeys.list(loanId),
    queryFn: async () => {
      const res = await getApiClient().GET("/api/loans/{loanId}/disclosures", {
        params: { path: { loanId } },
      })
      return handle<DisclosureResponse[]>(res) ?? []
    },
    enabled: !!loanId,
  })
}

export function useTimingQuery(loanId: string) {
  return useQuery({
    queryKey: disclosureKeys.timing(loanId),
    queryFn: async () => {
      const res = await getApiClient().GET("/api/loans/{loanId}/disclosures/timing", {
        params: { path: { loanId } },
      })
      return handle<TimingResponse>(res)
    },
    enabled: !!loanId,
  })
}

export function useToleranceQuery(loanId: string) {
  return useQuery({
    queryKey: disclosureKeys.tolerance(loanId),
    queryFn: async () => {
      const res = await getApiClient().GET("/api/loans/{loanId}/disclosures/tolerance", {
        params: { path: { loanId } },
      })
      return handle<ToleranceResponse>(res)
    },
    enabled: !!loanId,
  })
}

export function useIssueLoanEstimateMutation(loanId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body: IssueDisclosureRequest) => {
      const res = await getApiClient().POST("/api/loans/{loanId}/disclosures/loan-estimate", {
        params: { path: { loanId } },
        body,
      })
      return handle<DisclosureResponse>(res)
    },
    onSuccess: () => invalidateAll(qc, loanId),
  })
}

export function useIssueClosingDisclosureMutation(loanId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body: IssueDisclosureRequest) => {
      const res = await getApiClient().POST("/api/loans/{loanId}/disclosures/closing-disclosure", {
        params: { path: { loanId } },
        body,
      })
      return handle<DisclosureResponse>(res)
    },
    onSuccess: () => invalidateAll(qc, loanId),
  })
}

export function useRecordReceiptMutation(loanId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ disclosureId, receivedAt }: { disclosureId: string; receivedAt: string }) => {
      const res = await getApiClient().POST("/api/loans/{loanId}/disclosures/{disclosureId}/receipt", {
        params: { path: { loanId, disclosureId } },
        body: { receivedAt },
      })
      return handle<DisclosureResponse>(res)
    },
    onSuccess: () => invalidateAll(qc, loanId),
  })
}
```

- [ ] **Step 2: Typecheck**

Run: `npm run build`
Expected: PASS (no TS errors). If `getApiClient().POST(...)` complains that `body` is required/optional, the generated path type drives it — keep `body` as shown (the schema marks the request body optional, so passing an object is always valid).

- [ ] **Step 3: Commit**

```bash
git add src/features/disclosures/disclosuresApi.ts
git commit -m "feat(disclosures): typed API wrappers + React-Query hooks"
```

---

### Task 2: `IssueDisclosureDialog.tsx` — issue confirm dialog (TDD)

**Files:**
- Create: `src/features/disclosures/IssueDisclosureDialog.tsx`
- Test: `src/features/disclosures/IssueDisclosureDialog.test.tsx`

A confirm dialog reused for both kinds. It owns its open/form state and calls an injected `onIssue(body)` — so it is testable in isolation with a `vi.fn()`, no MSW needed. The CD variant adds a prepayment-penalty checkbox.

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen, within, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { PointerEventsCheckLevel } from "@testing-library/user-event"
import { describe, it, expect, vi } from "vitest"
import { IssueDisclosureDialog } from "./IssueDisclosureDialog"

describe("IssueDisclosureDialog", () => {
  it("LE variant: submit emits {deliveryMethod:'EMAIL'} and shows no prepayment checkbox", async () => {
    const onIssue = vi.fn().mockResolvedValue({})
    const user = userEvent.setup({ pointerEventsCheck: PointerEventsCheckLevel.Never })
    render(<IssueDisclosureDialog kind="LOAN_ESTIMATE" pending={false} onIssue={onIssue} trigger={<button>Open LE</button>} />)
    await user.click(screen.getByRole("button", { name: /open le/i }))
    const dialog = screen.getByRole("dialog")
    expect(within(dialog).queryByText(/prepayment penalty/i)).toBeNull()
    await user.click(within(dialog).getByRole("button", { name: /^issue le$/i }))
    await waitFor(() => expect(onIssue).toHaveBeenCalledWith({ deliveryMethod: "EMAIL" }))
  })

  it("CD variant: checking prepayment penalty emits prepaymentPenalty:true", async () => {
    const onIssue = vi.fn().mockResolvedValue({})
    const user = userEvent.setup({ pointerEventsCheck: PointerEventsCheckLevel.Never })
    render(<IssueDisclosureDialog kind="CLOSING_DISCLOSURE" pending={false} onIssue={onIssue} trigger={<button>Open CD</button>} />)
    await user.click(screen.getByRole("button", { name: /open cd/i }))
    const dialog = screen.getByRole("dialog")
    await user.click(within(dialog).getByLabelText(/prepayment penalty added/i))
    await user.click(within(dialog).getByRole("button", { name: /^issue cd$/i }))
    await waitFor(() => expect(onIssue).toHaveBeenCalledWith({ deliveryMethod: "EMAIL", prepaymentPenalty: true }))
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run src/features/disclosures/IssueDisclosureDialog.test.tsx`
Expected: FAIL — cannot resolve `./IssueDisclosureDialog`.

- [ ] **Step 3: Write the component**

```tsx
import { useState } from "react"
import type { ReactNode } from "react"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { Checkbox } from "@/components/ui/checkbox"
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger, DialogFooter } from "@/components/ui/dialog"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { AlertBanner } from "@/components/shell/AlertBanner"
import { ApiError } from "@/lib/api/errors"
import { DELIVERY_LABELS } from "./disclosuresApi"
import type { DeliveryMethod, DisclosureKind, IssueDisclosureRequest } from "./disclosuresApi"

const DELIVERY_METHODS = Object.keys(DELIVERY_LABELS) as DeliveryMethod[]

export function IssueDisclosureDialog({
  kind,
  trigger,
  onIssue,
  pending,
}: {
  kind: DisclosureKind
  trigger: ReactNode
  onIssue: (body: IssueDisclosureRequest) => Promise<unknown>
  pending: boolean
}) {
  const isCd = kind === "CLOSING_DISCLOSURE"
  const [open, setOpen] = useState(false)
  const [deliveryMethod, setDeliveryMethod] = useState<DeliveryMethod>("EMAIL")
  const [prepaymentPenalty, setPrepaymentPenalty] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const reset = () => {
    setDeliveryMethod("EMAIL")
    setPrepaymentPenalty(false)
    setError(null)
  }
  const submit = async () => {
    setError(null)
    const body: IssueDisclosureRequest = { deliveryMethod }
    if (isCd && prepaymentPenalty) body.prepaymentPenalty = true
    try {
      await onIssue(body)
      setOpen(false)
      reset()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : `Failed to issue ${isCd ? "Closing Disclosure" : "Loan Estimate"}`)
    }
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { setOpen(o); if (!o) reset() }}>
      <DialogTrigger asChild>{trigger}</DialogTrigger>
      <DialogContent>
        <DialogHeader><DialogTitle>{isCd ? "Issue Closing Disclosure" : "Issue Loan Estimate"}</DialogTitle></DialogHeader>
        <div className="space-y-3">
          {error && <AlertBanner variant="error">{error}</AlertBanner>}
          <p className="text-sm text-muted-foreground">
            Issuing records an official disclosure and starts the applicable TRID timing clock.
          </p>
          <div className="space-y-1">
            <Label htmlFor="dsc-delivery">Delivery method</Label>
            <Select value={deliveryMethod} onValueChange={(v) => setDeliveryMethod(v as DeliveryMethod)}>
              <SelectTrigger id="dsc-delivery"><SelectValue /></SelectTrigger>
              <SelectContent>
                {DELIVERY_METHODS.map((m) => <SelectItem key={m} value={m}>{DELIVERY_LABELS[m]}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
          {isCd && (
            <div className="flex items-center gap-2">
              <Checkbox id="dsc-prepay" checked={prepaymentPenalty} onCheckedChange={(c) => setPrepaymentPenalty(c === true)} />
              <Label htmlFor="dsc-prepay" className="font-normal">Prepayment penalty added since last disclosure</Label>
            </div>
          )}
          <DialogFooter>
            <Button onClick={submit} disabled={pending}>{isCd ? "Issue CD" : "Issue LE"}</Button>
          </DialogFooter>
        </div>
      </DialogContent>
    </Dialog>
  )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run src/features/disclosures/IssueDisclosureDialog.test.tsx`
Expected: PASS (2 passed).

- [ ] **Step 5: Commit**

```bash
git add src/features/disclosures/IssueDisclosureDialog.tsx src/features/disclosures/IssueDisclosureDialog.test.tsx
git commit -m "feat(disclosures): issue LE/CD confirm dialog"
```

---

### Task 3: `DisclosuresPage.tsx` — compliance cards + history table + receipt dialog

**Files:**
- Create: `src/features/disclosures/DisclosuresPage.tsx`

Composes everything. `TimingCard`, `ToleranceCard`, and `RecordReceiptDialog` are local components in this file (small, single-use — same inlining convention as `ConditionsPage`'s `ConditionDialog`). Behavior is locked by the MSW integration test in Task 4; this task verifies by typecheck.

- [ ] **Step 1: Write the file**

```tsx
import { Fragment, useState } from "react"
import { useParams } from "react-router-dom"
import { toast } from "sonner"
import { SectionHeaderBar } from "@/components/shell/SectionHeaderBar"
import { AlertBanner } from "@/components/shell/AlertBanner"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger, DialogFooter } from "@/components/ui/dialog"
import { ApiError } from "@/lib/api/errors"
import { IssueDisclosureDialog } from "./IssueDisclosureDialog"
import {
  KIND_LABELS, STATUS_LABELS, DELIVERY_LABELS, RESET_REASON_LABELS, BUCKET_LABELS,
  documentContentUrl,
  useDisclosuresQuery, useTimingQuery, useToleranceQuery,
  useIssueLoanEstimateMutation, useIssueClosingDisclosureMutation, useRecordReceiptMutation,
} from "./disclosuresApi"
import type { DisclosureResponse, TimingResponse, ToleranceResponse, IssueDisclosureRequest } from "./disclosuresApi"

const money = (n?: number) => (n == null ? "—" : n.toLocaleString("en-US", { style: "currency", currency: "USD", minimumFractionDigits: 2 }))
const day = (s?: string) => (s ? s.slice(0, 10) : "—")
const yesNo = (v?: boolean) =>
  v == null ? <span className="text-muted-foreground">—</span> : v ? <Badge>Yes</Badge> : <Badge variant="destructive">No</Badge>

function TimingCard({ timing }: { timing?: TimingResponse }) {
  return (
    <section className="rounded border p-3">
      <h3 className="mb-2 text-sm font-semibold">TRID Timing</h3>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
        <dt className="text-muted-foreground">Earliest consummation</dt><dd>{day(timing?.overallEarliestConsummation)}</dd>
        <dt className="text-muted-foreground">Consummation date</dt><dd>{day(timing?.consummationDate)}</dd>
        <dt className="text-muted-foreground">Timing satisfied</dt><dd>{yesNo(timing?.consummationSatisfiesTiming)}</dd>
        <dt className="text-muted-foreground">LE delivery deadline</dt><dd>{day(timing?.leDeliveryDeadline)}</dd>
        <dt className="text-muted-foreground">LE delivered on time</dt><dd>{yesNo(timing?.leDeliveredOnTime)}</dd>
        {timing?.revisedLeDeadline && (
          <Fragment><dt className="text-muted-foreground">Revised LE deadline</dt><dd>{day(timing.revisedLeDeadline)}</dd></Fragment>
        )}
      </dl>
    </section>
  )
}

function ToleranceCard({ tolerance }: { tolerance?: ToleranceResponse }) {
  const buckets = tolerance?.bucketTotals ?? {}
  const cmp = tolerance?.comparisonVsBaselineLe
  return (
    <section className="rounded border p-3">
      <h3 className="mb-2 text-sm font-semibold">Good-Faith Tolerance</h3>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
        {Object.entries(buckets).map(([k, v]) => (
          <Fragment key={k}>
            <dt className="text-muted-foreground">{BUCKET_LABELS[k] ?? k}</dt><dd>{money(v)}</dd>
          </Fragment>
        ))}
      </dl>
      {cmp == null ? (
        <p className="mt-2 text-sm text-muted-foreground">No Loan Estimate issued yet — no baseline to compare.</p>
      ) : (
        <div className="mt-2 space-y-1 text-sm">
          <p>Zero-bucket excess: <strong>{money(cmp.zeroBucketExcess)}</strong></p>
          <p>10%-bucket excess: <strong>{money(cmp.tenPercentExcess)}</strong></p>
          <p>{cmp.withinTolerance ? <Badge>Within tolerance</Badge> : <Badge variant="destructive">Over tolerance</Badge>}</p>
        </div>
      )}
    </section>
  )
}

function RecordReceiptDialog({ loanId, disclosure }: { loanId: string; disclosure: DisclosureResponse }) {
  const [open, setOpen] = useState(false)
  const [receivedAt, setReceivedAt] = useState("")
  const [error, setError] = useState<string | null>(null)
  const m = useRecordReceiptMutation(loanId)
  const submit = async () => {
    setError(null)
    try {
      await m.mutateAsync({ disclosureId: disclosure.id as string, receivedAt })
      toast.success("Receipt recorded")
      setOpen(false)
      setReceivedAt("")
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to record receipt")
    }
  }
  return (
    <Dialog open={open} onOpenChange={(o) => { setOpen(o); if (!o) { setReceivedAt(""); setError(null) } }}>
      <DialogTrigger asChild>
        <Button variant="ghost" size="sm" aria-label={`Record receipt ${disclosure.id}`}>Record receipt</Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader><DialogTitle>Record receipt</DialogTitle></DialogHeader>
        <div className="space-y-3">
          {error && <AlertBanner variant="error">{error}</AlertBanner>}
          <div className="space-y-1">
            <Label htmlFor="dsc-received">Date received</Label>
            <Input id="dsc-received" type="date" value={receivedAt} onChange={(e) => setReceivedAt(e.target.value)} />
          </div>
          <DialogFooter>
            <Button onClick={submit} disabled={!receivedAt || m.isPending}>Save</Button>
          </DialogFooter>
        </div>
      </DialogContent>
    </Dialog>
  )
}

export default function DisclosuresPage() {
  const { loanId = "" } = useParams()
  const { data: disclosures, isLoading } = useDisclosuresQuery(loanId)
  const { data: timing } = useTimingQuery(loanId)
  const { data: tolerance } = useToleranceQuery(loanId)
  const issueLe = useIssueLoanEstimateMutation(loanId)
  const issueCd = useIssueClosingDisclosureMutation(loanId)

  const onIssueLe = async (body: IssueDisclosureRequest) => {
    const r = await issueLe.mutateAsync(body)
    toast.success("Loan Estimate issued")
    return r
  }
  const onIssueCd = async (body: IssueDisclosureRequest) => {
    const r = await issueCd.mutateAsync(body)
    toast.success("Closing Disclosure issued")
    return r
  }

  const rows = disclosures ?? []

  return (
    <div>
      <SectionHeaderBar
        title="Disclosures"
        actions={
          <>
            <IssueDisclosureDialog kind="LOAN_ESTIMATE" pending={issueLe.isPending} onIssue={onIssueLe}
              trigger={<Button size="sm">Issue Loan Estimate</Button>} />
            <IssueDisclosureDialog kind="CLOSING_DISCLOSURE" pending={issueCd.isPending} onIssue={onIssueCd}
              trigger={<Button size="sm" variant="secondary">Issue Closing Disclosure</Button>} />
          </>
        }
      />
      <div className="grid gap-3 p-3 md:grid-cols-2">
        <TimingCard timing={timing} />
        <ToleranceCard tolerance={tolerance} />
      </div>
      {isLoading ? (
        <p className="p-4 text-sm text-muted-foreground">Loading…</p>
      ) : rows.length === 0 ? (
        <p className="p-4 text-sm text-muted-foreground">No disclosures issued yet.</p>
      ) : (
        <table className="w-full text-sm">
          <thead><tr className="border-b text-left">
            <th className="px-3 py-2 font-medium">Type</th>
            <th className="px-3 py-2 font-medium">Ver</th>
            <th className="px-3 py-2 font-medium">Status</th>
            <th className="px-3 py-2 font-medium">APR</th>
            <th className="px-3 py-2 font-medium">Finance charge</th>
            <th className="px-3 py-2 font-medium">Amount financed</th>
            <th className="px-3 py-2 font-medium">Total of payments</th>
            <th className="px-3 py-2 font-medium">TIP</th>
            <th className="px-3 py-2 font-medium">Delivery</th>
            <th className="px-3 py-2 font-medium">Delivered</th>
            <th className="px-3 py-2 font-medium">Received</th>
            <th className="px-3 py-2"></th>
          </tr></thead>
          <tbody>
            {rows.map((d) => (
              <tr key={d.id} className="border-b hover:bg-muted">
                <td className="px-3 py-2 whitespace-nowrap">
                  {d.kind ? KIND_LABELS[d.kind] : ""}
                  {d.resetTriggered && (
                    <Badge variant="destructive" className="ml-1"
                      title={(d.resetReasons ?? []).map((r) => RESET_REASON_LABELS[r] ?? r).join(", ")}>reset</Badge>
                  )}
                </td>
                <td className="px-3 py-2">{d.version}</td>
                <td className="px-3 py-2">{d.status ? STATUS_LABELS[d.status] : ""}</td>
                <td className="px-3 py-2">{d.apr == null ? "—" : `${d.apr}%`}</td>
                <td className="px-3 py-2">{money(d.financeCharge)}</td>
                <td className="px-3 py-2">{money(d.amountFinanced)}</td>
                <td className="px-3 py-2">{money(d.totalOfPayments)}</td>
                <td className="px-3 py-2">{money(d.tip)}</td>
                <td className="px-3 py-2">{d.deliveryMethod ? DELIVERY_LABELS[d.deliveryMethod] : ""}</td>
                <td className="px-3 py-2">{day(d.deliveredAt)}</td>
                <td className="px-3 py-2">{day(d.computedReceivedDate)}</td>
                <td className="px-3 py-2 space-x-2 whitespace-nowrap">
                  {(d.status === "PENDING" || d.status === "SENT") && <RecordReceiptDialog loanId={loanId} disclosure={d} />}
                  {d.documentId && (
                    <a className="text-sm underline" href={documentContentUrl(loanId, d.documentId)}
                       target="_blank" rel="noreferrer" aria-label={`View document ${d.id}`}>View document</a>
                  )}
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

- [ ] **Step 2: Typecheck**

Run: `npm run build`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/features/disclosures/DisclosuresPage.tsx
git commit -m "feat(disclosures): page with timing/tolerance cards, history table, receipt dialog"
```

---

### Task 4: `disclosures.test.tsx` — MSW integration tests

**Files:**
- Create: `src/features/disclosures/disclosures.test.tsx`

The behavioral safety net for the page. Mirrors `conditions.test.tsx` (MemoryRouter + QueryClient wrap, MSW `server.use(...)`, `PointerEventsCheckLevel.Never`). Every test registers handlers for all three GETs (the page fires list + timing + tolerance on mount). The MSW base host is `http://localhost:8080` (same as the conditions test — that is what `resolveBaseUrl()` returns under test config).

- [ ] **Step 1: Write the failing test**

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
import DisclosuresPage from "./DisclosuresPage"

const BASE = "http://localhost:8080/api/loans/L1/disclosures"

function wrap(children: ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}
function renderAt() {
  return render(wrap(
    <MemoryRouter initialEntries={["/loans/L1/disclosures"]}>
      <Routes><Route path="/loans/:loanId/disclosures" element={<DisclosuresPage />} /></Routes>
    </MemoryRouter>,
  ))
}
const ok = (data: unknown) => HttpResponse.json({ success: true, data })

/** Register timing + tolerance GETs (most tests don't assert them). */
function stubCompliance(opts?: { tolerance?: unknown; timing?: unknown }) {
  server.use(
    http.get(`${BASE}/timing`, () => ok(opts?.timing ?? {
      overallEarliestConsummation: null, consummationDate: null, consummationSatisfiesTiming: null,
      leDeliveryDeadline: null, leDeliveredOnTime: null, revisedLeDeadline: null,
    })),
    http.get(`${BASE}/tolerance`, () => ok(opts?.tolerance ?? { bucketTotals: {}, comparisonVsBaselineLe: null })),
  )
}

describe("DisclosuresPage", () => {
  it("shows empty state", async () => {
    server.use(http.get(BASE, () => ok([])))
    stubCompliance()
    renderAt()
    expect(await screen.findByText(/no disclosures issued yet/i)).toBeInTheDocument()
  })

  it("renders history rows with a reset badge", async () => {
    server.use(http.get(BASE, () => ok([
      { id: "D1", kind: "LOAN_ESTIMATE", version: 1, status: "SENT", apr: 6.125, financeCharge: 1000,
        amountFinanced: 200000, totalOfPayments: 360000, tip: 50, deliveryMethod: "EMAIL",
        resetTriggered: true, resetReasons: ["APR_INACCURATE"] },
    ])))
    stubCompliance()
    renderAt()
    expect(await screen.findByText("Loan Estimate")).toBeInTheDocument()
    expect(screen.getByText("6.125%")).toBeInTheDocument()
    expect(screen.getByText("reset")).toBeInTheDocument()
  })

  it("issues a Loan Estimate (POST loan-estimate)", async () => {
    let posted: Record<string, unknown> | null = null
    server.use(
      http.get(BASE, () => ok([])),
      http.post(`${BASE}/loan-estimate`, async ({ request }) => {
        posted = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ success: true, data: { id: "D1", kind: "LOAN_ESTIMATE", version: 1, status: "SENT" } })
      }),
    )
    stubCompliance()
    const user = userEvent.setup({ pointerEventsCheck: PointerEventsCheckLevel.Never })
    renderAt()
    await screen.findByText(/no disclosures issued yet/i)
    await user.click(screen.getByRole("button", { name: /issue loan estimate/i }))
    const dialog = screen.getByRole("dialog")
    await user.click(within(dialog).getByRole("button", { name: /^issue le$/i }))
    await waitFor(() => expect(posted).toMatchObject({ deliveryMethod: "EMAIL" }))
  })

  it("issues a Closing Disclosure with prepayment penalty", async () => {
    let posted: Record<string, unknown> | null = null
    server.use(
      http.get(BASE, () => ok([])),
      http.post(`${BASE}/closing-disclosure`, async ({ request }) => {
        posted = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ success: true, data: { id: "D2", kind: "CLOSING_DISCLOSURE", version: 1, status: "SENT" } })
      }),
    )
    stubCompliance()
    const user = userEvent.setup({ pointerEventsCheck: PointerEventsCheckLevel.Never })
    renderAt()
    await screen.findByText(/no disclosures issued yet/i)
    await user.click(screen.getByRole("button", { name: /issue closing disclosure/i }))
    const dialog = screen.getByRole("dialog")
    await user.click(within(dialog).getByLabelText(/prepayment penalty added/i))
    await user.click(within(dialog).getByRole("button", { name: /^issue cd$/i }))
    await waitFor(() => expect(posted).toMatchObject({ deliveryMethod: "EMAIL", prepaymentPenalty: true }))
  })

  it("records receipt for a SENT disclosure (POST receipt with receivedAt)", async () => {
    let posted: Record<string, unknown> | null = null
    server.use(
      http.get(BASE, () => ok([
        { id: "D1", kind: "LOAN_ESTIMATE", version: 1, status: "SENT", deliveryMethod: "EMAIL" },
      ])),
      http.post(`${BASE}/D1/receipt`, async ({ request }) => {
        posted = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ success: true, data: { id: "D1", kind: "LOAN_ESTIMATE", version: 1, status: "RECEIVED" } })
      }),
    )
    stubCompliance()
    const user = userEvent.setup({ pointerEventsCheck: PointerEventsCheckLevel.Never })
    renderAt()
    await user.click(await screen.findByLabelText("Record receipt D1"))
    const dialog = screen.getByRole("dialog")
    await user.type(within(dialog).getByLabelText(/date received/i), "2026-06-22")
    await user.click(within(dialog).getByRole("button", { name: /^save$/i }))
    await waitFor(() => expect(posted).toMatchObject({ receivedAt: "2026-06-22" }))
  })

  it("renders the tolerance baseline empty state when no LE issued", async () => {
    server.use(http.get(BASE, () => ok([])))
    stubCompliance({ tolerance: { bucketTotals: { ZERO: 500, TEN_PERCENT: 1200, UNLIMITED: 300 }, comparisonVsBaselineLe: null } })
    renderAt()
    expect(await screen.findByText(/no loan estimate issued yet/i)).toBeInTheDocument()
    expect(screen.getByText("Zero tolerance")).toBeInTheDocument()
  })

  it("renders timing card with on-time indicator", async () => {
    server.use(http.get(BASE, () => ok([])))
    stubCompliance({ timing: {
      overallEarliestConsummation: "2026-07-01", consummationDate: "2026-07-05",
      consummationSatisfiesTiming: true, leDeliveryDeadline: "2026-06-10", leDeliveredOnTime: true, revisedLeDeadline: null,
    } })
    renderAt()
    expect(await screen.findByText("TRID Timing")).toBeInTheDocument()
    expect(screen.getByText("2026-07-01")).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run test to verify behavior (red → green)**

Run: `npx vitest run src/features/disclosures/disclosures.test.tsx`
Expected: all 7 pass. If "Issue LE"/"Issue CD" button-name lookups are ambiguous, confirm the trigger labels ("Issue Loan Estimate" / "Issue Closing Disclosure") differ from the submit labels ("Issue LE" / "Issue CD") — they do, so `within(dialog).getByRole("button", { name: /^issue le$/i })` resolves only the submit button.

- [ ] **Step 3: Commit**

```bash
git add src/features/disclosures/disclosures.test.tsx
git commit -m "test(disclosures): MSW integration tests for the page"
```

---

### Task 5: Wire the route + sidebar

**Files:**
- Modify: `src/router.tsx`
- Modify: `src/components/shell/LoanSidebar.tsx`

- [ ] **Step 1: Add the route import + entry in `src/router.tsx`**

After the existing `import ConditionsPage from "@/features/conditions/ConditionsPage"` line, add:

```tsx
import DisclosuresPage from "@/features/disclosures/DisclosuresPage"
```

After the existing `{ path: "conditions", element: <ConditionsPage /> },` line, add:

```tsx
      { path: "disclosures", element: <DisclosuresPage /> },
```

- [ ] **Step 2: Enable the sidebar item in `src/components/shell/LoanSidebar.tsx`**

Remove `"Closing Disclosure"` from the `MENU_FUTURE` array so it reads:

```tsx
const MENU_FUTURE = [
  "Appraisal Manager", "Memory Maker",
  "Date Tracking", "Request to Withdraw/Cancel Loan",
  "Loan Calendar",
]
```

Immediately after the existing Conditions `NavLink` block (the one whose text is `Conditions`), add a Disclosures `NavLink`:

```tsx
            <NavLink
              to={`/loans/${loanId}/disclosures`}
              className={({ isActive }) => `border-b px-3 py-2 text-sm font-medium ${isActive ? "bg-secondary text-secondary-foreground" : "hover:bg-muted"}`}
            >
              Disclosures
            </NavLink>
```

- [ ] **Step 3: Verify build + lint**

Run: `npm run build && npm run lint`
Expected: PASS (no TS or lint errors).

- [ ] **Step 4: Commit**

```bash
git add src/router.tsx src/components/shell/LoanSidebar.tsx
git commit -m "feat(disclosures): wire route + enable sidebar nav item"
```

---

### Task 6: Deep-link the FeesPage "Preview CD" button

**Files:**
- Modify: `src/features/fees/FeesPage.tsx`

The disabled "Preview CD" button (in the `FeesEditor` component, which has `loanId` in scope) becomes a link to the loan's Disclosures page. `Button` already supports `asChild` (Slot-based).

- [ ] **Step 1: Add the `Link` import**

The file already imports `useParams` from `react-router-dom`. Change that import to also bring in `Link`:

```tsx
import { useParams, Link } from "react-router-dom"
```

- [ ] **Step 2: Replace the disabled "Preview CD" button**

Find:

```tsx
        <Button variant="secondary" size="sm" disabled title="Coming soon">Preview CD</Button>
```

Replace with:

```tsx
        <Button asChild variant="secondary" size="sm"><Link to={`/loans/${loanId}/disclosures`}>Preview CD</Link></Button>
```

(Leave the adjacent disabled "Run High Cost" button unchanged.)

- [ ] **Step 3: Verify build + lint + fees tests don't regress**

Run: `npm run build && npm run lint && npx vitest run src/features/fees`
Expected: PASS. (If there is no fees test directory, the vitest run reports "no test files" — that is fine; the build + lint gate the change.)

- [ ] **Step 4: Commit**

```bash
git add src/features/fees/FeesPage.tsx
git commit -m "feat(fees): link Preview CD to the loan Disclosures page"
```

---

## Final verification (after all tasks)

- [ ] Run the full suite: `npx vitest run` — all green (existing + the new disclosures tests).
- [ ] `npm run build && npm run lint` — clean.
- [ ] Live click-through (optional, owner-run): start the console (`VITE_AUTH_MODE=local`), open a loan → **Disclosures** in the sidebar → Issue Loan Estimate → confirm a row appears with TILA figures and the tolerance card flips from the baseline empty state.

## Self-Review notes (spec coverage)
- Issue LE/CD with delivery method + CD prepayment-penalty → Task 2 (dialog) + Task 3/4 (wiring + tests). ✓
- History table with TILA figures + reset badge → Task 3 + Task 4. ✓
- Record receipt → Task 3 (`RecordReceiptDialog`) + Task 4. ✓
- Timing + tolerance read-only cards incl. null-baseline empty state → Task 3 + Task 4. ✓
- View-generated-document link → Task 3 (`documentContentUrl`). ✓
- Route + sidebar (enable "Closing Disclosure" slot, relabel "Disclosures") → Task 5. ✓
- FeesPage "Preview CD" deep-link → Task 6. ✓
- Out of scope (per spec): `triggerCocId` from CoC, pipeline disclosure badge, other FeesPage compliance buttons. Not planned. ✓
- **Known v1 limitation (documented):** the "View document" link is a plain anchor to the documents content endpoint. In `local` auth mode (current dev) it works; under Cognito it would need a bearer token (a blob-fetch), which is the same unsolved concern as the existing `documents` feature — deferred follow-up, not a regression.
