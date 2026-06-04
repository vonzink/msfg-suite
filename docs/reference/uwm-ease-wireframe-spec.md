# UWM EASE — Companion Wireframe Spec (Individual Loan Process)

Box-drawing wireframes for every page in the loan workspace. Pair with the
"UWM EASE Front-End Schematic." Legend:

  [ Orange Btn ]   = primary action button (orange, rounded)
  ( Gray Btn )     = secondary/disabled button (gray, rounded)
  <select ▾>       = dropdown
  [____]           = text input        [____]*  = required (red asterisk)
  (•) / ( )        = radio selected / unselected
  [x] / [ ]        = checkbox checked / unchecked
  ▓▓▓ BAR ▓▓▓      = navy section-header bar (uppercase white text)
  ░░░ banner ░░░   = tinted info/alert banner
  {gray}           = read-only / calculated field (gray fill)
  ====TOTALS====   = bold totals row with orange top border

================================================================================
## A. GLOBAL SHELL (wraps every loan page)
================================================================================

┌──────────────────────────────────────────────────────────────────────────────┐
│ [UWM]   Start A Loan ▾   Grow Your Business →   Partner Support →             │ ← fixed top header
│         Your Pipeline ⧉   🔍 Find My Loan      [⇄][⊞] (CR)  ☰                 │
├───────────────┬────────────────────────────────────────────────────┬─────────┤
│ LEFT SIDEBAR  │  MAIN CONTENT AREA                                  │         │
│  (~280px)     │  (fluid)                                            │  (CR)   │← floating
│               │                                                     │ Client  │  right tab
│               │                                                     │ Request │
│               │                                                     │         │
├───────────────┴────────────────────────────────────────────────────┴─────────┤
│ FOOTER: 4 link columns + "Start Using Our Industry-Leading…" tagline          │
└──────────────────────────────────────────────────────────────────────────────┘

### A1. Start A Loan — Mega-Menu (full-width overlay, opens below header)
┌──────────────────────────────────────────────────────────────────────[ X ]──┐
│ [icon] Easy Qualifier ⧉          │  More Tools & Resources                    │
│        Price out scenarios…      │                                            │
│ [icon] Import A Loan ⧉           │  UWM Rates ⧉                               │
│        Upload your file…         │    Check out our rate sheet / Read More    │
│ [icon] Create A Loan ⧉           │  Loan Products                             │
│        Use our advanced form…    │    Explore all our loan products/Read More │
│ [icon] Blink+ ⧉                  │  Turn Times                                │
│        Manage POS, LOS, CRM…     │    Current turn times… / Read More         │
│ [icon] UWM Portal ⧉              │  KEEP                                       │
│        Bi-directional LOS link…  │    Retain past business… / Read More       │
│ [icon] Control Your Price ⧉      │                                            │
│        bps earned on loans…      │                                            │
│ [icon] Loan Swap ⧉               │                                            │
│        Prioritize loans…         │                                            │
└──────────────────────────────────────────────────────────────────────────────┘

### A2. Left Sidebar (persistent inside a loan)
┌─────────────────────────────┐
│ [  BACK TO PIPELINE  ]       │  ← orange, full width
│ ▓▓▓ ABBAS HUSSEIN ▓▓▓        │  ← borrower name bar
│ Loan Status                  │
│   Approved With Conditions   │
│ Loan Number      [⧉ copy]    │
│   1226322293                 │
│ Property Address             │
│   5345 S Flanders Way        │
│   Centennial, CO 80015       │
│ [ VIRTUAL CLOSE ELIGIBILITY ]│  ← orange CTAs
│ [ IMPORT SECOND LIEN LOAN   ]│
│ [      LOAN LAB            ] │
│ ┌─────────────────────────┐  │
│ │ Ultimate Loan Submission │ │  ← progress widget + doc icon
│ │ A snapshot of progress   │ │
│ │ See more                 │ │
│ └─────────────────────────┘  │
│ ┌── MENU ──┬── SUMMARY ──┐   │  ← two tabs
│ │ 1003              ▾    │   │  ← expandable
│ │ Products & Pricing     │   │
│ │ Fees              ▾    │   │
│ │ Change of Circumstance │   │
│ │ AUS                    │   │
│ │ Pre-Approval Letter    │   │
│ │ Document Manager  ▾    │   │
│ │ Appraisal Manager ▾    │   │
│ │ Closing Disclosure ▾   │   │
│ │ Memory Maker      ▾    │   │
│ │ Conditions             │   │
│ │ Date Tracking     ▾    │   │
│ │ Contacts               │   │
│ │ Request to Withdraw/   │   │
│ │   Cancel Loan          │   │
│ │ Amortization           │   │
│ │ Loan Calendar          │   │
│ └────────────────────────┘   │
└─────────────────────────────┘

### A3. Full Menu Tree (with verified sub-items)
1003 ▾
  ├ Personal Information      ├ Housing Expenses
  ├ Employment & Income       ├ Details of Transaction
  ├ Assets                    ├ Declarations
  ├ Liabilities              └ Government Monitoring
  ├ Real Estate Owned
  └ Loan Information
Products & Pricing
Fees ▾ ─────────── Fees · Invoices
Change of Circumstance
AUS
Pre-Approval Letter
Document Manager ▾ ─ Go Doc-less · Generate Documents · Processor Assist / PA+ ·
                     Attach Documents · View Documents · Digital File Archive ·
                     E-Sign Tracking · Export 3.4 Loan File
Appraisal Manager ▾ ─ Order Appraisal · Appraisal Tracker
Closing Disclosure ▾ ─ CD Information · CD Progress
Memory Maker ▾ ─ Place Order · Order Summary
Conditions
Date Tracking ▾ ─ Date Tracking · VVOE
Contacts
Request to Withdraw/Cancel Loan
Amortization
Loan Calendar

### A4. Footer (4 columns + social row + legal row)
┌──────────────────────────────────────────────────────────────────────────────┐
│ About UWM        Account            Help                  Legal                │
│  Why UWM          EASE Login         Make A Payment         NMLS #3038          │
│  Media Resources  Team Member Email  Report Suspicious      Mortgagee Clause    │
│  Events           Team Member Connect  Activity             FHA/VA Licenses     │
│  Contact Us                                                                    │
│  Investor Relations                          "Start Using Our Industry-Leading │
│  Careers                                      Products, Tools And Technology."  │
│  [f][x][in][ig][yt][th] social icons                                           │
│  Privacy Policy · Your Privacy Choices · Do Not Sell/Share · Terms of Use ·    │
│  Licensing Disclaimer · USA Patriot Act Notice                                 │
└──────────────────────────────────────────────────────────────────────────────┘

================================================================================
## B. WELCOME / LOADING DASHBOARD  (interstitial shown while pages load)
================================================================================
▓▓▓ WELCOME ZACHARY ▓▓▓
░ⓘ Congratulations! You are eligible for Virtual Close. Click here to learn more.░
You are currently in the loan for [Borrower] and can take the following actions:
   • Attach loan conditions

      ╭───────╮          ┌─────────┐
      │  86%  │          │ loading │
      ╰───────╯          └─────────┘
      CD PROGRESS        (second tile)
   (circular gauge,
    orange ring)

================================================================================
## C. 1003 ▸ PERSONAL INFORMATION   (form layout, per-borrower tabs)
================================================================================
▓▓▓ PERSONAL INFORMATION ▓▓▓                              ( ADD NEW BORROWER )
┌─ Abbas Hussein ─┬ Hussein-Fouad Hussein ┐  ← borrower tabs

Personal Information
[x] Primary
First Name[____]  Middle Name[____]  Last Name[____]  Suffix[____] ⓘ
SSN[____]         DOB[__/__/__]       Marital Status<Married|Separated|Unmarried ▾>
                                      No. Of Dependents[____]
Age Of Dependents[____]  Joined To Borrower<Please Select ▾>
Tax Filing Address Same As<Please Select ▾>   Present Address Same As<Please Select ▾>
Citizenship:  ( ) U.S. Citizen   ( ) Permanent Resident Alien
              (•) Non-Permanent Resident Alien   ( ) Foreign National      [ ] Veteran?
                                                              ( ADD ALTERNATE NAME )
Contact Details
Home Phone[____]  Cell Phone[____]  Work Phone[____] Ext[__]  Email[____]  [ ] No Email

Unmarried Addendum
Is there a person who is not your legal spouse, but who currently has real property
rights similar to those of a legal spouse?   ( ) Yes  (•) No

▓ ADDRESS HISTORY ▓  (collapsible ▾)
Present Address
Address Line 1[____]  Unit#[____]
City[____]  State<…50 states ▾>  ZIP[____]  Country<United States… ▾>
Time At Residence (Years)[____]  Ownership<Living Rent Free|Own|Rent ▾>
Rent Amount[$____]  [ ] Rent Verified ⓘ
* If < 2 yrs at present address, add Previous Address.
Previous Address Details … (repeats address block)
Mailing Address  [ ] Same As Present Address … (address block)

▓ TAX FILING ADDRESS HISTORY ▓
Tax Filing Address Details (Line 3 of 4506-C) [ ] Same As Present … (address block)
Previous address on last return (Line 4 of 4506-C) … (address block)

Notes: State select = 50 states + DC + territories. Country select = US + ~30.
       Marital "Unmarried" posts value "Single". Citizenship radio values:
       Citizen / Resident / Nonresident / National.

================================================================================
## D. 1003 ▸ EMPLOYMENT & INCOME   (entity grid)
================================================================================
▓▓▓ EMPLOYMENT & INCOME INFORMATION ▓▓▓
( ORDER INCOME )                    [ INCOME CALCULATOR ] [ ADD NEW INCOME ]
┌───────────────┬───────────────┬─────────────────┬─────────────────┐
│ BORROWER NAME │ INCOME TYPE   │ EMPLOYER NAME   │ MONTHLY INCOME ▾│
├───────────────┼───────────────┼─────────────────┼─────────────────┤
│ …row… (▾ expander per row)                                        │
├───────────────┴───────────────┴─────────────────┴─────────────────┤
│ ===================== TOTAL INCOME: $X ============================│
└────────────────────────────────────────────────────────────────────┘
▓ DOC-LESS INCOME VERIFICATION RESULTS ▓
░ⓘ Currently no VOI and Tax Transcript orders have been placed. ░
                                                  ( SUBMIT TO UNDERWRITING )

================================================================================
## E. 1003 ▸ ASSETS   (entity grid)
================================================================================
▓▓▓ ASSETS INFORMATION ▓▓▓
[ ORDER ASSETS ]                                        ( ADD NEW ASSET )
┌────────────┬─────────────────┬─────────┬───────────┬──────────┐
│ ASSET TYPE │ ACCOUNT NUMBER  │ BALANCE │ DEPOSITOR │ VERIFIED │
├────────────┴─────────────────┴─────────┴───────────┴──────────┤
│ ================ TOTAL ASSETS: $X =============================│
└────────────────────────────────────────────────────────────────┘
▓ DOC-LESS ASSET VERIFICATION RESULTS ▓
░ⓘ Currently no asset verification orders have been placed. ░

================================================================================
## F. 1003 ▸ LIABILITIES   (entity grid)
================================================================================
▓▓▓ LIABILITIES INFORMATION ▓▓▓
                                   ( ADD NEW LIABILITY )  [ EXPAND ALL ⤢ ]
┌────────────────┬────────────┬─────────┬─────────┬──────────┬─────────┐
│ LIABILITY TYPE │ ACCOUNT #  │ BALANCE │ PAYMENT │ CREDITOR │ DTI     │
├────────────────┴────────────┴─────────┴─────────┴──────────┴─────────┤
│ ============ TOTAL LIABILITIES - PAYMENTS: $X ========================│
└───────────────────────────────────────────────────────────────────────┘
DTI column value e.g. "Include" (include/exclude toggle).

================================================================================
## G. 1003 ▸ REAL ESTATE OWNED   (form, sub-tabs)
================================================================================
▓▓▓ REAL ESTATE OWNED ▓▓▓
┌ Real Estate Details ─┬ Rental Income & Property Expenses ┐
[ ] Subject Property
Real Estate Owners:            Borrower(s) Using This As Primary Address:
  [ ] Abbas Hussein              [ ] Abbas Hussein
  [ ] Hussein-Fouad Hussein      [ ] Hussein-Fouad Hussein
Address Line 1[____]*  Unit#[____]  Intended Occupancy<…▾>*
City[____]*  State<…▾>  ZIP[____]   Country<…▾>*
Market Value[$____]*  Property Type<…▾>*  Status<…▾>*
                                   ( SAVE ) ( SAVE & ADD ) ( CANCEL )

================================================================================
## H. 1003 ▸ LOAN INFORMATION   (form, sub-tabs, calculated fields)
================================================================================
▓▓▓ LOAN INFORMATION ▓▓▓
┌ Mortgage Purpose, Types & Terms ─┬ Subject Property ┐
Mortgage Applied For<Conventional ▾>  Amortization Type<Fixed ▾>
                                      │  Qualifying Credit Score[____]
Documentation Type<Full ▾>  Interest Rate[__.___ %]
                                      │  Down Payment Amount[$____]
Amortized No. Of Payments[360]  Mortgage Purpose<Purchase Home ▾>
                                      │  Available Down Payment Source Options: ⓘ
Sales Price[$____]  Appraised Value[$____]    [ ] Gift   [ ] Sale of Assets
                                              [ ] Checking [ ] Stocks/Bonds/MF
Base Loan Amount[$____]  Financed Fees[$____] [ ] Equity on Sold Property
                                              [ ] Equity from Pending Sale
Total Loan Amount{$____}ⓘ Second Loan Amt[$_] [ ] Equity from Subject Property
                                              [ ] Other funds source
LTV{__.___%}  CLTV{__.___%}                   [ ] Savings  [ ] Certificate of Deposit
TLTV{__.___%}
                                                                     ( SAVE )

================================================================================
## I. 1003 ▸ HOUSING EXPENSES   (two-column comparison)
================================================================================
▓▓▓ TOTAL PRESENT HOUSING EXPENSE ▓▓▓
┌ Summary ─┬ Abbas Hussein ─┬ Hussein-Fouad Hussein ┐
▓ TOTAL VS. PROPOSED EXPENSES ▓
                              TOTAL PRESENT EXPENSE      PROPOSED EXPENSE
Monthly Rent                  [$____]                    (—)
Mortgage Payment              [$____]                    [$____]
Other Financing Payment       [$____]                    [$____]
Hazard Insurance              [$____]                    [$____]
Monthly Taxes                 [$____]                    [$____]
Monthly MI                    [$____]                    [$____]
Monthly HOA Dues              [$____]                    [$____]
Flood Insurance               [$____]                    [$____]
Monthly Other                 [$____]                    [$____]
──────────────────────────────────────────────────────────────────
Total Amount                  [$____]                    [$____]
                                                              ( SAVE )

================================================================================
## J. 1003 ▸ DETAILS OF TRANSACTION   (ledger + summary card)
================================================================================
▓▓▓ DETAILS OF TRANSACTION ▓▓▓
┌─────────────────────────────────────────────────────────┬────────────┐
│ DESCRIPTION                                              │ AMOUNT     │
├─────────────────────────────────────────────────────────┼────────────┤
│ A. Sales Contract Price                                  │ [$____]    │
│ B. Improvements, Renovations and Repairs                 │ [$____]    │
│ C. Land (if acquired separately)                         │ [$____]    │
│ D. Refinance: Balance of Mortgage Loans paid off         │ $X         │
│ E. Credit Cards and Other Debts Paid Off                 │ $X         │
│ F. Borrower Closing Cost (incl. Prepaid/Escrow) ▾        │ $X         │
│ G. Discount (if borrower will pay)                       │ ($X)       │
│ H. TOTAL DUE FROM BORROWER(s) (A thru G)                 │ $X  ◄bold  │
│ I. Loan Amount                                           │ $X  ◄bold  │
│    (excl. financed MI $X ; financed MI amount $X)        │            │
│ J. Other New Mortgage Loans                              │ [$____]    │
│ K. TOTAL MORTGAGE LOANS (I and J)                        │ $X  ◄bold  │
│ L. Seller Credits                                        │ $X  ◄bold  │
│ M. Other Credits (paid fees, earnest money, …) ▾         │ $X         │
│ N. TOTAL CREDITS (L and M)                               │ $X  ◄bold  │
├─────────────────────────────────────────────────────────┼────────────┤
│ CALCULATION                                              │            │
│ Total Due From Borrower (Line H)                         │ $X         │
│ Less Total Mortgage Loans (K) and Total Credits (N)      │ -$X        │
│ Cash From/To Borrower (H − K − N)                        │ $X         │
└─────────────────────────────────────────────────────────┴────────────┘
┌──────────────────────────────┐
│ Available Assets ⊕   $X       │   ( MORE INFO )            ( SAVE )
│ Cash From/To Borrower  $X     │
│ Shortage:   $X  ◄red          │
└──────────────────────────────┘

================================================================================
## K. 1003 ▸ DECLARATIONS   (per-borrower columns, Yes/No rows)
================================================================================
▓▓▓ DECLARATIONS ▓▓▓
                                          ABBAS HUSSEIN    HUSSEIN-FOUAD HUSSEIN
A. Occupy as primary residence?            ( )Yes ( )No     ( )Yes ( )No
   If YES, ownership interest last 3 yrs?   ( )Yes ( )No     ( )Yes ( )No
     (1) Type of property owned             <Not Provided ▾> <Not Provided ▾>
     (2) How held title                     <Not Provided ▾> <Not Provided ▾>
B. Family/business relationship w/ seller?  ( )Yes ( )No     ( )Yes ( )No
C. Borrowing money for this transaction?    ( )Yes ( )No     ( )Yes ( )No
   If YES, amount?                          [$____]          [$____]
D.1 Applying for other mortgage?            ( )Yes ( )No     ( )Yes ( )No
D.2 Applying for new credit?                ( )Yes ( )No     ( )Yes ( )No
E. Property subject to priority lien (PACE)?( )Yes ( )No     ( )Yes ( )No
F. Co-signer/guarantor on undisclosed debt? ( )Yes ( )No     ( )Yes ( )No
G. Outstanding judgments?                   ( )Yes ( )No     ( )Yes ( )No
H. Delinquent/default on federal debt?      ( )Yes ( )No     ( )Yes ( )No
I. Party to a lawsuit w/ liability?         ( )Yes ( )No     ( )Yes ( )No
J. Conveyed title in lieu of foreclosure 7y?( )Yes ( )No     ( )Yes ( )No
K. Pre-foreclosure/short sale past 7y?      ( )Yes ( )No     ( )Yes ( )No
L. Property foreclosed past 7y?             ( )Yes ( )No     ( )Yes ( )No
M. Declared bankruptcy past 7y?             ( )Yes ( )No     ( )Yes ( )No
   If YES, type(s): [ ]7 [ ]11 [ ]12 [ ]13   (same for col 2)
N. Any occupant borrowers first-time buyer? ( )Yes ( )No
O. HUD Approved Counseling?                 ( )Yes ( )No     ( )Yes ( )No
DU Explanation: Foreclosure  <none ▾> ( )Yes ( )No   <none ▾> ( )Yes ( )No
DU Explanation: Delinquency  <none ▾> ( )Yes ( )No   <none ▾> ( )Yes ( )No

================================================================================
## L. 1003 ▸ GOVERNMENT MONITORING (HMDA)   (grouped checkboxes)
================================================================================
▓▓▓ GOVERNMENT MONITORING ▓▓▓
░ⓘ By default, the unchecked answer is "No." If checked, the answer is "Yes." ░
┌ Abbas Hussein ─┬ Hussein-Fouad Hussein ┐
The Demographic Information was provided through:
 ( )Face-to-Face  ( )Telephone Interview  ( )Fax or Mail  (•)Email or Internet
To be completed by Loan Officer (for application taken in person):
  Ethnicity collected by visual observation/surname?  ( )Yes (•)No
  Sex collected by visual observation/surname?        ( )Yes (•)No
  Race collected by visual observation/surname?       ( )Yes (•)No
Ethnicity   [ ]Hispanic or Latino  [ ]Mexican [ ]Puerto Rican [ ]Cuban
            [ ]Other Hispanic/Latino [_text_ "100 remaining"]
            [x]Not Hispanic or Latino   [ ]I do not wish to provide
Sex         [ ]Female  [x]Male  [ ]I do not wish to provide
Race/Nat'l  [ ]American Indian/Alaska Native [_tribe text 100 rem_]
Origin      [ ]Asian  [ ]Asian Indian [ ]Chinese [ ]Filipino [ ]Japanese
                      [ ]Korean [ ]Vietnamese [ ]Other Asian [_text_]
            [ ]Black or African American
            [ ]Native Hawaiian/Pacific Islander [ ]Native Hawaiian [ ]Samoan
                      [ ]Guamanian/Chamorro [ ]Other Pacific Islander [_text_]
            [x]White   [ ]I do not wish to provide
                                                              ( SAVE )
NOTE: [federal HMDA disclosure paragraph]

================================================================================
## M. PRODUCTS & PRICING   (lock management)
================================================================================
▓▓▓ PRODUCTS & PRICING ▓▓▓
░⚠ NOTE: Your Estimated Closing Date is [date]. An accurate date may prevent delays.░
░ⓘ NOTE: Please apply any price incentives before submitting lock requests. ░
▓ CONFORMING CONVENTIONAL 30 YEAR FIXED — MONTHLY ▓
┌──────────────────┬──────────────────┬──────────────────┬──────────────────┐
│ Lock Status      │ Commitment Period│ Interest Rate    │                  │
│   Locked         │   15 Day Lock    │   X.XXX          │                  │
│ Compensation     │ Interviewer Email│ Total Loan Amount│                  │
│   Payer: Lender  │   …@…            │   $X             │                  │
│ Locked By        │ Lock Date        │ Current Expiration│                 │
│   Zachary Zink   │   [datetime]     │   [date]         │                  │
└──────────────────┴──────────────────┴──────────────────┴──────────────────┘
Exact Rate Type<…▾>   ( CONTROL YOUR PRICE ) [ EXTEND LOCK ][ RATE CHANGE ][ RELOCK ]
▓ PRICING BREAKDOWN ▓
┌───────────────────────────────────┬──────────────┬──────────────┐
│ ADJUSTMENT NAME                   │ ADJUSTMENT % │ DOLLAR AMOUNT│
├───────────────────────────────────┼──────────────┼──────────────┤
│ Base Price                        │  -X.XXX      │ ($X)         │
│ UW Waive fee adjustment           │   X.XXX      │ $X           │
│ FICO …/LTV … Purch                 │   X.XXX      │ $X           │
│ FTHB Adjustment Cap               │  -X.XXX      │ ($X)         │
│ Final Price                       │  -X.XXX      │ ($X)  ◄bold  │
│ Compensation                      │   X.XXX      │ $X           │
│ Final Price After Compensation    │  -X.XXX      │ ($X)  ◄bold  │
└───────────────────────────────────┴──────────────┴──────────────┘
                                              [ GENERATE LOCK CONFIRMATION ]

================================================================================
## N. AUS — ONE-CLICK AUS®   (credential form)
================================================================================
▓▓▓ ONE-CLICK AUS® ▓▓▓
░✖ Unable to run One-Click AUS at this time because:
     • Loan must be in FILE IMPORTED, SETUP status to pull AUS  ░
One-Click AUS® Credit Credentials                                          ▴
(•) Hard Credit Report
                    Issue / Re-issue
   Use a credit reference number from any agency to re-issue the tri-merge report.
DU
  ( ) Re-Issue   (•) Use Existing
  Credit Company<Advantage Credit, Inc… ▾>      FHA Case Number[____]
  Credit Username[____]*                        Credit Password[••••••]*
  Credit Ref – Abbas Hussein[____]*             Credit Ref – Hussein-Fouad[____]
LPA
  ( ) Re-Issue
  Merged Credit Company<Select the Company ▾>   Credit Ref – Abbas[____]*
  Credit Ref – Hussein-Fouad[____]*             LPA Branch Number[____]*
                                              ( RUN ONE-CLICK AUS® )

================================================================================
## O. PRE-APPROVAL LETTER
================================================================================
▓▓▓ PRE-APPROVAL LETTER ▓▓▓               [