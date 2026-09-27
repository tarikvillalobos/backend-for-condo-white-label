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
