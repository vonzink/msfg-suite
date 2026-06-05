-- Harden the RLS policies:
--  (1) handle app.current_org reset to the empty-string default. After RESET, a custom GUC that
--      was set at least once returns '' (not NULL); ''::uuid throws. NULLIF(expr,'') -> NULL, so
--      the predicate becomes NULL (not true) -> RLS denies the row. Fail-closed via false, not error.
--  (2) make write-side enforcement explicit with WITH CHECK (same predicate), so INSERT/UPDATE are
--      visibly constrained to the current org (Postgres would derive this from USING for a FOR ALL
--      policy, but explicit is the standard hardening for security policies).

drop policy if exists tenant_isolation on loan;
create policy tenant_isolation on loan
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

drop policy if exists tenant_isolation on borrower_party;
create policy tenant_isolation on borrower_party
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);

drop policy if exists tenant_isolation on loan_status_history;
create policy tenant_isolation on loan_status_history
    using (org_id = nullif(current_setting('app.current_org', true), '')::uuid)
    with check (org_id = nullif(current_setting('app.current_org', true), '')::uuid);
