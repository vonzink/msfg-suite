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