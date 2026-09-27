CREATE OR REPLACE FUNCTION track_version_transaction() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN NEW.created_tx := pg_current_xact_id()::text;
  ELSIF NEW.valid_to IS DISTINCT FROM OLD.valid_to THEN NEW.closed_tx := pg_current_xact_id()::text;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER record_version_transaction BEFORE INSERT OR UPDATE ON v1_record_versions
FOR EACH ROW EXECUTE FUNCTION track_version_transaction();
CREATE INDEX snapshot_page_order ON v1_record_versions (tenant_id,brand_id,kind,location_id,created_at,id);
