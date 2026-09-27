ALTER TABLE v1_record_versions ADD COLUMN logical_id VARCHAR(128);
ALTER TABLE v1_record_versions ADD COLUMN sort_at VARCHAR(40);
UPDATE v1_record_versions SET logical_id = id, sort_at = created_at;
CREATE INDEX versions_business_order ON v1_record_versions (tenant_id,brand_id,kind,location_id,sort_at,logical_id);
