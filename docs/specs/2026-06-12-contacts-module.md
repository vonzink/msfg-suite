# MSFG LOS — Contacts Module (frontend §6)

> Loan-scoped contact roster (listing agent, escrow officer, …) matching the shipped `ContactsPage`
> behind `LocalContactsAdapter` (`msfg-suite-web/src/features/contacts/contactsPort.ts`). The last
> frontend work-order item. Near-exact mirror of the `reo` module (loan-scoped CRUD, ordinal max+1),
> minus the summary endpoint. NOT money/security-critical → standard two-stage gates, no opus pass.

## Locked decisions
| Area | Decision |
|---|---|
| Module | New **`contacts`** gradle module (`com.msfg.los.contacts`), deps `:platform` + `:loan-core` — mirror `reo`. Migration **V16**. |
| Model | `Contact`: `role` enum **`ContactRole { LISTING_AGENT, SELLING_AGENT, ESCROW_OFFICER, TITLE_COMPANY, INSURANCE_AGENT, ATTORNEY, APPRAISER, OTHER }`** (mirrors the FE's 8 display strings; FE maps labels), `name` (@NotBlank), `company`/`phone`/`email` optional free-form strings (no format validation — additive later), `ordinal`. |
| API | `GET /api/loans/{loanId}/contacts` (ordinal,id order) · `POST` → 201 · `PATCH /{contactId}` (any subset of role/name/company/phone/email; name PATCHed blank → 400) · `DELETE /{contactId}` → 204. No bulk PUT (YAGNI — the FE's save-all is a localStorage artifact; id-CRUD maps its add/edit/delete rows directly). |
| Ordinal | max+1 on create (`findTopByLoanIdOrderByOrdinalDesc`), list order `OrdinalAscIdAsc` — the corrected fees/reo pattern from day one. |
| Tenancy | `TenantScopedEntity` + `findByIdAndOrgId` + loanId filter (cross-loan-same-org → 404); guard `assertCanAccess(loanService.get(loanId))` on every method; V16 RLS = the V15 pattern verbatim (FORCE, WITH CHECK, NULLIF GUC, org FK, app_user grants). |
| Validation | `role` @NotNull + `name` @NotBlank on create; PATCH: provided-field semantics (null = leave), blank name rejected. Unknown enum → 400 via existing handlers. |
| springdoc | `ContactRole`, `ContactResponse`, `CreateContactRequest`, `UpdateContactRequest` — sweep for simple-name uniqueness. |
| Tests | IT: create→201+ordinal 0, list order, PATCH subset + blank-name 400, DELETE 204 + ordinal-not-reused (max+1), missing role/name 400s (each branch), cross-org 404, cross-loan-same-org 404, PLATFORM_ADMIN 403, PROCESSOR org-wide 200, no-token 401; RLS IT (1 table); `OpenApiDocsIT` green; full build. |

## Out of scope
Contact↔party linking, dedupe, vCard import, per-role cardinality rules.
