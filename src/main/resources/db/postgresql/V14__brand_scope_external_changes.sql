CREATE OR REPLACE FUNCTION capture_record_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE row_data app_records; before_data jsonb; after_data jsonb; brand text;
  moment bigint; request text; actor text; change_id text; target_id text;
BEGIN
  row_data := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  before_data := CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.payload::jsonb END;
  after_data := CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE NEW.payload::jsonb END;
  brand := coalesce(after_data ->> '_brandId', before_data ->> '_brandId',
    nullif(current_setting('app.brand_id', true), ''));
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
  IF row_data.kind IN ('v1_derived_snapshot','rate_limit','auth_delivery','notification_delivery','v1_identifier','v1_idempotency') THEN RETURN NULL; END IF;
  IF row_data.kind IN ('account','session','challenge','v1_session','v1_challenge','v1_push_registration','v1_installation') THEN
    before_data := CASE WHEN before_data IS NULL THEN NULL ELSE '{"sensitive":"***"}'::jsonb END;
    after_data := CASE WHEN after_data IS NULL THEN NULL ELSE '{"sensitive":"***"}'::jsonb END;
  END IF;
  change_id := gen_random_uuid()::text;
  INSERT INTO audit_changes (id,tenant_id,brand_id,location_id,request_id,table_name,row_id,created_at,payload)
  VALUES (change_id,row_data.tenant_id,brand,row_data.location_id,request,'app_records',target_id,to_char(clock_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
    jsonb_build_object('id',change_id,'requestId',request,'table',row_data.kind,'rowPk',jsonb_build_object('id',target_id),
      'op',lower(TG_OP),'changes',jsonb_build_object('before',audit_redact(before_data),'after',audit_redact(after_data)),
      'actorKind',CASE WHEN request IS NULL THEN 'database' ELSE coalesce(nullif(current_setting('app.actor_kind',true),''),'anonymous') END,
      'actorName',actor,'actorRole',nullif(current_setting('app.actor_role',true),''),'operationId',nullif(current_setting('app.operation_id',true),''),
      'createdAt',to_char(clock_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))::text);
  RETURN NULL;
END $$;
