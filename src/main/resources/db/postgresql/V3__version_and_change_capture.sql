CREATE INDEX v1_records_brand ON app_records (tenant_id, kind, (payload::jsonb ->> '_brandId'), location_id, created_at, id);
CREATE INDEX v1_records_status ON app_records (tenant_id, kind, (payload::jsonb ->> '_brandId'), (payload::jsonb ->> 'status'));
CREATE INDEX v1_records_node ON app_records (tenant_id, kind, (payload::jsonb ->> '_brandId'), (payload::jsonb ->> 'nodeId'));
CREATE OR REPLACE FUNCTION audit_redact(value jsonb) RETURNS jsonb LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE result jsonb; pair record;
BEGIN
  IF jsonb_typeof(value) = 'object' THEN
    result := '{}'::jsonb;
    FOR pair IN SELECT * FROM jsonb_each(value) LOOP
      IF pair.key ~* '(password|secret|token|credential|code|qr|cpf|phone|email|document|cipher|keyhash|proof)' THEN
        result := result || jsonb_build_object(pair.key, '***');
      ELSE result := result || jsonb_build_object(pair.key, audit_redact(pair.value)); END IF;
    END LOOP;
    RETURN result;
  ELSIF jsonb_typeof(value) = 'array' THEN
    SELECT coalesce(jsonb_agg(audit_redact(item)), '[]'::jsonb) INTO result FROM jsonb_array_elements(value) item;
    RETURN result;
  END IF;
  RETURN value;
END $$;

CREATE OR REPLACE FUNCTION capture_record_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE row_data app_records; before_data jsonb; after_data jsonb; brand text;
  moment bigint; request text; actor text; change_id text; target_id text;
BEGIN
  row_data := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  before_data := CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.payload::jsonb END;
  after_data := CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE NEW.payload::jsonb END;
  brand := coalesce(after_data ->> '_brandId', before_data ->> '_brandId');
  moment := (extract(epoch FROM clock_timestamp()) * 1000000)::bigint;
  request := nullif(current_setting('app.request_id', true), '');
  actor := nullif(current_setting('app.actor_id', true), '');
  target_id := coalesce(after_data ->> '_id', before_data ->> '_id', row_data.id);
  IF row_data.kind LIKE 'v1_%' AND row_data.kind NOT IN ('v1_derived_snapshot','v1_identifier','v1_challenge','v1_session','v1_installation','v1_push_registration') THEN
    UPDATE v1_record_versions SET valid_to = moment WHERE id = row_data.id AND valid_to IS NULL;
    IF TG_OP <> 'DELETE' THEN
      INSERT INTO v1_record_versions (id,version,kind,tenant_id,location_id,owner_id,brand_id,payload,created_at,updated_at,valid_from,deleted)
      VALUES (NEW.id,NEW.version,NEW.kind,NEW.tenant_id,NEW.location_id,NEW.owner_id,brand,NEW.payload,NEW.created_at,NEW.updated_at,moment,after_data ->> '_deletedAt' IS NOT NULL);
    END IF;
  END IF;
