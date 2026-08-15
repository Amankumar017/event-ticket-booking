-- Baseline migration.
--
-- The domain tables arrive in V2. This migration exists so Flyway owns the
-- schema from the first commit onwards, and so the shared pieces every later
-- table depends on are defined exactly once.

-- Keeps updated_at honest without every writer having to remember to set it.
create or replace function set_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

comment on function set_updated_at() is
    'Trigger function: stamps updated_at on every UPDATE. Attach with '
    '"create trigger <table>_set_updated_at before update on <table> '
    'for each row execute function set_updated_at()".';
