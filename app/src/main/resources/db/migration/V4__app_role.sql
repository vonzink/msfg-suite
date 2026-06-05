-- Non-superuser application role that is SUBJECT to Row Level Security (used to prove + later
-- enforce DB-layer tenant isolation). PostgreSQL superusers AND table owners bypass RLS — FORCE
-- only overrides the owner's implicit bypass, not the superuser's — so RLS only actually enforces
-- for a non-owner, non-superuser role like this one.
--
-- Created NOLOGIN + passwordless on purpose: it is used via `SET ROLE app_user` (needs no
-- password) by RlsIT, and as the grant target. It ships NO usable credential to any environment.
--
-- DEPLOYMENT REQUIREMENT (to engage RLS for the *running app*): point the app's runtime datasource
-- at a non-owner role while Flyway/DDL runs as the owner — e.g. provision out-of-band per env
-- `ALTER ROLE app_user LOGIN PASSWORD '<from-secrets>'; GRANT CONNECT ON DATABASE <db> TO app_user;`
-- Until that is done, tenant isolation at runtime is enforced by the app layer
-- (Hibernate @TenantId + findByIdAndOrgId); RLS is a proven-but-dormant DB-layer backstop.
do $$ begin
  if not exists (select 1 from pg_roles where rolname = 'app_user') then
    create role app_user nosuperuser nocreatedb nocreaterole noinherit;
  end if;
end $$;

grant usage on schema public to app_user;
grant select, insert, update, delete on all tables in schema public to app_user;
grant usage, select on all sequences in schema public to app_user;
