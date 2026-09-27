CREATE OR REPLACE FUNCTION track_version_transaction() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN NEW.created_tx := pg_current_xact_id()::text;
  ELSIF NEW.valid_to IS DISTINCT FROM OLD.valid_to THEN NEW.closed_tx := pg_current_xact_id()::text;
  END IF;
  NEW.logical_id := coalesce(NEW.payload::jsonb ->> '_id', NEW.id);
  NEW.sort_at := coalesce(NEW.payload::jsonb ->> 'depositedAt', NEW.payload::jsonb ->> 'startsAt', NEW.created_at);
  RETURN NEW;
END $$;
UPDATE v1_record_versions SET logical_id = coalesce(payload::jsonb ->> '_id',id),
  sort_at = coalesce(payload::jsonb ->> 'depositedAt',payload::jsonb ->> 'startsAt',created_at);
