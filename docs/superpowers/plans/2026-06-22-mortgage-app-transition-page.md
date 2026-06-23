# mortgage-app Transition Page (funnel → passwordless → prefilled app) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the funnel→app hand-off with a `/continue` transition page in mortgage-app that greets the borrower, shows the data we captured, verifies them passwordless (email one-time code), and drops them into a prefilled application — with the loan born in suite at that authenticated moment.

**Architecture:** msfg.us's `/api/v1/applications` stops creating the loan; it mints a short-TTL signed JWT (`jose` HS256) carrying a **non-sensitive** `HandoffPayload` (no income/credit/SSN) and the wizard redirects to `app.msfgco.com/continue?t=<token>`. mortgage-app's new public `/continue` route decodes the token, renders the summary + an email-OTP panel behind a `PasswordlessAuthPort` (local dev adapter now; Cognito adapter deferred), and on verify calls the **existing** suite `POST /api/loans/intake` as the authenticated borrower, then seeds the existing `ApplicationForm` via its existing `sessionStorage['carryOverData']` prefill path. **Zero suite changes.**

**Tech Stack:** msfg.us = Next.js 16 / TS / vitest / `jose`. mortgage-app FE = React CRA / react-router / react-oidc-context / jest+RTL. No new deps.

**Shared `HandoffPayload` shape (both repos must agree):**
```
{
  sourceLeadId: string,
  loanPurpose: "Purchase" | "Refinance" | "CashOut",
  borrower:  { firstName: string, lastName: string, email: string, phone: string },
  property:  { addressLine: string|null, city: string|null, state: string|null, zipCode: string|null,
               propertyUse: string|null, propertyType: string|null, propertyValue: number|null },
  display:   { purchasePrice: number|null, downPaymentPercent: string|null },
  loanOfficer: { name: string, slug: string } | null
}
```
**Excludes** income, credit band, SSN (those are confirmed in the app, never in the URL).

**Shared dev constants:** dev borrower sub `00000000-0000-0000-0000-0000000000b0`, org `…00aa`, email `borrower@dev.local` (the apiClient already sends these as `X-Dev-*` when `REACT_APP_DEV_SUB` is set).

**Execution order:** Part M (msfg.us) and Part N (mortgage-app) are independent except the `HandoffPayload` shape (frozen above) and the `?t=` contract. Build M first (produces the token) or N first (consumes a hand-mint token) — either works; integrate via the running stack. Commits land on each repo's existing feature branch (msfg.us `feat/client-password-management`, mortgage-app `feat/borrower-suite-repoint-slice`). No push/merge/server-restart without owner approval (the conductor restarts + re-walks).

---

## File Structure
**msfg.us:**
- Create: `src/server/integrations/los/handoffToken.ts` (build payload + mint JWT)
- Create: `src/server/integrations/los/handoffToken.test.ts`
- Modify: `src/lib/env.ts` (+ `HANDOFF_TOKEN_SECRET`)
- Modify: `src/app/api/v1/applications/route.ts` (mint token, stop creating the loan)
- Modify: `src/components/apply/steps/FinishStep.tsx` (redirect to `/continue?t=…`)

**mortgage-app FE:**
- Create: `frontend/src/auth/handoffToken.js` (decode) + `…/handoffToken.test.js`
- Create: `frontend/src/auth/passwordless/PasswordlessAuthPort.js`, `DevPasswordlessAdapter.js`, `CognitoOtpAdapter.js` + `PasswordlessAuthPort.test.js`
- Create: `frontend/src/pages/continuePrefill.js` (HandoffPayload→intake DTO + →form carry-over) + `continuePrefill.test.js`
- Modify: `frontend/src/services/mortgageService.js` (+ `createLoanFromIntake`)
- Create: `frontend/src/pages/ContinuePage.js` + `ContinuePage.design.css` + `ContinuePage.test.js`
- Modify: `frontend/src/App.js` (public `/continue` route)

---

# PART M — msfg.us (hand-off token)

## Task M1: hand-off token mint + payload builder

**Files:**
- Modify: `src/lib/env.ts`
- Create: `src/server/integrations/los/handoffToken.ts`
- Create: `src/server/integrations/los/handoffToken.test.ts`

- [ ] **Step 1: env key.** In `src/lib/env.ts`, inside the `envSchema` `z.object({...})` near the `DEV_*` block, add:
```ts
  HANDOFF_TOKEN_SECRET: z.string().min(1).optional(),
```
(optional → minting no-ops to an unsigned token when unset, matching the repo's "integrations optional" pattern.)

- [ ] **Step 2: failing test** `src/server/integrations/los/handoffToken.test.ts`:
```ts
import { describe, it, expect } from "vitest";
import { buildHandoffPayload, mintHandoffToken, type HandoffPayload } from "./handoffToken";
import { jwtVerify } from "jose";

const LEAD = {
  firstName: "Ann", lastName: "Buyer", email: "ann@example.com", phone: "555-0100",
  intent: "BUY" as const, idempotencyKey: "lead-xyz", location: null,
  answers: { fields: { purchasePrice: 425000, downPayment: "20%", propertyUse: "Primary residence",
    propertyType: "Single Family", address: { line1: "1 Main St", city: "Denver", state: "CO", zip: "80202" } } },
};

describe("handoffToken", () => {
  it("builds a non-sensitive payload (no income/credit)", () => {
    const p = buildHandoffPayload(LEAD as any, { name: "Zachary Zink", slug: "zachary-zink" });
    expect(p.sourceLeadId).toBe("lead-xyz");
    expect(p.loanPurpose).toBe("Purchase");
    expect(p.borrower.email).toBe("ann@example.com");
    expect(p.property.city).toBe("Denver");
    expect(p.display.purchasePrice).toBe(425000);
    expect(p.display.downPaymentPercent).toBe("20%");
    expect(JSON.stringify(p)).not.toMatch(/income|creditBand|ssn/i);
  });

  it("mints a verifiable HS256 JWT round-trip", async () => {
    const p = buildHandoffPayload(LEAD as any, null);
    const token = await mintHandoffToken(p, "test-secret-0123456789");
    const { payload } = await jwtVerify(token, new TextEncoder().encode("test-secret-0123456789"),
      { algorithms: ["HS256"] });
    expect((payload as any).h.sourceLeadId).toBe("lead-xyz");
  });
});
```

- [ ] **Step 3: run — expect FAIL:** `npx vitest run src/server/integrations/los/handoffToken.test.ts` (module not found).

- [ ] **Step 4: implement** `src/server/integrations/los/handoffToken.ts`:
```ts
import { SignJWT } from "jose";

export type HandoffPayload = {
  sourceLeadId: string;
  loanPurpose: "Purchase" | "Refinance" | "CashOut";
  borrower: { firstName: string; lastName: string; email: string; phone: string };
  property: { addressLine: string | null; city: string | null; state: string | null; zipCode: string | null;
              propertyUse: string | null; propertyType: string | null; propertyValue: number | null };
  display: { purchasePrice: number | null; downPaymentPercent: string | null };
  loanOfficer: { name: string; slug: string } | null;
};

type LeadLike = {
  firstName: string; lastName: string; email: string; phone: string;
  intent: "BUY" | "REFI" | "CASH"; idempotencyKey: string;
  answers: { fields?: Record<string, unknown> } & Record<string, unknown>;
};

const PURPOSE: Record<LeadLike["intent"], HandoffPayload["loanPurpose"]> = {
  BUY: "Purchase", REFI: "Refinance", CASH: "CashOut",
};
const blank = (v: unknown): string | null => (typeof v === "string" && v.trim() !== "" ? v : null);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);

/** Build the NON-SENSITIVE hand-off payload from a persisted lead. Omits income/credit/SSN. */
export function buildHandoffPayload(
  lead: LeadLike, officer: { name: string; slug: string } | null,
): HandoffPayload {
  const f = lead.answers?.fields ?? {};
  const addr = (f.address ?? {}) as Record<string, unknown>;
  return {
    sourceLeadId: lead.idempotencyKey,
    loanPurpose: PURPOSE[lead.intent],
    borrower: { firstName: lead.firstName, lastName: lead.lastName, email: lead.email, phone: lead.phone },
    property: {
      addressLine: blank(addr.line1), city: blank(addr.city), state: blank(addr.state), zipCode: blank(addr.zip),
      propertyUse: blank(f.propertyUse), propertyType: blank(f.propertyType), propertyValue: num(f.homeValue),
    },
    display: { purchasePrice: num(f.purchasePrice), downPaymentPercent: blank(f.downPayment) },
    loanOfficer: officer,
  };
}

/** Mint a short-TTL HS256 JWT carrying the payload under claim `h`. */
export async function mintHandoffToken(payload: HandoffPayload, secret: string): Promise<string> {
  return new SignJWT({ h: payload })
    .setProtectedHeader({ alg: "HS256" })
    .setIssuedAt()
    .setIssuer("msfg.us")
    .setAudience("mortgage-app")
    .setExpirationTime("10m")
    .sign(new TextEncoder().encode(secret));
}
```

- [ ] **Step 5: run — expect PASS:** `npx vitest run src/server/integrations/los/handoffToken.test.ts`.

- [ ] **Step 6: commit** (branch only, no push):
```bash
cd /Users/zacharyzink/MSFG/WebProjects/msfg.us
git add src/lib/env.ts src/server/integrations/los/handoffToken.ts src/server/integrations/los/handoffToken.test.ts
git commit -m "feat(handoff): non-sensitive hand-off payload + HS256 token mint

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task M2: `/api/v1/applications` mints the token (stops creating the loan)

**Files:**
- Modify: `src/app/api/v1/applications/route.ts`

- [ ] **Step 1: rewrite the handler.** The route no longer creates the loan or calls the LOS. It looks up the lead, builds the payload, mints the token, returns `{ ok, handoffToken }`. Auth is NOT required (the borrower authenticates later at `/continue`); access is capability-based on the unguessable `leadId`. Replace the whole `POST` body with:
```ts
import { NextResponse } from "next/server";
import { getLeadById } from "@/server/leads/leadService";
import { OFFICERS } from "@/content/officers";
import { applicationHandoffSchema } from "@/validation/lead";
import { serverEnv } from "@/lib/env";
import { buildHandoffPayload, mintHandoffToken } from "@/server/integrations/los/handoffToken";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

function resolveOfficerName(slug: unknown): { name: string; slug: string } | null {
  if (typeof slug !== "string" || !slug) return null;
  const o = OFFICERS.find((x) => x.slug === slug);
  return o ? { name: o.name, slug: o.slug } : null;
}

/**
 * POST /api/v1/applications — funnel hand-off. Mints a short-TTL signed token carrying a NON-SENSITIVE
 * summary of the lead (no income/credit/SSN). The borrower authenticates + the loan is created later at
 * the app's /continue page. Capability-based on the unguessable leadId; the token contents are non-sensitive.
 * SECURITY follow-up: bind the lead to the browser session + rate-limit (tracked separately).
 */
export async function POST(req: Request) {
  let json: unknown;
  try { json = await req.json(); } catch {
    return NextResponse.json({ ok: false, error: "Invalid JSON body" }, { status: 400 });
  }
  const parsed = applicationHandoffSchema.safeParse(json);
  if (!parsed.success) {
    return NextResponse.json({ ok: false, error: parsed.error.flatten() }, { status: 400 });
  }
  const lead = await getLeadById(parsed.data.leadId);
  if (!lead) return NextResponse.json({ ok: false, error: "Lead not found" }, { status: 404 });

  const officer = resolveOfficerName(
    (lead.answers as { fields?: Record<string, unknown> })?.fields?.loanOfficer,
  );
  const payload = buildHandoffPayload(lead as never, officer);
  const secret = serverEnv.HANDOFF_TOKEN_SECRET ?? "local-unsigned-dev-secret";
  const handoffToken = await mintHandoffToken(payload, secret);

  return NextResponse.json({ ok: true, handoffToken },
    { headers: { "Cache-Control": "no-store" } });
}
```
> This drops the imports of `authConfigured`/`getSession`/`getIdToken`/`createLoanApplication`/`createLoanApplicationDev`/`funnelToIntake`. Leave `losClient.ts` and `funnelToIntake` in place (still used by the deferred real-LOS path / other code) — just stop importing them here. Locally `HANDOFF_TOKEN_SECRET` is unset → a fixed dev secret is used (the FE decodes without verifying — see N1).

- [ ] **Step 2: typecheck** `npx tsc --noEmit` — fix only new errors in this file (e.g. an unused-import lint). Report.

- [ ] **Step 3: commit:**
```bash
cd /Users/zacharyzink/MSFG/WebProjects/msfg.us
git add src/app/api/v1/applications/route.ts
git commit -m "feat(handoff): /api/v1/applications mints a hand-off token (loan now born at /continue)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task M3: FinishStep redirects to `/continue?t=<token>`

**Files:**
- Modify: `src/components/apply/steps/FinishStep.tsx`

- [ ] **Step 1: relax the fire-gate + capture the token.** The hand-off no longer needs a msfg.us session (auth happens at `/continue`). Change the `appId` state to `token`, and the useEffect to fire when `contact` + `leadId` are present (drop the auth-authenticated condition; keep `devBypass` no longer needed but harmless). Replace the state + effect:
```tsx
  const fired = useRef(false);
  const [handoff, setHandoff] = useState<"idle" | "sending" | "done">("idle");
  const [token, setToken] = useState<string | null>(null);

  useEffect(() => {
    if (fired.current || !contact || !leadId) return;
    fired.current = true;
    setHandoff("sending");
    const controller = new AbortController();
    fetch("/api/v1/applications", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      cache: "no-store",
      signal: controller.signal,
      body: JSON.stringify({ leadId }),
    }).then((r) => (r.ok ? r.json() : null))
      .then((d) => { if (d?.handoffToken) setToken(String(d.handoffToken)); })
      .catch(() => {})
      .finally(() => setHandoff("done"));
    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [contact, leadId]);
```

- [ ] **Step 2: change `continueHref`/`continueLabel`.** The borrower goes to `/continue` to verify + continue (no msfg.us sign-in path anymore). Replace:
```tsx
  const continueHref = token
    ? `${APP_URL}/continue?t=${encodeURIComponent(token)}`
    : APP_URL;
  const continueLabel = `Continue in the ${shortName} app`;
```
Remove the now-unused `auth.configured`/`auth.authenticated` references in the JSX welcome block if they cause lint errors (keep the officer line; drop the `Welcome back, {auth.user?.email}` block — the borrower isn't signed into msfg.us in this flow). Keep `useAuth()` import only if still referenced; otherwise remove it. Keep the `devBypass` line out (unused).

- [ ] **Step 3: verify build/typecheck.** `npx tsc --noEmit` (no new errors in this file). If msfg.us has no React-testing setup, the runtime check is the E2E walk; do not add an RTL test if `@testing-library/react` isn't a dep (grep package.json — if absent, skip a component test and rely on tsc + E2E).

- [ ] **Step 4: commit:**
```bash
cd /Users/zacharyzink/MSFG/WebProjects/msfg.us
git add src/components/apply/steps/FinishStep.tsx
git commit -m "feat(finish): redirect to app /continue?t=<token> (passwordless transition)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

# PART N — mortgage-app FE (the transition page)

> Build/test from `frontend/`: `CI=true npm test -- --watchAll=false <file>`. Commit on `feat/borrower-suite-repoint-slice`.

## Task N1: hand-off token decode

**Files:**
- Create: `frontend/src/auth/handoffToken.js`
- Create: `frontend/src/auth/handoffToken.test.js`

- [ ] **Step 1: failing test** `frontend/src/auth/handoffToken.test.js`. The FE only DECODES the JWT payload (no signature verify — the signature is server-side integrity; the FE consuming its own borrower's funnel data is not a trust boundary). Works for both signed JWTs and a local unsigned 3-part token.
```js
import { decodeHandoffToken } from './handoffToken';

// A JWT is base64url(header).base64url(payload).sig — decode reads the middle segment.
function makeToken(payloadObj) {
  const b64 = (o) => btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${b64({ alg: 'HS256' })}.${b64({ h: payloadObj })}.sig`;
}

test('decodes the h claim from a token', () => {
  const t = makeToken({ sourceLeadId: 'lead-1', loanPurpose: 'Purchase',
    borrower: { firstName: 'Ann' }, property: {}, display: {}, loanOfficer: null });
  const p = decodeHandoffToken(t);
  expect(p.sourceLeadId).toBe('lead-1');
  expect(p.borrower.firstName).toBe('Ann');
});

test('returns null on a malformed token', () => {
  expect(decodeHandoffToken('garbage')).toBeNull();
  expect(decodeHandoffToken(null)).toBeNull();
});
```

- [ ] **Step 2: run — expect FAIL:** `CI=true npm test -- --watchAll=false src/auth/handoffToken.test.js`.

- [ ] **Step 3: implement** `frontend/src/auth/handoffToken.js`:
```js
/**
 * Decode the NON-SENSITIVE hand-off payload from the `?t=` token.
 * The FE decodes (does NOT verify the signature) — the token carries the borrower's own
 * non-sensitive funnel summary, used only to render the page + seed the application; the
 * loan it creates is the borrower's own. Integrity verification, if needed, lives server-side.
 */
export function decodeHandoffToken(token) {
  if (!token || typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length < 2) return null;
  try {
    let b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    while (b64.length % 4) b64 += '=';
    const json = JSON.parse(decodeURIComponent(escape(atob(b64))));
    return json && json.h ? json.h : null;
  } catch {
    return null;
  }
}
```

- [ ] **Step 4: run — expect PASS.** **Step 5: commit:**
```bash
cd /Users/zacharyzink/MSFG/WebProjects/mortgage-app
git add frontend/src/auth/handoffToken.js frontend/src/auth/handoffToken.test.js
git commit -m "feat(continue): decode the funnel hand-off token

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task N2: PasswordlessAuthPort + dev adapter (+ Cognito stub)

**Files:**
- Create: `frontend/src/auth/passwordless/PasswordlessAuthPort.js`
- Create: `frontend/src/auth/passwordless/DevPasswordlessAdapter.js`
- Create: `frontend/src/auth/passwordless/CognitoOtpAdapter.js`
- Create: `frontend/src/auth/passwordless/PasswordlessAuthPort.test.js`

- [ ] **Step 1: failing test** `PasswordlessAuthPort.test.js`:
```js
import { getPasswordlessAuth } from './PasswordlessAuthPort';

describe('passwordless dev adapter', () => {
  const OLD = process.env.REACT_APP_DEV_SUB;
  beforeAll(() => { process.env.REACT_APP_DEV_SUB = '00000000-0000-0000-0000-0000000000b0'; });
  afterAll(() => { process.env.REACT_APP_DEV_SUB = OLD; });

  test('selects the dev adapter when REACT_APP_DEV_SUB is set', () => {
    expect(getPasswordlessAuth().kind).toBe('dev');
  });
  test('requestCode resolves sent; verifyCode accepts any code', async () => {
    const auth = getPasswordlessAuth();
    await expect(auth.requestCode('ann@example.com')).resolves.toEqual({ sent: true });
    await expect(auth.verifyCode('ann@example.com', '000000')).resolves.toMatchObject({ ok: true });
  });
});
```

- [ ] **Step 2: run — expect FAIL.**

- [ ] **Step 3: implement.** `DevPasswordlessAdapter.js`:
```js
/**
 * LOCAL-ONLY passwordless adapter. No real email/code — requestCode is a no-op success and
 * verifyCode accepts any code, resolving as the dev borrower. The app already treats the dev
 * borrower as signed-in (RequireAuth bypass + apiClient X-Dev-* headers), so no token is minted.
 */
export const DevPasswordlessAdapter = {
  kind: 'dev',
  async requestCode(_email) { return { sent: true }; },
  async verifyCode(_email, _code) { return { ok: true }; },
};
```
`CognitoOtpAdapter.js` (stub with the real contract — deferred, owner-gated):
```js
/**
 * Cognito email one-time-code adapter (DEFERRED — requires Cognito passwordless enabled by the owner:
 * custom-auth Lambda triggers (DefineAuthChallenge/CreateAuthChallenge/VerifyAuthChallenge) or managed
 * email-OTP). Contract:
 *   requestCode(email): InitiateAuth(CUSTOM_AUTH) → CreateAuthChallenge emails a 6-digit code → { sent }.
 *   verifyCode(email, code): RespondToAuthChallenge → on success store the resulting tokens where
 *     oidc-client-ts/apiClient read them (sessionStorage `oidc.user:<authority>:<clientId>`), then { ok }.
 * Throws "not-implemented" until the Cognito passwordless slice lands.
 */
export const CognitoOtpAdapter = {
  kind: 'cognito',
  async requestCode(_email) { throw new Error('CognitoOtpAdapter not implemented — Cognito passwordless not enabled'); },
  async verifyCode(_email, _code) { throw new Error('CognitoOtpAdapter not implemented — Cognito passwordless not enabled'); },
};
```
`PasswordlessAuthPort.js`:
```js
import { DevPasswordlessAdapter } from './DevPasswordlessAdapter';
import { CognitoOtpAdapter } from './CognitoOtpAdapter';

/**
 * Returns the active passwordless adapter. Local dev (REACT_APP_DEV_SUB set) → DevPasswordlessAdapter;
 * otherwise the Cognito email-OTP adapter (deferred). Mirrors the apiClient dev-vs-real split.
 */
export function getPasswordlessAuth() {
  return process.env.REACT_APP_DEV_SUB ? DevPasswordlessAdapter : CognitoOtpAdapter;
}
```

- [ ] **Step 4: run — expect PASS.** **Step 5: commit:**
```bash
cd /Users/zacharyzink/MSFG/WebProjects/mortgage-app
git add frontend/src/auth/passwordless/
git commit -m "feat(continue): passwordless auth port + dev adapter (Cognito OTP deferred)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task N3: prefill mappings + `createLoanFromIntake`

**Files:**
- Create: `frontend/src/pages/continuePrefill.js`
- Create: `frontend/src/pages/continuePrefill.test.js`
- Modify: `frontend/src/services/mortgageService.js`

- [ ] **Step 1: failing test** `continuePrefill.test.js` — two mappings: HandoffPayload → suite `IntakeRequest`, and HandoffPayload → the form's `carryOverData` shape.
```js
import { toIntakeRequest, toCarryOverData } from './continuePrefill';

const PAYLOAD = {
  sourceLeadId: 'lead-1', loanPurpose: 'Purchase',
  borrower: { firstName: 'Ann', lastName: 'Buyer', email: 'ann@example.com', phone: '555-0100' },
  property: { addressLine: '1 Main St', city: 'Denver', state: 'CO', zipCode: '80202',
              propertyUse: 'Primary residence', propertyType: 'Single Family', propertyValue: null },
  display: { purchasePrice: 425000, downPaymentPercent: '20%' },
  loanOfficer: { name: 'Zachary Zink', slug: 'zachary-zink' },
};

test('toIntakeRequest maps to suite IntakeRequest shape', () => {
  const r = toIntakeRequest(PAYLOAD);
  expect(r.sourceLeadId).toBe('lead-1');
  expect(r.loanPurpose).toBe('PURCHASE');                 // enum-mapped
  expect(r.borrower).toEqual({ firstName: 'Ann', lastName: 'Buyer', email: 'ann@example.com', phone: '555-0100' });
  expect(r.property).toEqual({ addressLine1: '1 Main St', city: 'Denver', state: 'CO',
    postalCode: '80202', estimatedValue: 425000 });
});

test('toCarryOverData maps to the form initial-values shape', () => {
  const d = toCarryOverData(PAYLOAD);
  expect(d.loanPurpose).toBe('Purchase');
  expect(d.propertyValue).toBe(425000);
  expect(d.property).toMatchObject({ addressLine: '1 Main St', city: 'Denver', state: 'CO', zipCode: '80202' });
  expect(d.borrowers[0]).toMatchObject({ firstName: 'Ann', lastName: 'Buyer', email: 'ann@example.com' });
});
```

- [ ] **Step 2: run — expect FAIL.**

- [ ] **Step 3: implement** `continuePrefill.js`:
```js
/** msfg.us loanPurpose → suite LoanPurposeType enum (suite: PURCHASE/REFINANCE/CONSTRUCTION/OTHER). */
function mapPurpose(p) {
  if (p === 'Purchase') return 'PURCHASE';
  if (p === 'Refinance' || p === 'CashOut') return 'REFINANCE';
  return 'OTHER';
}

/** HandoffPayload → suite POST /api/loans/intake body (matches origination IntakeRequest). */
export function toIntakeRequest(p) {
  const pr = p.property || {};
  return {
    sourceLeadId: p.sourceLeadId,
    loanPurpose: mapPurpose(p.loanPurpose),
    borrower: {
      firstName: p.borrower?.firstName ?? null,
      lastName: p.borrower?.lastName ?? null,
      email: p.borrower?.email ?? null,
      phone: p.borrower?.phone ?? null,
    },
    property: {
      addressLine1: pr.addressLine ?? null,
      city: pr.city ?? null,
      state: pr.state ?? null,
      postalCode: pr.zipCode ?? null,
      estimatedValue: p.display?.purchasePrice ?? pr.propertyValue ?? null,
    },
  };
}

/** HandoffPayload → the ApplicationForm carry-over shape (consumed via sessionStorage['carryOverData']). */
export function toCarryOverData(p) {
  const pr = p.property || {};
  return {
    loanPurpose: p.loanPurpose,
    propertyValue: p.display?.purchasePrice ?? pr.propertyValue ?? null,
    property: { addressLine: pr.addressLine ?? '', city: pr.city ?? '', state: pr.state ?? '', zipCode: pr.zipCode ?? '' },
    propertyUse: pr.propertyUse === 'Primary residence' ? 'Primary'
      : pr.propertyUse === 'Second home' ? 'Secondary'
      : pr.propertyUse === 'Investment property' ? 'Investment' : '',
    borrowers: [{
      firstName: p.borrower?.firstName ?? '',
      lastName: p.borrower?.lastName ?? '',
      email: p.borrower?.email ?? '',
      phone: p.borrower?.phone ?? '',
    }],
  };
}
```

- [ ] **Step 4: add the service fn.** In `frontend/src/services/mortgageService.js`, add to the exported object (near `getApplication`):
```js
  /**
   * Create the loan in suite from the funnel hand-off (the transition page calls this post-verify).
   * Hits the existing suite POST /api/loans/intake; idempotent on sourceLeadId; returns { loanId, loanNumber }.
   */
  createLoanFromIntake: async (intakeRequest) => {
    const { data } = await apiClient.post('/loans/intake', intakeRequest);
    // suite envelope { success, data: { loanId, loanNumber } }
    return (data && data.data) ? data.data : data;
  },
```

- [ ] **Step 5: run — expect PASS:** `CI=true npm test -- --watchAll=false src/pages/continuePrefill.test.js`.

- [ ] **Step 6: commit:**
```bash
cd /Users/zacharyzink/MSFG/WebProjects/mortgage-app
git add frontend/src/pages/continuePrefill.js frontend/src/pages/continuePrefill.test.js frontend/src/services/mortgageService.js
git commit -m "feat(continue): HandoffPayload→intake + →form mappings; createLoanFromIntake

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task N4: `ContinuePage` + public route

**Files:**
- Create: `frontend/src/pages/ContinuePage.js`
- Create: `frontend/src/pages/ContinuePage.design.css`
- Create: `frontend/src/pages/ContinuePage.test.js`
- Modify: `frontend/src/App.js`

- [ ] **Step 1: failing test** `ContinuePage.test.js` — drive the state machine with the auth port + service mocked. Put a token in the URL via `MemoryRouter`'s `initialEntries`.
```js
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import ContinuePage from './ContinuePage';

const mockNavigate = jest.fn();
jest.mock('react-router-dom', () => ({ ...jest.requireActual('react-router-dom'), useNavigate: () => mockNavigate }));
jest.mock('../services/mortgageService', () => ({ __esModule: true,
  default: { createLoanFromIntake: jest.fn().mockResolvedValue({ loanId: 'L1', loanNumber: '100' }) } }));
const requestCode = jest.fn().mockResolvedValue({ sent: true });
const verifyCode = jest.fn().mockResolvedValue({ ok: true });
jest.mock('../auth/passwordless/PasswordlessAuthPort', () => ({ getPasswordlessAuth: () => ({ kind: 'dev', requestCode, verifyCode }) }));

// token with h={sourceLeadId, loanPurpose:'Purchase', borrower:{firstName:'Ann',email:'ann@example.com'}, property:{}, display:{purchasePrice:425000}, loanOfficer:{name:'Zachary Zink'}}
const TOKEN = (() => {
  const b64 = (o) => btoa(JSON.stringify(o)).replace(/=+$/, '');
  return `${b64({ alg: 'HS256' })}.${b64({ h: { sourceLeadId: 'lead-1', loanPurpose: 'Purchase',
    borrower: { firstName: 'Ann', lastName: 'Buyer', email: 'ann@example.com', phone: '5' }, property: {},
    display: { purchasePrice: 425000 }, loanOfficer: { name: 'Zachary Zink', slug: 'z' } } })}.sig`;
})();

function renderPage() {
  return render(<MemoryRouter initialEntries={[`/continue?t=${TOKEN}`]}><ContinuePage /></MemoryRouter>);
}

test('greets the borrower and shows the captured summary', () => {
  renderPage();
  expect(screen.getByText(/Welcome, Ann/i)).toBeInTheDocument();
  expect(screen.getByText(/Zachary Zink/i)).toBeInTheDocument();
  expect(screen.getByText(/\$425,000/)).toBeInTheDocument();
});

test('email → request code → verify → creates loan and navigates to /apply', async () => {
  renderPage();
  fireEvent.click(screen.getByRole('button', { name: /code/i }));
  await waitFor(() => expect(requestCode).toHaveBeenCalledWith('ann@example.com'));
  // enter the code (6 inputs or one field — match your impl; here a single input named 'code')
  fireEvent.change(screen.getByLabelText(/code/i), { target: { value: '000000' } });
  fireEvent.click(screen.getByRole('button', { name: /verify|continue/i }));
  await waitFor(() => expect(verifyCode).toHaveBeenCalled());
  const svc = require('../services/mortgageService').default;
  await waitFor(() => expect(svc.createLoanFromIntake).toHaveBeenCalled());
  await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/apply'));
  expect(JSON.parse(sessionStorage.getItem('carryOverData')).borrowers[0].firstName).toBe('Ann');
});
```

- [ ] **Step 2: run — expect FAIL.**

- [ ] **Step 3: implement** `ContinuePage.js`. Read the token with `useSearchParams`, decode it, drive `phase` (`email`→`code`→`working`), call the auth port, then `createLoanFromIntake` + write `carryOverData` + `navigate('/apply')`. Model styling on `LandingPage.js` (use the shared `Icon`/`Button` from `../components/design`, and `ContinuePage.design.css`). Skeleton (fill the JSX to match the approved mockup — greeting, summary list, email/code panel, gated continue):
```jsx
import React, { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { toast } from 'react-toastify';
import Icon from '../components/design/Icon';
import Button from '../components/design/Button';
import mortgageService from '../services/mortgageService';
import { decodeHandoffToken } from '../auth/handoffToken';
import { getPasswordlessAuth } from '../auth/passwordless/PasswordlessAuthPort';
import { toIntakeRequest, toCarryOverData } from './continuePrefill';
import './ContinuePage.design.css';

const money = (n) => (typeof n === 'number' ? `$${n.toLocaleString('en-US')}` : '—');

export default function ContinuePage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const payload = useMemo(() => decodeHandoffToken(params.get('t')), [params]);
  const auth = useMemo(() => getPasswordlessAuth(), []);
  const [email, setEmail] = useState(payload?.borrower?.email || '');
  const [code, setCode] = useState('');
  const [phase, setPhase] = useState('email'); // email | code | working
  const [busy, setBusy] = useState(false);

  if (!payload) {
    return <div className="page continue-page"><p>This link has expired or is invalid. Please restart your application.</p></div>;
  }

  const sendCode = async () => {
    setBusy(true);
    try { await auth.requestCode(email); setPhase('code'); }
    catch { toast.error('Could not send a code. Try again.'); }
    finally { setBusy(false); }
  };

  const verifyAndContinue = async () => {
    setBusy(true);
    try {
      const res = await auth.verifyCode(email, code);
      if (!res?.ok) { toast.error('That code did not match. Try again.'); setBusy(false); return; }
      setPhase('working');
      await mortgageService.createLoanFromIntake(toIntakeRequest(payload));
      sessionStorage.setItem('carryOverData', JSON.stringify(toCarryOverData(payload)));
      navigate('/apply');
    } catch {
      toast.error('Something went wrong finishing sign-in. Try again.');
      setBusy(false);
    }
  };

  const first = payload.borrower?.firstName || 'there';
  return (
    <div className="page continue-page">
      {/* greeting */}
      <h1 className="continue-h1">Welcome, {first}</h1>
      {payload.loanOfficer?.name && (
        <p className="muted">Let's pick up where you left off — you'll be working with {payload.loanOfficer.name}.</p>
      )}
      {/* summary */}
      <div className="continue-summary card">
        <Row label="Loan purpose" value={payload.loanPurpose} />
        <Row label="Purchase price" value={money(payload.display?.purchasePrice)} />
        {payload.display?.downPaymentPercent && <Row label="Down payment" value={payload.display.downPaymentPercent} />}
        {payload.property?.propertyUse && <Row label="Property" value={payload.property.propertyUse} />}
      </div>
      {/* auth */}
      {phase !== 'working' ? (
        <div className="continue-auth card">
          <label htmlFor="cont-email">Email</label>
          <input id="cont-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} disabled={phase === 'code'} />
          {phase === 'email' ? (
            <Button variant="primary" onClick={sendCode} disabled={busy || !email}>Email me a 6-digit code</Button>
          ) : (
            <>
              <label htmlFor="cont-code">Code</label>
              <input id="cont-code" inputMode="numeric" value={code} onChange={(e) => setCode(e.target.value)} />
              <Button variant="primary" onClick={verifyAndContinue} disabled={busy || code.length < 4}>
                Verify & continue <Icon name="arrow-right" size={16} />
              </Button>
            </>
          )}
          <p className="muted continue-fine">New here? We'll create your account automatically — no password to remember.</p>
        </div>
      ) : (
        <p className="muted">Setting up your application…</p>
      )}
    </div>
  );
}

function Row({ label, value }) {
  return (
    <div className="continue-row">
      <span className="muted">{label}</span><span className="continue-val">{value || '—'}</span>
    </div>
  );
}
```
Create `ContinuePage.design.css` with minimal styles (mirror `LandingPage.design.css` class conventions: `.continue-page`, `.continue-summary`, `.continue-row`, `.continue-auth`, `.continue-val`, `.continue-fine`). Keep it simple; the design polish can follow.
> Verify the `Icon`/`Button` prop APIs against `LandingPage.js` usage (e.g. `<Button variant="primary" size="lg">`, `<Icon name="arrow-right" size={16} />`) and adjust names to real ones. If an icon name doesn't exist, use one that does (check `components/design/Icon`).

- [ ] **Step 4: add the public route.** In `frontend/src/App.js`, add an import and a route OUTSIDE `RequireAuth`, next to `/`, `/login`, `/signup`:
```jsx
import ContinuePage from './pages/ContinuePage';
```
```jsx
            <Route path="/continue" element={<ContinuePage />} />
```

- [ ] **Step 5: run — expect PASS:** `CI=true npm test -- --watchAll=false src/pages/ContinuePage.test.js`. Adjust the test's selectors to your final JSX (label text / button names) until green.

- [ ] **Step 6: full FE suite green:** `CI=true npm test -- --watchAll=false 2>&1 | tail -15` (was 151 passing; expect 151 + the new tests).

- [ ] **Step 7: commit:**
```bash
cd /Users/zacharyzink/MSFG/WebProjects/mortgage-app
git add frontend/src/pages/ContinuePage.js frontend/src/pages/ContinuePage.design.css frontend/src/pages/ContinuePage.test.js frontend/src/App.js
git commit -m "feat(continue): /continue transition page — passwordless verify + prefilled hand-off

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review (completed)
- **Spec coverage:** `/continue` page → N4. Passwordless seam + dev adapter + Cognito stub → N2. Hand-off token mint (msfg.us) → M1/M2; decode (mortgage-app) → N1. FinishStep redirect → M3. Post-auth call to existing suite intake → N3 (`createLoanFromIntake`) + N4 (orchestration). Prefill via existing `carryOverData` path → N3 (`toCarryOverData`) + N4. Non-sensitive token (no income/credit) → M1 `buildHandoffPayload` (+ test asserts it). Zero suite changes → confirmed (no suite task). Local-first → dev adapter (N2) + unsigned token decode (N1) + apiClient X-Dev-* (existing).
- **Type/shape consistency:** `HandoffPayload` identical in M1 (`handoffToken.ts`) and consumed in N1/N3/N4; `toIntakeRequest` output matches suite `IntakeRequest` (addressLine1/postalCode/estimatedValue, loanPurpose enum) per A4; `toCarryOverData` matches the form's edit-load field names (loanPurpose/propertyValue/property/propertyUse/borrowers[]) per `ApplicationForm.js`.
- **Verify-at-build points (read the real file first):** the `Icon`/`Button` prop names + available icon names in `components/design`; whether msfg.us has `@testing-library/react` (skip M3 RTL test if absent); the exact `carryOverData` field names the form's `reset()` consumes (mirror the edit-load mapping); `applicationHandoffSchema` shape (it validates `{ leadId }`).
- **Placeholders:** none — every step has concrete code/commands. The `ContinuePage` JSX is complete and runnable; CSS class polish is explicitly allowed to follow.
