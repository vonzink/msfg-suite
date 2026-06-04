# UWM EASE — Master Front-End / Back-End Reference

> Reverse-engineered consolidated reference for UWM EASE (the system we model). Reference only.

## 1. Platform at a glance
EASE is UWM's broker loan-origination portal — an ASP.NET MVC app (jQuery 1.8.3 + unobtrusive
validation, server-rendered Razor views loaded into a shell). Most loan pages render inside a shell
at `/.../LoanApplication#url=<Action>`; some load by direct controller route.

**Route template (the key pattern):**
```
/Lending/[{Area}/]Loan/{loanId}/{borrowerId}/{Controller}/{Action}
```
Three MVC areas: root (no area), `Origination`, `UClose`. `{ln}`/`{br}` = the two IDs.

## 2. Layout shell (every loan page)
Fixed top nav (logo, Start A Loan / Grow Your Business / Partner Support mega-menus, Your Pipeline,
Find My Loan). Left rail: Back to Pipeline, borrower-name banner, Loan Status / Loan Number /
Property Address, action buttons (Virtual Close Eligibility, Import Second Lien Loan, Loan Lab),
"Ultimate Loan Submission" progress card, MENU/SUMMARY tabbed sidebar. Main content under a blue
header bar. Floating "CR — Client Request" tab on the right edge.

**Sidebar menu:** 1003 ▾, Products & Pricing, Fees ▾, Change of Circumstance, AUS, Pre-Approval
Letter, Document Manager ▾, Appraisal Manager ▾, Closing Disclosure ▾, Memory Maker ▾, Conditions,
Date Tracking ▾, Contacts, Request to Withdraw/Cancel Loan, Amortization, Loan Calendar.

## 3. Endpoint & route registry (~65 routes)
- **Core 1003 (Origination area):** `Borrower`, `Income`, `Asset`, `LoanLiabilities`, `RealEstate`,
  `LoanInformation`, `Expense`, `DetailsOfTransaction`, `Declarations`, `GovernmentMonitoring`.
- **Pricing / underwriting:** `Pricing`, `Fee`, `AUS/OrderAUS`, `Credit`, `UltimateSubmission`.
- **Documents:** `Document` (Generate), `Document/PreApprovalDocument`, `Document/ViewDocuments`,
  `Document/ESignTracking`, `Document/AttactmentView` [sic], `Docless`, `LoanAttachment/AttachDocument`,
  `LoanAttachment/GetConditions`, `FileDownload`, `DownloadLoanFile`, `ProcessingAssistant`.
- **Appraisal:** `AppraisalManager`, `AppraisalTracker`.
- **Disclosures / CD:** `Document/ClosingDisclosureDetails`, `Document/ClosingDisclosureMessages`,
  `DisclosuresCalendar`, `History/DisclosureHistory`, `UCDResults/UCDResultsReview`,
  `ChangeOfCircumstancePreview`.
- **Tracking / misc:** `DateTracking`, `VVOETracking`, `Contacts`, `ChangeLoanStatus`, `Amortization`.
- **Memory Maker (gifting):** `ClosingGifts`, `ClosingGifts/History`, `ClosingGifts/RedeemProducts` (POST).
- **UClose area (closing):** `UClose/Dashboard`, `UClose/Invoice/InvoiceLanding`,
  `UClose/Escalations/GetEscalationsHistoryView`, `Eclose/VirtualeCloseSummary`. Scheduler (JS
  controller `ClosingScheduler`): `ClosingScheduler/UCloseReview`, `/UCloseSelectDate`,
  `/UCloseConditionsContactsComments`, `ClosingDay/OptInEClosing`, `GFE/FromUClose`. Dashboard
  utilities: `Dashboard/WildfirePilotOptInOptOut`, `Dashboard/SendTitleAgentReminderEmail`.
- **Cross-cutting / session:** `Account/TimeRemainingBeforeTimeout`, `Account/LogOff`,
  `Account/GoToOldEASE`, `Monitoring/GetSiteMaintenanceMessage`, `LoanApplication/LockMyLoan`,
  `LoanApplication/LoanSummary`, `LoanApplication/DisassociateHELOC`, `FnmaImport/ImportPiggybackLoan`,
  `FileDownload/CreateFnmaDownloadFile`, `Pipeline/GetPipelinePlayBook`.

## 4. Follow-up captures
**4a. Generate Documents — Package Type enum (`PackageTypeId`):** observed `8 = Closing Disclosure`,
`22 = Condition` on an Approved-With-Conditions loan (status-gated; full enum larger). Posts
`POST Document/Order`. "Providing your own LE from an outside LOS?" = TRID disclosure-ownership branch.

**4b. Memory Maker (`ClosingGifts`) — client-gifting module:** intro copy, "LO Partner Points"
balance, expandable accordion per Borrower and per Real Estate Agent, Grand Total (Points Used / USD
incl. tax+shipping+processing), "Confirm Your Order" (disabled-until-valid). Submits
`POST ClosingGifts/RedeemProducts`. Per-recipient model (`BorrowerData[n]` / agent):
- `LoppNoteOrder.TemplateId` — handwritten note: General / Purchase–General / Purchase–Repeat (`BR_TYC_*`)
- `LoppEmailOrder.TemplateId` — thank-you email: same three (`BR_TYE_*`)
- `LoppEmailNoteOrder.ThankYouNoteCardDesignOptionValue` — 10 card designs ("Memory Maker 1–10")
- `LoppEmailNoteOrder.ThankYouNoteFontStyleOptionValue` — 7 fonts (Angie, Jack, Jeremy, Jill, Terry, Tracy, Tribeca)
- `LoppProductOrders[i]` — physical gifts (cutting board, ice bucket, welcome mat, cookies, blanket)
  with `TemplateId` (monogram: first-initial / last-initial / Custom) + `PersonalizationId`
  (FirstName / LastName / Custom)

**4c. UClose 3.0 closing scheduler:** entry `UClose/Dashboard`. Eligible closing-type cards with
feature checklists — Hybrid eNote (Enhanced Electronic Security, Sign on Any Device, Sign Ahead, Eco
Friendly) vs In-Person (Secure only). "START UCLOSE" → scheduler. Downstream API:
`ClosingScheduler/UCloseReview` → `/UCloseSelectDate` → `/UCloseConditionsContactsComments`, with
`ClosingDay/OptInEClosing` and `GFE/FromUClose`.

## 5. Build notes (for our rebuild)
- Replicate the **loan-scoped route template** as the URL scheme (we already do: `/api/loans/{id}/...`;
  `{borrowerId}` → borrower sub-resources).
- The shell+hash content-swap is the UX mechanism (server-rendered partials into a host frame) — our
  build is API-first; a future frontend can adopt SPA equivalents.
- Forms rely on `data-val-*` unobtrusive validation (523 validation-attributed fields catalogued).
- Gift designs/fonts/templates and closing-type cards → static enum tables in our rebuild.
