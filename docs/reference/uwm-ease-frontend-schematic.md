# UWM EASE — Loan Origination Front-End Schematic

A specification for recreating the front-end design and functionality of the UWM EASE
broker loan-origination portal (`ease.uwm.com`). Focus: the individual loan workflow.

## 1. Global Design System

### Brand & Color
- **Primary blue** (`~#1B3A6B` deep navy): section header bars, panel titles
- **Accent orange** (`~#F26A21`): primary action buttons (Save, Generate, Lock, sidebar CTAs), active/alert highlights
- **Green** (`~#5BA829`): success states, completion badges ("100%"), upload-success banners, progress
- **Red** (`~#D0021B`): required-field markers, shortages/negative values, error banners
- **Neutral grays**: alternating table rows, disabled buttons, field backgrounds
- **White** content canvas on a light gray page background

### Typography
- Clean sans-serif (Helvetica/Arial family). Uppercase for section header bars and table column headers.
- Field labels: small gray uppercase/sentence-case above each input.

### Layout Grid
- **Top global header** (fixed, full width)
- **Two-column body**: left sidebar (~25% / fixed ~280px) + main content area (fluid)
- **Global footer** (4-column link directory + tagline)
- A floating **"CR / Client Request"** blue tab fixed to the right edge of the viewport.

### Reusable Components
- **Section header bar**: solid navy bar, uppercase white title, optional right-aligned action buttons.
- **Pill/rounded action buttons**: orange (primary), gray (disabled/secondary).
- **Info/alert banners**: light-tinted full-width strip with an icon (blue=info, yellow=warning, green=success, red=error).
- **Data tables**: uppercase column headers, zebra rows, orange underline above a bold totals row.
- **Sub-tab strips**: horizontal tabs at top of a content panel (e.g., per-borrower, or detail/sub-section).
- **Calculated/read-only fields**: gray-filled inputs.
- **Required marker**: red asterisk / red icon on the field.

---

## 2. Global Header (persistent)

Left: **UWM logo** (links to uwm.com).
Center nav (each is a mega-menu / link):
- **Start A Loan ▾** — full-width mega-menu, 3 logical columns:
  - *Tools col*: Easy Qualifier, Import A Loan, Create A Loan, Blink+, UWM Portal,
    Control Your Price Dashboard, Loan Swap (each = icon + bold title + one-line description, opens new tab).
  - *More Tools & Resources col*: UWM Rates, Loan Products, Turn Times, KEEP (title + desc + "Read More").
- **Grow Your Business →**
- **Partner Support →**
- **Your Pipeline ⧉** (external/new tab)
- **🔍 Find My Loan** (search)
- Utility icons (right): two app-switcher icons, a **CR** avatar/account chip, and a **hamburger ☰** menu.

---

## 3. Loan Workspace Layout (every loan page)

When inside a loan, the body is two columns:

### 3a. Left Sidebar (persistent within a loan)
Top to bottom:
1. **BACK TO PIPELINE** button (orange, full width).
2. **Borrower name** header bar (navy).
3. **Loan summary card**:
   - Loan Status (e.g., "Approved With Conditions")
   - Loan Number (with copy icon)
   - Property Address
4. **CTA buttons** (orange): Virtual Close Eligibility, Import Second Lien Loan, Loan Lab.
5. **"Ultimate Loan Submission"** widget — "A snapshot of your progress / See more" + document icon.
6. **Tabbed navigation block** with two tabs: **MENU** | **SUMMARY**.
   - The **MENU** tab lists all loan sections (see §4). Items with a ▾ expand into sub-items.

### 3b. Main Content Area
Renders the selected section. Always begins with a navy section-header bar.

### 3c. Footer (persistent)
4 columns of links + "Start Using Our Industry-Leading Products, Tools And Technology." tagline:
- **About UWM**: Why UWM, Media Resources, Events, Contact Us, Investor Relations, Careers
- **Account**: EASE Login, Team Member Email, Team Member Connect
- **Help**: Make A Payment, Report Suspicious Activity
- **Legal**: NMLS Consumer Access #3038, Mortgagee Clause, FHA/VA Licenses

---

## 4. Left Menu — Full Section Map

MENU
├── 1003 ▾  (the loan application — expandable)
│   ├── Personal Information
│   ├── Employment & Income
│   ├── Assets
│   ├── Liabilities
│   ├── Real Estate Owned
│   ├── Loan Information
│   ├── Housing Expenses
│   ├── Details of Transaction
│   ├── Declarations
│   └── Government Monitoring
├── Products & Pricing
├── Fees ▾
├── Change of Circumstance
├── AUS
├── Pre-Approval Letter
├── Document Manager ▾
├── Appraisal Manager ▾
├── Closing Disclosure ▾
├── Memory Maker ▾
├── Conditions
├── Date Tracking ▾
├── Contacts
├── Request to Withdraw/Cancel Loan
├── Amortization
└── Loan Calendar

---

## 5. Page-by-Page Specifications (Individual Loan Process)

### 5.1 — "Welcome" Loan Dashboard (landing/interstitial)
- Header bar: "WELCOME [User]".
- Green info banner: Virtual Close eligibility ("Congratulations! …Click here to learn more.").
- "You are currently in the loan for [Borrower] and can take the following actions:" → action list (e.g., "Attach loan conditions").
- **CD PROGRESS** circular progress gauge (animated, e.g., "86%") + a second loading tile.
- Used as a loading state between heavy pages.

### 5.2 — 1003 ▸ Personal Information  *(form layout)*
- Sub-tab strip = one tab per borrower + **ADD NEW BORROWER** button (right).
- **Personal Information** group: Primary checkbox; First/Middle/Last/Suffix; SSN; DOB; Marital Status (Married/Separated/Unmarried radios); No. of Dependents; Age of Dependents; Joined To Borrower; Tax Filing Address Same As; Present Address Same As; Citizenship (4 radios: US Citizen / Permanent Resident Alien / Non-Permanent Resident Alien / Foreign National); Veteran? checkbox; ADD ALTERNATE NAME button.
- **Contact Details**: Home/Cell/Work phone + Ext.; Email; "No Email" checkbox.
- **Unmarried Addendum**: Yes/No question.
- **Address History** (collapsible): Present Address (full address fields + Time at Residence + Ownership [Rent/Own/Rent Free] + Rent Amount + "Rent Verified?"), Previous Address(es), Mailing Address ("Same As Present" toggle).
- **Tax Filing Address History**: 4506-C line 3 & line 4 address blocks.
- Pattern: multi-column responsive form, state & country `<select>` dropdowns reused throughout.

### 5.3 — 1003 ▸ Employment & Income  *(table/grid layout)*
- Header actions: **Order Income**, **Income Calculator**, **Add New Income**.
- Table columns: Borrower Name | Income Type | Employer Name | Monthly Income (each row has a ▾ expander). Bold **TOTAL INCOME** row.
- **Doc-Less Income Verification Results** panel (status message).
- **Submit to Underwriting** button.

### 5.4 — 1003 ▸ Assets  *(table/grid layout)*
- Header actions: **Order Assets**, **Add New Asset**.
- Table: Asset Type | Account Number | Balance | Depositor | Verified. Bold **TOTAL ASSETS** row.
- **Doc-Less Asset Verification Results** panel.

### 5.5 — 1003 ▸ Liabilities  *(table/grid layout)*
- Header actions: **Add New Liability**, **Expand All**.
- Table: Liability Type | Account Number | Balance | Payment | Creditor | DTI (Include/Exclude). Bold **TOTAL LIABILITIES – PAYMENTS** row.

### 5.6 — 1003 ▸ Real Estate Owned  *(form layout w/ sub-tabs)*
- Sub-tabs: **Real Estate Details** | **Rental Income & Property Expenses**.
- Form: Subject Property checkbox; Real Estate Owners (per-borrower checkboxes); Borrower(s) Using This As Primary Address; address block; Intended Occupancy; Market Value; Property Type; Status. Required fields marked with red `*`.
- Buttons: **Save**, **Save & Add**, **Cancel**.

### 5.7 — 1003 ▸ Loan Information  *(form layout w/ sub-tabs)*
- Sub-tabs: **Mortgage Purpose, Types & Terms** | **Subject Property**.
- Fields: Mortgage Applied For; Amortization Type; Documentation Type; Interest Rate; Amortized No. of Payments; Mortgage Purpose; Sales Price; Appraised Value; Base/Financed/Total/Second Loan Amount; **calculated read-only** LTV / CLTV / TLTV; Qualifying Credit Score; Down Payment Amount; **Available Down Payment Source Options** (checkbox list: Gift, Sale of Assets, Checking, Stocks/Bonds, Equity variants, Savings, CD).
- **Save** button.

### 5.8 — 1003 ▸ Housing Expenses  *(comparison table)*
- Sub-tabs: **Summary** | one tab per borrower.
- Two-column comparison table: **Total Present Expense** vs. **Proposed Expense**.
- Rows: Monthly Rent, Mortgage Payment, Other Financing, Hazard Insurance, Monthly Taxes, Monthly MI, Monthly HOA Dues, Flood Insurance, Monthly Other → bold **Total Amount** row. **Save**.

### 5.9 — 1003 ▸ Details of Transaction  *(ledger table + summary card)*
- Description/Amount ledger, lines **A–N** (Sales Contract Price, Improvements, Land, Refinance balance, Credit cards/debts paid off, Borrower Closing Cost, Discount, **H. Total Due From Borrower**, I. Loan Amount, J. Other New Mortgage Loans, **K. Total Mortgage Loans**, L. Seller Credits, M. Other Credits, **N. Total Credits**).
- **Calculation** section: Total Due – Total Mortgage Loans & Credits = Cash From/To Borrower.
- Summary card (bottom-left): Available Assets, Cash From/To Borrower, **Shortage** (red), **More Info** button, **Save**.

### 5.10 — 1003 ▸ Declarations  *(per-borrower column Q&A)*
- One column per borrower across the top.
- Lettered questions **A–O** as Yes/No radio rows (occupancy, ownership interest, family relationship to seller, borrowing money, other mortgages, liens, co-signer, judgments, federal debt delinquency, lawsuits, foreclosure/deed-in-lieu, short sale, bankruptcy [+ type 7/11/12/13 checkboxes], first-time homebuyer, HUD counseling).
- Conditional sub-fields + **DU Explanation** dropdowns (Foreclosure, Delinquency, …).

### 5.11 — 1003 ▸ Government Monitoring (HMDA)  *(grouped checkbox form)*
- Sub-tabs per borrower. Info banner ("By default, the unchecked answer is 'No.'…").
- Demographic provided-through radios (Face-to-Face / Telephone / Fax or Mail / Email or Internet).
- "To be completed by Loan Officer" Yes/No rows (ethnicity/sex/race collected by observation).
- Grouped checkbox sets with conditional text inputs: **Ethnicity**, **Sex**, **Race/National Origin** ("I do not wish to provide" options). **Save** + federal NOTE text block.

### 5.12 — Products & Pricing  *(lock management)*
- Warning banner (Estimated Closing Date) + info banner ("apply price incentives before submitting lock requests").
- **Lock status detail grid**: Lock Status, Commitment Period, Interest Rate, Compensation Payer Type, Interviewer Email, Total Loan Amount, Locked By, Lock Date, Current Expiration.
- "Exact Rate Type" dropdown + action buttons: **Control Your Price**, **Extend Lock**, **Rate Change**, **Relock**.
- **Pricing Breakdown** table: Adjustment Name | Adjustment % | Dollar Amount → bold **Final Price**, **Compensation**, **Final Price After Compensation** rows.
- **Generate Lock Confirmation** button.

### 5.13 — AUS (One-Click AUS®)  *(credential form)*
- Status/error banner (e.g., "Unable to run… Loan must be in [status]").
- **Hard Credit Report** radio. "Issue / Re-issue" heading + helper text.
- **DU** group (Re-Issue / Use Existing radios; Credit Company, FHA Case #, Credit Username/Password [masked], Credit Ref per borrower).
- **LPA** group (Re-Issue radio; Merged Credit Company, Credit Ref per borrower, LPA Branch Number).
- **Run One-Click AUS®** button.

### 5.14 — Pre-Approval Letter
- **Generate Pre-Approval Letter** button.
- **View Pre-Approval Documents** table: Generated On | Document Type | Requested By, with pagination (Page X of Y, Per Page selector, Refresh Data, empty state "NO DOCUMENTS").

### 5.15 — Document Manager ▸ Attach Document  *(conditions upload — key page)*
- **ATTACH DOCUMENTS** header + blue instructional banner.
- Green success banner on upload ("Your document has been uploaded successfully").
- **Category / Document Type** `<select>` (TRAC, Senior Underwriter, Underwriter II, Master, Project Review, Disclosures/Compliance, All Conditions, 4506-C, Borrower Cert & Auth, Invoice) + **Collapse All**.
- **Collapsible condition category panels**, each with a completion badge (e.g., "2/2", "100%" green; "0" orange):
  - SENIOR UNDERWRITER (PTD), UNDERWRITER II (PTD), CLOSING (PTF)
  - Each panel lists numbered condition line items + "Upload Additional Documents" checkbox.
- **Expiring Documents** grid: tiles for Close By / Appraisal / Asset / CPL / Credit / Income / Insurance / etc., each with an expiration date; counter "You have N documents expiring within 3 business days."
- **DRAG & DROP** upload zone ("Drop files to upload (or click)" + **ADD FILES…**).
- **Comments** section + email-send field (UTRACK).

### 5.16 — Conditions  *(print-style condition list)*
- **Print** + **Collapse All** buttons. Filter links: **Not Cleared Conditions** / **Cleared Conditions** / **All Conditions**.
- **UTRACK** sharing block (email + Send; authorization acknowledgement text).
- Category / Document Type select + "Apply FastPass Applied" checkbox.
- Grouped conditions (Senior Underwriter PTD, Underwriter II PTD, Closing PTF). Each row: checkbox, condition #, description, **Attached Documents** + **Attached Comment** controls, "Upload Additional Documents."
- Closing NOTE block + bulleted closing-package list.

### 5.17 — Contacts  *(multi-panel)*
- **Broker contacts** (editable form): Loan Officer + Alternative Contact (Email, Name, Business Phone, Extension, Mobile Phone) → **Save Updates**.
- **UWM Contacts** panel (read-only): Account Executive, Senior Underwriter, Underwriter II, Closer — each with phone, email, "Share your experience at uShare" link.
- **Other Loan Contacts**: Escrow Agent (phone/email).
- **UTRACK** email-share widget (Email + Send + authorization text).

### 5.18 — Other menu endpoints (lighter-weight, same shell)
- **Fees ▾**, **Change of Circumstance**, **Appraisal Manager ▾**, **Closing Disclosure ▾**,
  **Memory Maker ▾**, **Date Tracking ▾**, **Request to Withdraw/Cancel Loan** (status change),
  **Amortization**, **Loan Calendar** (Disclosures Calendar). All render inside the same
  sidebar+content shell with a navy header bar and the table/form patterns above.

---

## 6. Interaction & Functionality Notes
- **SPA-ish behavior**: many sections load via a shared `/LoanApplication` shell that briefly shows the "Welcome" dashboard (circular CD-progress gauge) before swapping in content — build a loading interstitial state.
- **Persistent left rail**: loan summary card + menu stay fixed while content swaps.
- **Two table archetypes**: (a) entity grids with add/order buttons + totals row + verification panel (Income/Assets/Liabilities); (b) ledger/comparison tables with calculated totals (Details of Transaction, Housing Expenses).
- **Form archetype**: multi-column label-over-field groups, reused state/country selects, red-asterisk required markers, gray calculated read-only fields, Save / Save & Add / Cancel footer buttons.
- **Per-borrower tabs** recur (Personal Info, Housing, Declarations, Gov Monitoring).
- **Conditions workflow** is central: category-grouped, completion-badged, drag-&-drop upload, expiring-docs dashboard, UTRACK status sharing.
- **Color-coded status** everywhere: green=complete/verified, orange=action/attention, red=shortage/required/error.

---

## 7. Suggested Component Inventory (for build)
`<TopNav>` (with `<MegaMenu>`), `<LoanSidebar>` (`<LoanSummaryCard>`, `<MenuTabs>`, `<MenuList>`),
`<SectionHeaderBar>`, `<AlertBanner variant>`, `<ActionButton variant>`, `<DataTable>` (+ `<TotalsRow>`),
`<EntityGridPanel>` (Income/Assets/Liabilities), `<LedgerTable>`, `<ComparisonTable>`,
`<FormGroup>`/`<LabeledField>`/`<StateSelect>`/`<CountrySelect>`, `<BorrowerTabs>`,
`<CollapsiblePanel>` (+ `<CompletionBadge>`), `<DragDropUpload>`, `<ExpiringDocsGrid>`,
`<CircularProgress>` (CD Progress), `<UtrackShareWidget>`, `<ContactCard>`, `<Footer>`,
`<FloatingClientRequestTab>`.