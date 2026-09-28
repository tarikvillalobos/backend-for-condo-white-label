ALTER TABLE v1_record_versions ADD COLUMN created_sort VARCHAR(40);
CREATE OR REPLACE FUNCTION track_version_transaction() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN NEW.created_tx := pg_current_xact_id()::text;
  ELSIF NEW.valid_to IS DISTINCT FROM OLD.valid_to THEN NEW.closed_tx := pg_current_xact_id()::text;
  END IF;
  NEW.logical_id := coalesce(NEW.payload::jsonb ->> '_id', NEW.id);
  NEW.created_sort := to_char(NEW.created_at::timestamptz AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
  NEW.sort_at := to_char(coalesce((NEW.payload::jsonb ->> 'depositedAt')::timestamptz,
    (NEW.payload::jsonb ->> 'startsAt')::timestamptz,NEW.created_at::timestamptz) AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
  RETURN NEW;
END $$;
UPDATE v1_record_versions SET created_sort = to_char(created_at::timestamptz AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
 sort_at = to_char(coalesce((payload::jsonb ->> 'depositedAt')::timestamptz,
 (payload::jsonb ->> 'startsAt')::timestamptz,created_at::timestamptz) AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
CREATE INDEX snapshot_created_order ON v1_record_versions (tenant_id,brand_id,kind,location_id,created_sort,logical_id);
