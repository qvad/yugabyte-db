CREATE EXTENSION pg_hint_plan;
SET pg_hint_plan.enable_hint_table TO on;

-- #23547 Ensure query against a view containing stored function doesn't crash
SELECT numeric_precision FROM information_schema.columns
    WHERE table_schema = 'pg_catalog' AND table_name = 'pg_class'
          AND column_name = 'relname';
