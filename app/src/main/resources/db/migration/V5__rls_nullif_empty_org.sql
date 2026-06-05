-- Harden the RLS policies to handle the case where app.current_org is reset to its default
-- empty string (rather than NULL). PostgreSQL's RESET on a custom GUC that has been set at
-- least once in a session sets it to "" (empty default), not NULL. current_setting(name, true)
-- then returns "" rather than NULL, and ""::uuid throws an error instead of evaluating to NULL.
-- NULLIF(expr, '') returns NULL when expr is "" — so the comparison becomes NULL::uuid → NULL
-- → org_id = NULL → NULL (not true) → RLS denies the row. Fail-closed via false, not error.

drop policy if exists tenant_isolation on loan;
create policy tenant_isolation on loan
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

drop policy if exists tenant_isolation on borrower_party;
create policy tenant_isolation on borrower_party
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

drop policy if exists tenant_isolation on loan_status_history;
create policy tenant_isolation on loan_status_history
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid);
