CREATE INDEX webhook_active_jobs ON app_records (kind,created_at) WHERE kind='v1_webhook'
  AND payload::jsonb ->> 'active' = 'true' AND payload::jsonb ->> '_deletedAt' IS NULL;
CREATE INDEX upload_cleanup_jobs ON app_records (kind,created_at) WHERE kind='v1_upload'
  AND payload::jsonb ->> '_deletedAt' IS NULL;
CREATE INDEX report_export_jobs ON app_records (kind,created_at) WHERE kind='v1_export'
  AND payload::jsonb ->> 'status' IN ('queued','processing') AND payload::jsonb ->> '_deletedAt' IS NULL;
CREATE INDEX announcement_delivery_jobs ON app_records (kind,created_at) WHERE kind='v1_announcement'
  AND payload::jsonb ->> 'pushNotify' = 'true' AND payload::jsonb ->> 'notifiedAt' IS NULL;
CREATE INDEX audit_changes_object ON audit_changes (tenant_id,brand_id,location_id,row_id,created_at,id);
