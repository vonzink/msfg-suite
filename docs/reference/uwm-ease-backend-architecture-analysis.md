# UWM EASE — Back-End Architecture Analysis (Best-Effort, Inferred)

> Method: derived from client-side evidence only (JS globals, /bundles/ manifest,
> form `action` URLs, inline AJAX route strings, loaded 3rd-party hosts, RUM beacons).
> Server internals (DB, app servers, headers) are reasoned inference, not confirmed.

## 1. Platform & Framework (HIGH confidence)
The application is a **classic server-rendered ASP.NET MVC** app (.NET Framework era,
not .NET Core / not a JS SPA). Evidence:
- `__RequestVerificationToken` hidden inputs (6 on one page) = ASP.NET MVC anti-forgery.
- The **ASP.NET bundling/minification** system at `/Lending/bundles/...`
  (jquery, modernizr, bootstrap, jQGrid, jqueryval, theme, responsiveNavBundle).
- `jqueryval` bundle = **jQuery Unobtrusive Validation** (MVC `data-val-*` attributes),
  meaning model validation attributes are emitted server-side from C# view models.
- Page titles/markup rendered server-side (Razor views), not hydrated client JSON.

### Client-side stack (confirmed via window globals)
| Library | Version | Role |
|---|---|---|
| jQuery | 1.8.3 | DOM/AJAX (very old → legacy codebase, long-lived) |
| jQuery UI | 1.8.24 | widgets/datepickers/tabs |
| Bootstrap | (modal present) | layout, modals, the navy panels/tabs |
| jQGrid | (bundle) | the data grids (Income, Assets, Liabilities, paginated tables) |
| moment.js | 2.9.0 | date handling (Lock Date, expirations, calendar) |
| SignalR | present | **real-time push** (see §5) |
| Modernizr | (bundle) | feature detection / responsive |
| typeahead.js | present | autocomplete fields |

The old jQuery/jQuery-UI versions strongly suggest a mature, incrementally-maintained
WebForms→MVC lineage rather than a recent rewrite. There's even an `Account/GoToOldEASE`
route — implying this "EASE" is itself a newer skin over a legacy "old EASE" system.

## 2. URL Routing & Controller Surface (HIGH confidence)
Routing is conventional MVC with a **loan-scoped route prefix** and an optional **area**:

/Lending/[{Area}/]Loan/{loanId}/{borrowerId}/{Controller}/{Action}
└ Origination (area)   └ 1073741812  └ 972142650

- `loanId` (1073741812) and a borrower/application id (972142650) are baked into nearly
  every route → the loan is the primary aggregate; routing/authorization is loan-scoped.
- Two route families seen: `/Lending/Loan/...` (cross-cutting) and
  `/Lending/Origination/Loan/...` (the **Origination area**). Other areas implied:
  **UClose** (`/Lending/UClose/.../Invoice/...`) for closing.

### Controllers discovered (≥15)
`LoanApplication, Income, Asset(implied), LoanAttachment, Document, EClose,
ClosingScheduler, GFE, FnmaImport, FileDownload, Invoice, UCDResults, Escalations,
History, Monitoring, Account, Pipeline`

### Representative actions (the "API" — they return Razor partials/JSON, not REST/JSON-API)
- `Income/SaveIncome` (POST form)            – CRUD on income entities
- `LoanApplication/LoanSummary`              – sidebar summary partial
- `LoanApplication/LockMyLoan`               – rate-lock action
- `LoanApplication/DisassociateHELOC`        – 2nd-lien linkage
- `Document/ClosingDisclosureDetails` / `…Messages` – CD module
- `Document/ESignTracking`, `Document/AttactmentView`
- `LoanAttachment/AttachDocument` / `GetConditions` – conditions/doc upload
- `FnmaImport/ImportPiggybackLoan`           – **MISMO/Fannie 3.2/3.4 import**
- `FileDownload/CreateFnmaDownloadFile`      – **3.4 (ULAD) export**
- `ClosingScheduler/UCloseSelectDate|UCloseReview|UCloseConditionsContactsComments`
- `GFE/FromUClose`                           – fee/disclosure generation
- `UCDResults`                               – Uniform Closing Dataset feedback
- `Account/TimeRemainingBeforeTimeout`, `Account/LogOff`, `Account/GoToOldEASE`
- `Monitoring/GetSiteMaintenanceMessage`     – polled maintenance banner

> Interpretation: this is **action-oriented MVC**, not a REST API. The front end posts
> forms / requests partial HTML fragments (PRG pattern), with jQGrid pulling grid JSON.

## 3. The "SPA-ish" Shell (MEDIUM-HIGH confidence)
Pages load through a shared `…/LoanApplication#url=<Action>` shell. Behavior observed:
- The browser URL settles on `/LoanApplication` with a `#url=GetConditions` fragment.
- A "Welcome"/CD-progress interstitial renders while the inner partial loads.
→ Implies a **master loan workspace view** that swaps server-rendered partial views into
   the content pane via AJAX (jQuery `.load()`/`$.ajax`), keyed by the hash. This is a
   hand-rolled partial-navigation layer on top of MVC, **not** Angular/React/Vue
   (all three globals = absent).

## 4. Data Layer (INFERRED — low/medium confidence)
No DB is directly observable, but strong indirect signals:
- Loan-scoped numeric surrogate keys (`loanId`, `borrowerId`) → relational store
  (almost certainly **SQL Server** given the .NET stack).
- Entity-per-grid CRUD (`SaveIncome`, add/edit/delete rows on Assets/Liabilities/REO)
  → normalized tables: Loan → Borrowers → Income / Assets / Liabilities / REO / Expenses.
- **MISMO/ULAD** import & export (`FnmaImport`, `CreateFnmaDownloadFile`, `Export 3.4`)
  → the canonical loan model maps to the MISMO 3.x XML schema; likely an internal LOS
  data model with MISMO serializers.
- `UCDResults` + `GFE` + `ClosingScheduler` → separate closing/disclosure subsystem
  (the **UClose** area) sharing the same loan key.

## 5. Real-Time & Async (MEDIUM confidence)
- **SignalR** present → server-push for live updates: condition status changes, doc
  upload progress, lock confirmations, the `docvnotifications.js` script (doc/condition
  notifications), and the "documents expiring within 3 business days" widget.
- **Polling** for `Account/TimeRemainingBeforeTimeout` (session countdown) and
  `Monitoring/GetSiteMaintenanceMessage` (maintenance banner).

## 6. AuthN / AuthZ & Security (MEDIUM confidence)
- Forms-based / cookie session auth (ASP.NET). `Account/LogOff`, idle-timeout countdown,
  and `GoToOldEASE` SSO-style bridge to the legacy system.
- **Anti-forgery tokens** on every state-changing form (CSRF protection).
- **Google reCAPTCHA** loaded (bot protection on sensitive actions/login).
- A third-party bot/threat-detection beacon: `yonai.lordofthesuperfrogs.com/mon` and
  `rocky.lordofthesuperfrogs.com` (continuous POST telemetry — looks like a
  device-fingerprint/anti-fraud or bot-management vendor).
- Authorization is loan-scoped: every action carries `{loanId}/{borrowerId}`, so the
  server presumably validates the logged-in broker's entitlement to that loan per request.

## 7. Observability & Ops (HIGH confidence)
- **Dynatrace RUM** (`ruxitagentjs_…`, `bf…dynatrace.com` beacons) — full APM + real-user
  monitoring across multiple app IDs (the beacon lists 4 `app-…` GUIDs → the page spans
  several instrumented services/apps behind one experience).
- Server-side maintenance messaging endpoint → centralized ops/maintenance control.

## 8. Third-Party Integrations (observed hosts)
| Host | Purpose (inferred) |
|---|---|
| js.stripe.com | **Payments** (appraisal/invoice fees, Memory Maker gift orders) |
| server.iad.liveperson.net | **Live chat** support (the "CR / Client Request" + chatuwm) |
| images.contentstack.io | **Contentstack (headless CMS)** for marketing/resource content |
| kit/ka-p.fontawesome.com, fast.fonts.net | icons & web fonts |
| google-analytics / googletagmanager / doubleclick | analytics + ad tracking |
| connect.facebook.net, snap.licdn.com | Meta Pixel + LinkedIn Insight (marketing) |
| gstatic/recaptcha | bot protection |
| *.lordofthesuperfrogs.com | anti-fraud / bot-management telemetry |

Domain integrations implied by routes (vendor not exposed client-side): credit bureaus /
tri-merge ("One-Click AUS" DU + LPA, credit ref re-issue), AUS (Fannie **DU** / Freddie
**LPA**), appraisal AMC (Order Appraisal / Appraisal Tracker), e-sign (E-Sign Tracking),
income/asset verification (Doc-Less / VOI / VOE), and title/closing (UClose, UCD).

## 9. Architecture Summary (one paragraph)
EASE is a long-lived, server-rendered **ASP.NET MVC (.NET Framework)** broker LOS, organized
into MVC **areas** (Origination, UClose, etc.) over a single loan aggregate keyed by
`loanId`/`borrowerId`. The UI is jQuery + Bootstrap + jQGrid with a hand-built partial-view
navigation shell (not a JS SPA framework), real-time updates via **SignalR**, and unobtrusive
server-driven validation. The canonical loan model is **MISMO/ULAD**-aligned (3.2/3.4
import/export). Around the core sit a payments layer (Stripe), chat (LivePerson), a headless
CMS (Contentstack) for marketing surfaces, heavy **Dynatrace** observability, reCAPTCHA + a
bot-management vendor for security, and numerous mortgage-domain back-end integrations
(credit, DU/LPA AUS, appraisal, e-sign, VOI/VOE, title/closing/UCD) invoked through
action endpoints rather than a public REST API.

## 10. Confidence & Caveats
- HIGH: MVC framework, bundling, anti-forgery, routing shape, controller names, client libs,
  Dynatrace, third-party hosts, MISMO import/export.
- MEDIUM: SPA-shell mechanism, SignalR usage specifics, auth model, anti-fraud vendor role.
- LOW / INFERRED: database engine (SQL Server assumed), server OS/IIS version, exact .NET
  version, internal service boundaries. Raw response headers were not readable, so these
  remain educated inferences, not confirmed facts.

  # UWM EASE — Back-End Endpoint & Data-Model Reference (Deep Enumeration)

Harvested by instrumenting ~20 loan pages: form `action`s, inline AJAX route strings,
jQuery-unobtrusive-validation attributes, hidden view-model fields, and select enums.
Route template: /Lending/[{Area}/]Loan/{ln}/{br}/{Controller}/{Action}
  {ln} = loanId (e.g. 1073741812)   {br} = loanApplication/borrower id (e.g. 972142650)

================================================================================
## 1. WRITE ENDPOINTS — Form POSTs (17 confirmed)  [the "save" surface]
================================================================================
These are MVC [HttpPost] actions bound to C# view models (anti-forgery token on each).

1003 / Loan Application:
  POST Income/SaveIncome
  POST Asset/Save
  POST LoanLiabilities/Save
  POST RealEstate/Save
  POST LoanInformation/Save
  POST Expense/SaveAllExpenses
  POST DetailsOfTransaction/SaveDetailsOfTransaction
  POST Declarations/Save
  POST GovernmentMonitoring/Save
  POST LoanApplication            (the shell view post)

Pricing / Lock:
  POST /Lending/Origination/Pricing/ExecuteLockExtension   (note: NOT loan-scoped path;
                                                            pricing area is loan-agnostic)

AUS (dual-agency):
  POST AUS/SubmitOrderDU           (Fannie Desktop Underwriter)
  POST AUS/SubmitLoanProspector    (Freddie LPA / Loan Product Advisor)
  POST AUS/CompareDUandLP          (side-by-side findings compare)

Closing / Fees / Contacts:
  POST Fee/SaveGFE                 (GFE = fee/disclosure save)
  POST Contacts/SaveBrokerData
  (UClose) Invoice/… (invoice landing under UClose area)

> Pattern: each 1003 entity has a single `Save`/`SaveX` POST → CRUD is coarse-grained
> (whole-section save of a view model, not per-field PATCH). DTO names visible in
> validation (§4) confirm bound models like `income.*`, address blocks, etc.

================================================================================
## 2. READ / ACTION ENDPOINTS — AJAX & navigation routes (34 distinct)
================================================================================
### Cross-cutting (non-area)  /Lending/Loan/{ln}/{br}/…
  Account/TimeRemainingBeforeTimeout   – session idle countdown (polled)
  Account/LogOff                       – sign out
  Account/GoToOldEASE                  – bridge to legacy "old EASE" system
  Monitoring/GetSiteMaintenanceMessage – maintenance banner (polled)
  ClosingScheduler/UCloseSelectDate
  ClosingScheduler/UCloseReview
  ClosingScheduler/UCloseConditionsContactsComments
  ClosingScheduler/Fee

### Origination area  /Lending/Origination/Loan/{ln}/{br}/…
  LoanApplication/LoanSummary          – sidebar summary partial
  LoanApplication/LockMyLoan           – rate lock
  LoanApplication/DisassociateHELOC    – unlink 2nd lien / HELOC
  LoanApplication/SetDismissClicked    – dismiss banner state
  LoanApplication/ShowUClose
  LoanApplication/ShowUClose3OptInOutPopupAfterRollOut
  LoanApplication/UClose
  LoanApplication/UClose3OptInOptOut   – UClose 3.0 opt-in/out workflow
  AUS/OrderAUS                         – AUS landing
  Document/ClosingDisclosureDetails    – CD Information
  Document/ClosingDisclosureMessages   – CD Progress / messaging
  Document/ESignTracking
  Document/ViewDocumentsRefresh        – doc grid refresh feed
  Document/AttactmentView              – (sic) attachment viewer
  LoanAttachment/AttachDocument        – conditions upload
  LoanAttachment/GetConditions         – conditions list
  DisclosuresCalendar/GetEvents        – calendar event feed (JSON)
  History/DisclosureHistory            – disclosure audit history
  FileDownload/CreateFnmaDownloadFile  – export MISMO/Fannie 3.4 (ULAD)
  FnmaImport/ImportPiggybackLoan       – import 2nd-lien via MISMO
  GFE/FromUClose                       – pull fees from UClose
  EClose/VirtualECloseSummary          – virtual close eligibility
  UCDResults/UCDResultsReview          – Uniform Closing Dataset feedback
  Pipeline/GetPipelinePlayBook         – pipeline "playbook" data

### UClose area  /Lending/UClose/Loan/{ln}/{br}/…
  Invoice/InvoiceLanding               – invoices (Stripe-backed payments)
  Escalations/GetEscalationsHistoryView

> Data-feed endpoints (return JSON for grids/widgets): ViewDocumentsRefresh,
> DisclosuresCalendar/GetEvents, GetConditions, LoanSummary, GetPipelinePlayBook,
> GetEscalationsHistoryView, GetSiteMaintenanceMessage, TimeRemainingBeforeTimeout.

================================================================================
## 3. CONTROLLER SURFACE (≥18 controllers across 3 areas)
================================================================================
Root area:        Account, Monitoring, ClosingScheduler
Origination area: LoanApplication, Income, Asset, LoanLiabilities, RealEstate,
                  LoanInformation, Expense, DetailsOfTransaction, Declarations,
                  GovernmentMonitoring, Pricing, AUS, Document, LoanAttachment,
                  Fee, Contacts, FileDownload, FnmaImport, GFE, History,
                  DisclosuresCalendar, EClose, UCDResults, Pipeline
UClose area:      Invoice, Escalations

Mapping is 1 controller ≈ 1 menu section / 1 loan entity. The loan-scoped route
constraint ({ln}/{br} on nearly every action) implies a global authorization filter
that validates the broker's entitlement to that loan on every request.

================================================================================
## 4. DATA MODEL — inferred from 523 validated fields + DTO prefixes
================================================================================
jQuery-unobtrusive-validation exposed 523 client-validated fields. Rule types seen:
  required · number · range · regex · length · maxlength · date · email · equalto
→ these mirror DataAnnotations on C# view models ([Required], [Range], [RegularExpression],
  [StringLength], [EmailAddress], [Compare]).

### Entity: Income  (model prefix `income.`)
  income.EmployerName [StringLength + Regex]
  EmploymentId [int, Required] · IsEmploymentIncome · IsSelfEmployed
  IsCurrentEmployment · BankStatements [int] · Primary · IsURLA34Loan · IsSave
  → URLA 3.4 (ULAD) awareness baked into the model (IsURLA34Loan flag).

### Shared value object: Address (reused on Personal Info, REO, Mailing, Tax/4506-C)
  AddressLine1 [Required] · (Unit) · City · State <enum 50+ > · ZIP · Country <enum 30+ >
  Validation repeats per address instance → a reusable AddressViewModel component.

### Entity: Borrower / Personal Info
  Marital status enum value "Single" (UI label "Unmarried")
  Citizenship radio values: Citizen / Resident / Nonresident / National
  Ownership enum: Rent Free / Own / Rent
  Veteran (bool), Primary (bool), Joined-To / Same-As selects keyed by composite
    "loanId,borrowerId" values (e.g. "1073741812,913974683") → links co-borrowers.

### Entity: Loan Information
  Calculated/server-derived: LTV, CLTV, TLTV, Total Loan Amount (read-only in UI →
    computed server-side or via posted base values). Down-payment source = bitmask of checkboxes.

### Other entities (one Save model each): Asset, Liability (DTI include/exclude),
  RealEstate (occupancy/property type/status enums), Expense (present vs proposed grid),
  DetailsOfTransaction (A–N ledger lines → calculated cash-to-close), Declarations
  (A–O booleans + bankruptcy chapter flags 7/11/12/13 + DU explanation codes),
  GovernmentMonitoring (HMDA ethnicity/sex/race bitsets + "provided through" enum).

### ActiveTabId [int, Required]
  Present across multi-tab forms → server tracks which sub-tab/borrower posted.

================================================================================
## 5. INTEGRATIONS (confirmed via routes + loaded hosts)
================================================================================
AUS:        Fannie DU (SubmitOrderDU) + Freddie LPA (SubmitLoanProspector) + Compare
Credit:     tri-merge re-issue via DU/LPA credit-ref fields (One-Click AUS®)
MISMO/ULAD: FnmaImport/ImportPiggybackLoan (in) + FileDownload/CreateFnmaDownloadFile
            (out, 3.4) + IsURLA34Loan flag → canonical loan model is MISMO 3.x
Closing:    UClose area + ClosingScheduler + GFE + UCDResults (UCD) + EClose (eClose/eNote)
Payments:   js.stripe.com → Invoice (UClose) & Memory Maker gift orders
Docs/eSign: Document/ESignTracking, ViewDocuments, Digital File Archive, AttactmentView
Real-time:  SignalR (condition/doc notifications, lock confirmations) + polling
            (TimeRemainingBeforeTimeout, GetSiteMaintenanceMessage)
CMS:        Contentstack (images.contentstack.io) for marketing surfaces
Chat:       LivePerson (server.iad.liveperson.net) — the "Client Request / CR" tab
Observability: Dynatrace RUM/APM (multiple instrumented app GUIDs)
Security:   anti-forgery tokens, reCAPTCHA, *.lordofthesuperfrogs.com anti-fraud beacons
Legacy:     Account/GoToOldEASE → EASE is a modern shell over an older "old EASE" LOS

================================================================================
## 6. ARCHITECTURE INFERENCES (consolidated)
================================================================================
- ASP.NET MVC (.NET Framework), **3 MVC Areas**: root, Origination, UClose.
- One controller per loan section/entity; coarse-grained whole-section POST saves.
- Loan-scoped routing + per-request authorization keyed to {ln}/{br}.
- Canonical loan model is MISMO/ULAD 3.4-aware (import + export + IsURLA34 flags).
- Heavy server-side computation (LTV family, cash-to-close, pricing adjustments)
  returned as read-only fields → business logic lives in the C#/service layer, not JS.
- Reusable view-model components (AddressViewModel) drive the 523 validation fields.
- SignalR for push; jQGrid + partial-view AJAX for reads; PRG for writes.
- SQL Server assumed (numeric surrogate keys, .NET stack) — NOT directly observable.

================================================================================
## 7. CONFIDENCE
================================================================================
HIGH (directly observed): all 17 POST endpoints, all 34 routes, controller/area
  structure, 523 validation fields + rule types, DTO prefixes, AUS/MISMO/Stripe/UClose,
  SignalR, legacy-bridge, idle-timeout & maintenance polling.
MEDIUM (strong inference): entity table shapes, authorization model, calc-on-server.
LOW (assumption): database engine, IIS/.NET version, internal service boundaries
  (HTTP response headers were blocked from inspection throughout).

CAVEAT: This maps only the endpoints reachable from the pages I visited on one loan in
"Approved With Conditions" status. Other loan statuses/roles likely expose additional
actions (e.g., initial setup, full UClose scheduling, escalations workflow) not seen here.

