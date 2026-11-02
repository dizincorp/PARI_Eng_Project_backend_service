-- Dumps the structure of the PARI database as one JSON document for docs/tools/schema_dictionary.py.
-- Usage (database migrated by the backend):
--   psql -U pari -d pari -At -f docs/tools/schema.sql > schema.json
select json_build_object(
 'columns', (select json_agg(c order by c.table_name, c.ordinal_position) from (
    select table_name, column_name, ordinal_position, data_type, udt_name, character_maximum_length as len,
           numeric_precision as prec, numeric_scale as scale, is_nullable, column_default, is_generated, generation_expression
    from information_schema.columns
    where table_schema = 'public' and table_name not in ('flyway_schema_history', 'spatial_ref_sys', 'geography_columns', 'geometry_columns')) c),
 'constraints', (select json_agg(x order by x.t, x.n) from (
    select conrelid::regclass::text t, conname n, contype ty, pg_get_constraintdef(oid) def from pg_constraint
    where connamespace = 'public'::regnamespace
      and conrelid::regclass::text not in ('flyway_schema_history', 'spatial_ref_sys')) x),
 'indexes', (select json_agg(i order by i.tablename, i.indexname) from (
    select tablename, indexname, indexdef from pg_indexes
    where schemaname = 'public' and tablename not in ('flyway_schema_history', 'spatial_ref_sys') and indexname not like '%_pkey') i),
 'settings', (select json_agg(s order by s.setting_key) from (select setting_key, setting_value, description from platform_settings) s),
 'triggers', (select json_agg(t) from (
    select event_object_table, trigger_name, event_manipulation, action_timing from information_schema.triggers where trigger_schema = 'public') t),
 'flyway', (select json_agg(f order by installed_rank) from (
    select installed_rank, version, description, script, success from flyway_schema_history) f)
);
