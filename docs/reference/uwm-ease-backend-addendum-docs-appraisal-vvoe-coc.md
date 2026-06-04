# UWM EASE — Back-End Addendum: Generate Documents · Appraisal Tracker · VVOE · Change of Circumstance

Same conventions as the other reference docs. Route template:
`/Lending/[{Area}/]Loan/{ln}/{br}/{Controller}/{Action}`

> Reverse-engineered intel about UWM EASE (the system we model). Reference only — not a
> prescription for our Java/Spring stack. Confidence: HIGH for endpoints/field names/enums
> (directly observed); MEDIUM for staged-review workflow logic (inferred).

---

## 1. GENERATE DOCUMENTS (Document Manager ▸ Generate Documents)

**Front-end:** Interviewer Information (Loan Officer Name / NMLS ID# / Phone — read-only,
NMLS-bound). "Are You Providing Your Own Loan Estimate From An Outside LOS?" Yes/No. Package
Type `<select>`. **GENERATE DOCUMENTS** button. Yellow Estimated-Closing-Date warning banner.

**Back-end:** `POST Document/Order` ← generates the disclosure/document package. Linked reads:
`LoanAttachment/GetConditions` & `/AttachDocument`, `Document/AttactmentView`,
`Document/ESignTracking`, `Document/ClosingDisclosureDetails` & `/ClosingDisclosureMessages`,
`History/DisclosureHistory`, `UCDResults/UCDResultsReview`, `ClosingScheduler/Fee`,
`UClose/Invoice/InvoiceLanding`, `UClose/Escalations/…`.

**Model & logic:**
- `Document/Order` posts `{ providingOwnLE: bool, packageType: enum }`.
- **TRID disclosure-ownership decision point:** Yes → broker supplies their own Loan Estimate
  (UWM won't re-disclose the LE); No → UWM generates the initial disclosure package.
- **Package Type enum = `PackageTypeId`** (status-gated; only packages valid for the loan's
  current state render). Observed on an "Approved With Conditions" loan: `8 = Closing Disclosure`,
  `22 = Condition` (+ blank placeholder). Non-contiguous IDs → the full server enum is larger.
- Tightly coupled to the UCD/eSign/Invoice pipeline → generation kicks off e-sign tracking +
  UCD validation + (if fees) invoicing.

---

## 2. APPRAISAL TRACKER (Appraisal Manager ▸ Appraisal Tracker)

**Front-end (read-mostly status view):** Borrower Information (Full Name · Home Phone · Email),
Mortgage Information (Mortgage Applied For · Purpose · Occupancy · Sales Price · Loan Amount ·
Property Address). "Appraisal Sent to UWM on `<date>` MST" with Order Number · Assigned To
(e.g. "UWM Appraisal Direct") · Ordered By. Order Tracker timeline (e.g. "Single Family Uniform
Residential Appraisal (FNMA 1004)", "Inspection Scheduled `<datetime MST>`", status milestones).

**Back-end (AppraisalTracker controller):**
- `GET  AppraisalTracker` — tracker landing/status
- `POST AppraisalTracker/SendPaymentLink` — email borrower a **Stripe** pay link
- `POST AppraisalTracker/PayNow` — in-app appraisal payment (**Stripe**)
- `POST AppraisalTracker/UpdatePaymentTracker` — payment status sync
- `POST AppraisalTracker/CancelOrder` — cancel appraisal order
- `POST AppraisalTracker/UpdateUnReadMessage` — appraiser/AMC message read state

**Model & logic:** Appraisal order entity `{ OrderNumber, ProductType (FNMA 1004/1073/etc.),
AssignedTo (AMC = "UWM Appraisal Direct"), OrderedBy, milestone timestamps, PaymentStatus,
Messages[] }`. Two payment paths (PayNow in-app vs SendPaymentLink to borrower) → **Stripe**.
`UpdateUnReadMessage` → two-way messaging thread with the appraiser/AMC. Integration: UWM
Appraisal Direct (in-house AMC) + a vendor order-status/inspection-milestone feed.

---

## 3. VVOE — Verbal Verification of Employment (Date Tracking ▸ VVOE)

**Front-end (READ-ONLY tracker; renders only inside the shell):** Estimated Closing Date; one
card per borrower/employer with Employer Name · Verification Method · Verification Source ·
Third Party Verified Number · Number Of Attempts. Info banner: available in "Approved With
Conditions" status; contact vvoe@uwm.com.

**Back-end:** No VVOE-specific AJAX routes (display-only); rendered via shell partial
`…#url=VVOETracking` under the `DateTracking` controller. Direct GET to `/…/VVOETracking` 500s
(valid only inside the LoanApplication shell context).

**Model & logic:** VVOE entity per employment `{ EmployerName, VerificationMethod,
VerificationSource, ThirdPartyVerifiedNumber, NumberOfAttempts, status gated by loan status }`.
Populated server-side by a third-party VOE vendor / UWM's VVOE team (brokers view only).
**Status-gated:** only surfaces in "Approved With Conditions" → workflow-state guard on the action.

---

## 4. CHANGE OF CIRCUMSTANCE (TRID fee-tolerance / re-disclosure workflow)

**Front-end (4 sub-tabs, AJAX-lazy):** `Loan Structure | Fees | Preview Changes | CoC Request History`.
Loan Structure tab: Reason for change `<select>` (REQUIRED), Date of Discovery, Requested Reason
Detail; editable fee lines (Underwriting Fee · TRAC Fee · TRAC+ Fee · PA+ Fee · UFMIP/Funding Fee
Type); Compensation Plan per item `<Select | Borrower Paid>`; **Save Changes**. Info banner:
"Fees will automatically update once 'Submit Changes' is clicked. Fee increases may result in a
temporary tolerance cure while our team reviews the request."

**Back-end:** `POST ChangeOfCircumstancePreview/InsertPendingChanges` ← stages pending CoC.
Related: `History/DisclosureHistory`, `Document/ClosingDisclosureDetails` & `/ClosingDisclosureMessages`,
`ClosingScheduler/Fee`.

**Data model (confirmed — collection-bound):**
```
ChangeOfCircumstanceItems[n].{
   SelectedRequestReason            (enum, see below)
   RequestedReasonDetail            (free text)
   DateOfDiscovery / DiscoveryDate
   CompensationPlanRequestedValue   (enum: Select | Borrower Paid | …)
   <fee value fields per line item>
}
```
**SelectedRequestReason enum (== CFPB TRID valid changed-circumstance set):**
- Acts of God, war, disaster or other emergency
- Borrower requested change
- Changes or inaccuracies in information relating to the Borrower or transaction
- New information regarding the Borrower or transaction
- Other

**Logic:** the regulatory **TRID Change-of-Circumstance / fee-tolerance engine**. Flow: edit fees →
Preview (diff before/after) → select valid reason + discovery date → `InsertPendingChanges` stages a
pending request → UWM team reviews (temporary tolerance cure) → redisclosed Closing Disclosure
generated (ties to `Document/ClosingDisclosure*` + `History/DisclosureHistory` audit trail).
Date-of-Discovery + reason justify and time-stamp the tolerance reset (3-business-day redisclosure
clock). Collection model → multiple fee changes batched into one CoC request.

---

## 5. New endpoints (delta)
`POST Document/Order` · `GET AppraisalTracker` · `POST AppraisalTracker/{SendPaymentLink,PayNow,UpdatePaymentTracker,CancelOrder,UpdateUnReadMessage}` · `POST ChangeOfCircumstancePreview/InsertPendingChanges` · (VVOETracking — shell partial under DateTracking).
New controllers: `AppraisalTracker`, `ChangeOfCircumstancePreview`, `DateTracking` (VVOE view).

## 6. Cross-cutting takeaways
- **Stripe** payment surfaces confirmed: AppraisalTracker + UClose/Invoice.
- Compliance engines are first-class: Generate Documents (TRID disclosure ownership), Change of
  Circumstance (tolerance cure + redisclosure), VVOE (verification audit) — all feeding the
  Closing Disclosure / DisclosureHistory / UCD subsystem.
- Read-only "tracker" views (Appraisal, VVOE) are server-rendered status dashboards fed by
  back-office/vendor data, distinct from the editable 1003 entity forms.
- Status-gating is pervasive (workflow-state guards on controller actions).
