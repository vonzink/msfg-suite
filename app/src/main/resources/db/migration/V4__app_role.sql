-- Create a non-superuser application role that is subject to Row Level Security.
-- PostgreSQL superusers (including the Testcontainers 'postgres' account) always bypass RLS,
-- even with FORCE ROW LEVEL SECURITY — that FORCE only overrides the table-owner's implicit
-- bypass, not the superuser bypass. Production app connections must run as 'app_user' (or
-- equivalent) so RLS actually enforces tenant isolation at the DB layer.
-- RlsIT uses SET ROLE app_user to prove the policy works for a real non-superuser.
do $$ begin
  if not exists (select 1 from pg_roles where rolname = 'app_user') then
    create role app_user login password 'app_password' noinherit nosuperuser nocreatedb nocreaterole;
  end if;
  -- Grant connect on current database (avoids hard-coding the db name which varies in TC).
  execute 'grant connect on database ' || current_database() || ' to app_user';
end $$;

grant usage on schema public to app_user;
grant select, insert, update, delete on all tables in schema public to app_user;
grant usage, select on all sequences in schema public to app_user;
