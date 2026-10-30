-- Preserve historical snapshots while retiring their application mapping.
ALTER TABLE reading_run ALTER COLUMN snapshot SET DEFAULT '{}'::jsonb;
