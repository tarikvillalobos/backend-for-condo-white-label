ALTER TABLE v1_record_versions ADD COLUMN created_tx VARCHAR(32);
ALTER TABLE v1_record_versions ADD COLUMN closed_tx VARCHAR(32);
ALTER TABLE v1_snapshots ADD COLUMN visibility TEXT;
ALTER TABLE v1_snapshots ADD COLUMN creator_tx VARCHAR(32);
